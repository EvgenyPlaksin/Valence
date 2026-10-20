# Valence — Architecture Specification

| | |
|---|---|
| **Status** | Draft v1.3 |
| **Scope** | System contracts, module boundaries, data design, concurrency model |
| **Out of scope** | Application code, UI design, push-notification vendor integration |
| **Audience** | Maintainers, security reviewers, contributors |

Valence is an open-source, zero-knowledge messaging platform. The client is an offline-first Android application (Kotlin, Jetpack Compose, Dagger Hilt, Room). The server is a stateless-by-design asynchronous relay (Kotlin, Ktor) that routes opaque ciphertext and has no capability to read message content. All end-to-end cryptography is provided by libsignal. Valence composes it and does not implement it.

---

## 0. Principles and Non-Goals

1. **Zero knowledge by construction.** The server is never given key material that could decrypt content. Privacy does not depend on server-side policy or operator honesty.
2. **Do not invent cryptography.** Valence uses the Signal Protocol as implemented by libsignal: PQXDH session establishment, the Double Ratchet, and Sealed Sender. No Valence code implements primitives, key agreement, ratcheting, or message encryption. Hand-rolled cryptography is prohibited. libsignal is reached only through the interfaces of `:core:crypto` on the client and a dedicated `crypto-support` package on the server, which is limited to signature verification and sender-certificate issuance.
3. **Offline-first.** The local database is the single source of truth for the UI. The network is a synchronisation channel, never a rendering dependency.
4. **Strict boundaries.** Dependencies point inward. Features never reference each other's implementations. Infrastructure is replaceable behind interfaces.
5. **Honest threat model.** Section 1.7 states what the server can and cannot observe. Claims are limited to what the design guarantees.

---

## 1. System Design & Security Protocol

### 1.1 Primitive Selection

| Purpose | Mechanism | Provided by |
|---|---|---|
| Session establishment | PQXDH: X25519 key agreement hybridised with a Kyber KEM, authenticated by signed prekeys | libsignal |
| Message encryption | Double Ratchet with per-message keys, forward secrecy, and post-compromise security | libsignal |
| Identity and prekey signatures | Signatures under the identity key | libsignal |
| Sender anonymity | Sealed Sender: outer layer sealed to the recipient's identity key, sender certificate issued by the server | libsignal |
| Safety numbers | Numeric fingerprint of both identity keys | libsignal |
| Local at-rest | SQLCipher (AES-256) with a Keystore-wrapped passphrase | SQLCipher, Android Keystore |
| Key wrapping on device | Android Keystore AES-256-GCM key (StrongBox when available) | Android Keystore |
| Padding | Length bucketing to 256 B multiples inside the plaintext | Valence (not cryptography) |

The exact cipher suites (message cipher, MAC, key derivation, KEM parameters) are those of the pinned libsignal version. Valence neither selects nor modifies them, and this table states intent, not parameters. Reviewers verify the parameters against the pinned version when the dependency is added or upgraded.

PQXDH adds a Kyber key encapsulation to the classical X25519 handshake. Recorded traffic therefore stays confidential even if a large quantum computer later breaks X25519, because an attacker would have to break both mechanisms.

RSA is explicitly not used.

### 1.2 Identity and Key Inventory

Each device holds:

| Key | Type | Lifetime | Published to server |
|---|---|---|---|
| Identity key pair (IK) | libsignal identity key pair; signs prekeys | Permanent per install | Public part |
| Signed prekey (SPK) | EC prekey, signed by IK | Rotated every 7 days | Public part and signature |
| Kyber prekey (KPK) | Kyber KEM prekey, signed by IK. One last-resort prekey in v1 | Rotated with the SPK | Public part and signature |
| One-time prekeys (OPK) | EC prekeys | Single use, batch of 100, replenished at threshold 25 | Public parts |
| Delivery key (DK) | Random 128-bit value | Permanent until rotated | SHA-256 hash only; the key itself is shared with contacts |
| Session state | Ratchet, chain, and message keys, held inside libsignal session records | Per contact | Never |

Ephemeral and ratchet keys are created and rotated inside libsignal. Valence never handles them directly.

The **mailbox ID** is the only server-visible address. It is a random 128-bit value (`mbx_` plus base32), generated client-side, not derived from phone number, e-mail, or username. Contact discovery maps human identifiers to mailbox IDs out of band (QR code, invite link, or an optional hashed-contact directory that is disabled by default). The invite link or QR code also carries the contact's delivery key.

### 1.3 Registration Handshake

```
Client                                                     Ktor Server
  | generate IK, SPK, KPK, OPK[100], DK                         |
  | sign SPK and KPK with IK                                    |
  |-- POST /v1/register ---------------------------------------->|
  |   { mailboxId, identityKey, signedPreKey{id,pub,sig},        |
  |     kyberPreKey{id,pub,sig}, oneTimePreKeys[{id,pub}],       |
  |     deliveryKeyHash, proof }                                 |
  |                                                              | verify signatures under IK
  |                                                              | verify proof-of-possession of IK
  |<-- 201 { accessToken(short JWT), refreshToken } -------------|
```

`proof` is a signature under IK over the server-issued registration challenge, binding the mailbox to the identity key. The server stores public keys and the delivery key hash only. The JWT authorises key management, sender-certificate issuance, and the receive socket. It is never attached to message sends (see 1.6).

### 1.4 Session Establishment (PQXDH)

Alice initiates to Bob.

1. Alice calls `GET /v1/prekeys/{bobMailboxId}`, presenting Bob's delivery key in the `X-Delivery-Key` header and no JWT. The server returns `IK_B`, `SPK_B` with its signature, `KPK_B` with its signature, and atomically **consumes** one `OPK_B` (or none if exhausted).
2. Alice hands the bundle to libsignal's session builder. It verifies both signatures under `IK_B`, performs the PQXDH key agreement, and initialises the Double Ratchet. Any verification failure aborts the send.
3. Alice's first message is a libsignal pre-key message. It carries what Bob needs to derive the same secret: Alice's identity key, her base key, the ids of the prekeys used, and the Kyber ciphertext. Like every message it is wrapped in Sealed Sender (1.6), so the server never sees these fields.
4. Bob's libsignal processes the pre-key message and establishes the session. Valence's prekey store deletes the consumed one-time prekey in the same transaction that persists the message (4.3).
5. Identity keys are trusted on first use. A later change to a contact's identity key raises a blocking trust-change event (1.8).

Valence does not restate the key-agreement formulas. The authoritative references are Signal's PQXDH and Double Ratchet specifications and the pinned libsignal source.

### 1.5 Message Encryption (Double Ratchet)

Per message, the client calls libsignal's session cipher with the padded plaintext. libsignal advances the ratchet, derives the message key, and produces the encrypted message.

- Key derivation and the choice of AEAD or MAC are internal to libsignal. The message authentication covers both parties' identity keys, so Valence defines no associated data of its own.
- The DH ratchet advances whenever a new ratchet public key is received, providing forward secrecy and post-compromise security.
- Skipped message keys, which tolerate out-of-order delivery, are cached inside the session record within libsignal's built-in bounds. Valence keeps no separate table for them.
- Plaintext is a length-padded structure (bucketed to 256 B multiples) containing message type, sender timestamp, body, and attachment metadata. The sender timestamp therefore lives only inside the ciphertext.
- A message key is never reused or persisted outside libsignal's session record.

### 1.6 Blind-Router Contract

The server is restricted to the following operations on message traffic:

| Server may | Server must never |
|---|---|
| Authenticate the receive connection, resolve the `to` mailbox, verify the delivery key on a send, enqueue the opaque `envelope`, deliver, delete on confirm | Parse, decrypt, log, or index `envelope` contents |
| Stamp `serverSeq` and `serverTs` on enqueue | Learn or store the sender of a message |
| Enforce TTL (default 30 days) and per-mailbox quotas | Retain delivered messages after confirm |
| Rate-limit by recipient mailbox and IP address | Hold any private key or session secret |
| Issue short-lived sender certificates to authenticated mailboxes | Record which mailbox sent to which |

**Sealed Sender.** Every message is sealed, starting with the first. The envelope has two layers:

- **Inner layer:** a libsignal session message (a pre-key message or an ordinary message) encrypted for the recipient's session.
- **Outer layer:** sealed to the recipient's identity key. It contains the sender certificate and the inner message. Only the recipient can open it, and opening it reveals the sender's mailbox ID and identity key to the recipient and to nobody else.

**Sender certificates.** A mailbox obtains a certificate with `GET /v1/sender-certificate` (JWT-authenticated). The certificate binds the mailbox ID and the identity key, expires after 24 hours, and is signed by a server certificate key chained to a trust root whose public key is pinned in the client. Recipients reject envelopes whose certificate is missing, expired, invalidly signed, or whose identity key does not match the session.

**Anonymous sends.** Sending over the authenticated socket would identify the sender through its JWT, so sends never use it. A send is `POST /v1/messages/{to}` with the recipient's delivery key in `X-Delivery-Key`, over a connection carrying no account credential. The server compares the SHA-256 of the presented key with the stored hash in constant time. The recipient generates the delivery key, registers its hash, and gives the key to contacts in the invite link or QR code. On rotation, the new key is sent to each contact in an ordinary encrypted message. This is modelled on Signal's unidentified-access key. A leaked delivery key lets third parties enqueue messages up to the quotas, but never read anything, and rotation invalidates it. Prekey fetches use the same mechanism.

### 1.7 Threat Model: What the Server Can Observe

"Never stores unencrypted metadata" is only achievable for **content-bearing** metadata. Valence guarantees the following and documents the residue.

| Data | Server visibility |
|---|---|
| Message body, attachments, filenames, MIME types | None |
| Sender timestamp, read/typing state | None (inside ciphertext; receipts are ordinary encrypted messages) |
| Sender identity | None, from the first message onward (Sealed Sender) |
| Who requested a prekey bundle | Not tied to an identity (delivery-key authorised); IP address visible at request time |
| Recipient mailbox ID | Visible, required for routing; pseudonymous and random |
| Sender-certificate requests | Visible as authenticated requests by a mailbox; reveal that it is active, not whom it contacts |
| Approximate message size | Visible; mitigated by padding buckets |
| Server arrival time and IP address | Visible; IP is not persisted, arrival time is retained only until confirm |
| Public key bundles and delivery key hash | Visible by design |

Out of scope for v1: traffic-analysis resistance against a global passive adversary (including timing correlation between an anonymous send and the matching delivery), and protection against a compromised client OS. Contributors must not claim otherwise in documentation.

### 1.8 Key Verification

Users verify a contact by comparing a **safety number** produced by libsignal's numeric fingerprint generator, rendered as 60 digits or a QR code. The generator defines the canonical ordering of the two identities and the hash iteration, so both sides compute the same value. A changed identity key raises a blocking trust-change event in the conversation layer rather than silently re-keying.

---

## 2. Repository and Android Multi-Module Architecture

### 2.1 Directory Structure

```
valence/
├── AGENTS.md
├── docs/
│   └── ARCHITECTURE.md
├── protocol/                             Standalone Gradle build: shared wire contracts
├── android/                              Android Gradle build
│   ├── app/                              Composition root only
│   ├── build-logic/                      Convention plugins (Gradle)
│   │   └── convention/
│   ├── core/
│   │   ├── model/                        Pure Kotlin domain types and repository interfaces
│   │   ├── common/                       Result types, dispatchers, clock, id generators
│   │   ├── crypto/                       Key management, session cipher, Sealed Sender (libsignal lives only here)
│   │   ├── database/                     Room, DAOs, SQLCipher, migrations
│   │   ├── network/                      Ktor/OkHttp client, WebSocket transport, mapping from protocol DTOs
│   │   ├── data/                         Repository implementations
│   │   └── ui-theme/                     Material 3 theme, design tokens, shared composables
│   └── features/
│       ├── auth/
│       │   ├── api/                      Public contract: navigation entry, AuthState, AuthSessionObserver
│       │   └── impl/                     Screens, ViewModels, use cases, Hilt module
│       ├── chat-list/
│       │   ├── api/
│       │   └── impl/
│       └── conversation/
│           ├── api/
│           └── impl/
└── server/                               Standalone Gradle build: Ktor backend
```

**Builds and the protocol module.** The repository is a monorepo of three independent Gradle builds: `protocol/`, `android/`, and `server/`. Each has its own wrapper and is built from its own directory. `android/` and `server/` both include `protocol/` with `includeBuild("../protocol")`, so a change there is visible to both sides immediately and exists exactly once.

`protocol/` is a pure Kotlin/JVM library using `kotlinx.serialization`. It contains:

- one class per wire frame and per HTTP request or response body (no shared generic DTOs)
- protocol version constants
- golden JSON fixtures, published as test fixtures and used by contract tests on both sides

It contains no cryptography, no Android code, and no Ktor code. Wire DTOs are defined only here. `:core:network` maps them to domain types at its boundary.

### 2.2 Core Module Responsibilities

| Module | Responsibility | Exposes | Hides |
|---|---|---|---|
| `:core:model` | Domain entities (`Message`, `Contact`, `Conversation`, `MessageStatus`), value classes (`MailboxId`, `MessageId`), repository interfaces | Immutable data types, repository interfaces | Nothing; has zero Android dependencies |
| `:core:common` | `DispatcherProvider`, `Clock`, `IdGenerator`, `AppResult<T>`, error taxonomy | Interfaces | Implementations |
| `:core:crypto` | Key generation, Keystore wrapping, session establishment, message encryption, Sealed Sender, safety numbers | `KeyStore`, `SessionCipher`, `SealedSender`, `PreKeyProvider`, `SafetyNumberGenerator`, and the `SignalStateStore` port | libsignal and all of its types, raw key bytes, ratchet internals |
| `:core:database` | Room schema, DAOs, SQLCipher integration, migrations, type converters | DAO interfaces, `ValenceDatabase` | Entity classes are `internal` and mapped at the boundary |
| `:core:network` | HTTP client, WebSocket transport, reconnection, auth interceptor, mapping between `protocol` DTOs and domain types | `MessageTransport`, `PreKeyApi`, `RegistrationApi`, `SenderCertificateApi` interfaces | OkHttp/Ktor client types, retry policy, TLS pinning, `protocol` DTOs |
| `:core:data` | Repository implementations binding crypto, database, and network; the adapter implementing `SignalStateStore` over the DAOs | `MessageRepository`, `ContactRepository`, `SessionRepository` | Orchestration, inbound pipeline, outbox |
| `:core:ui-theme` | Theme, typography, spacing tokens, reusable stateless composables | `ValenceTheme`, shared components | Nothing business-related |

Repository interfaces are declared in `:core:model`'s contract package (`core.model.repository`) so that features and `:core:data` depend on the same abstraction without depending on each other.

libsignal types never cross the `:core:crypto` boundary. Inputs and outputs are `:core:model` types and byte arrays. libsignal needs persistent stores for identity, prekeys, and sessions. `:core:crypto` declares them as the `SignalStateStore` port, and `:core:data` implements the port over the DAOs, so `:core:crypto` never depends on `:core:database`.

### 2.3 Dependency Rules

```
app ──────────► features:*:impl ──► features:*:api
 │                    │                    │
 │                    ▼                    ▼
 └──────────────► core:data ───► core:crypto / database / network ──► protocol
                      │
                      ▼
               core:model ◄── core:common
```

Enforced rules:

Every module has a defined set of modules it may depend on, and any other dependency fails the build. A module without a rule also fails, so a new module must be added to the rules deliberately.

| Module | May depend on |
|---|---|
| `:app` | Any module (composition root) |
| `:features:X:impl` | `:features:*:api`, `:core:model`, `:core:common`, `:core:ui-theme` |
| `:features:X:api` | `:core:model`, `:core:common` |
| `:core:data` | `:core:model`, `:core:common`, `:core:crypto`, `:core:database`, `:core:network` |
| `:core:crypto`, `:core:database`, `:core:network` | `:core:model`, `:core:common` |
| `:core:common` | `:core:model` |
| `:core:model`, `:core:ui-theme` | No other module |

Additional rules:

1. Only `:app` may depend on a `features:*:impl` module. Features reach each other only through `api` modules.
2. Features consume repository interfaces from `:core:model` only. They never depend on `:core:data`, `:core:database`, `:core:network`, `:core:crypto`, or `protocol`.
3. `:core:model`, `:core:common`, and `features:*:api` modules use no libraries other than Kotlin and kotlinx.
4. `:app` is the only module that sees every `impl`. It wires them through Hilt.
5. `:api` modules contain only interfaces, navigation route definitions, and immutable value types. This rule is enforced by review.
6. libsignal is a dependency of `:core:crypto` only. On the client, only `:core:network` depends on `protocol`.
7. Classes in `..domain..` packages of `impl` modules import only the Kotlin standard library, kotlinx.coroutines, `:core:model` and `:core:common` types, `features:*:api` types, types of their own `domain` package tree, and `javax.inject.Inject`.

The table and rules 1 to 4 and 6 are verified at module level by the Gradle task `checkModuleGraph`, which reads the dependency model of the build and therefore also sees dependencies added by convention plugins. Rule 7 and the other source-level rules in section 5 are verified by Konsist tests. Both run in CI.

### 2.4 Feature Decoupling

Features communicate through three mechanisms only.

**a) Navigation contracts and state observers.** Each feature's `api` module exports a destination and a navigator interface, and any state other features need to observe. `AuthState` and `AuthSessionObserver` both live in `auth:api`. Other features' `impl` modules depend on that `api` module, never on its `impl`.

```kotlin
interface ConversationNavigator {
    fun routeFor(conversationId: ConversationId): NavRoute
}

interface AuthSessionObserver {
    val state: StateFlow<AuthState>
}
```

**b) Shared domain interfaces** in `:core:model` (repositories). A feature receives them through constructor injection and never knows the concrete class.

**c) Event bus via Flow.** Cross-feature notifications that are not state (for example, "identity key changed") are published on a typed `SharedFlow` owned by `:core:data` and exposed through a domain interface.

Hilt binding strategy:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
interface ConversationFeatureModule {
    @Binds fun bindNavigator(impl: ConversationNavigatorImpl): ConversationNavigator
}
```

`chat-list` navigates to a conversation by injecting `ConversationNavigator`, which it sees through `conversation:api`. Removing or replacing the `conversation:impl` module requires no change in `chat-list`.

### 2.5 Presentation Layer Within a Feature

Each `impl` module follows the same internal layering: `ui` (Compose screens, stateless), `presentation` (ViewModel with a single immutable `UiState` and a sealed `UiEvent`/`UiAction` pair), `domain` (use cases, one public `operator fun invoke` each). Unidirectional data flow is mandatory.

The `domain` package is pure Kotlin apart from `@Inject` on use-case constructors (rule 7). It never imports Android, Room, Ktor, or Hilt types.

---

## 3. Data Models & State Contracts

### 3.1 Wire Contract (`protocol/`, JSON, `kotlinx.serialization`)

Valence uses two transports. The authenticated WebSocket is used only to **receive**. Anonymous HTTPS is used to **send** and to fetch prekey bundles, so that no account credential accompanies them (see 1.6). All bodies use `v` to govern breaking changes. Binary fields are Base64URL without padding. Unknown fields are ignored.

**Client → Server (anonymous HTTPS): send**

`POST /v1/messages/{to}` with header `X-Delivery-Key: <base64url delivery key>`

```json
{
  "v": 1,
  "envelope": "u8F2...3sA",
  "ttlSeconds": 2592000
}
```

Response `201`:

```json
{
  "v": 1,
  "serverTs": 1760000000123
}
```

An unknown mailbox and an invalid delivery key both return `401`, so mailbox existence cannot be probed. Oversized bodies return `413` and rate limiting returns `429` with `Retry-After`.

**Server → Client (WebSocket): `message.deliver`**

```json
{
  "type": "message.deliver",
  "v": 1,
  "serverSeq": 884213,
  "serverTs": 1760000000123,
  "envelope": "u8F2...3sA"
}
```

**Client → Server (WebSocket): `delivery.confirm`** (client has durably stored; server may delete)

```json
{
  "type": "delivery.confirm",
  "v": 1,
  "upToServerSeq": 884213
}
```

**Client → Server (WebSocket): `sync.resume`**

```json
{
  "type": "sync.resume",
  "v": 1,
  "afterServerSeq": 884200
}
```

**Server → Client (WebSocket): `error`**

```json
{
  "type": "error",
  "v": 1,
  "code": "RATE_LIMITED",
  "retryAfterMs": 2000
}
```

**Prekey bundle** (`GET /v1/prekeys/{mailboxId}`, header `X-Delivery-Key`)

```json
{
  "v": 1,
  "identityKey": "Zp0t...Lk",
  "signedPreKey": { "id": 12, "pub": "b7Xc...9d", "sig": "Qm4v...8w" },
  "kyberPreKey": { "id": 3, "pub": "k9Lp...2f", "sig": "Tn7a...5c" },
  "oneTimePreKey": { "id": 48, "pub": "mC9e...Qw" }
}
```

`oneTimePreKey` is absent when the mailbox's one-time prekeys are exhausted.

**Sender certificate** (`GET /v1/sender-certificate`, JWT-authenticated)

```json
{
  "v": 1,
  "certificate": "c3Vw...Zg",
  "expiresAt": 1760086400000
}
```

**Field semantics.**

- `v` is the integer protocol version of a body. The current version is 1. The server accepts N and N-1.
- `serverTs` and `expiresAt` are Unix epoch milliseconds in UTC. `serverTs` is the server's clock at the moment it accepts a message, never the sender's clock.
- `envelope` is the Base64URL encoding of the sealed envelope (1.6). The server treats it as opaque bytes.
- `ttlSeconds` is optional and states how long the server may keep an uncollected message. If it is absent, the server applies its default of 30 days. The server clamps larger values to 30 days and rejects values below 1 with `400`.
- `serverSeq` is a 64-bit integer assigned by the server when it enqueues a message. It is strictly increasing per mailbox, starts at 1, and is never reused. Gaps are possible, for example after the TTL deleted messages.
- `upToServerSeq` in `delivery.confirm` is inclusive and cumulative. The client states that it has durably stored every message with `serverSeq` less than or equal to N, and the server may delete all of them. Repeating a confirmation, or sending a lower value than before, has no effect.
- `afterServerSeq` in `sync.resume` is exclusive. The client sends the highest `serverSeq` it has confirmed, or 0 on the first connection, and the server replays every stored message with a greater `serverSeq` in ascending order.
- `code` in `error` is machine-readable. v1 defines only `RATE_LIMITED`. A client treats any code it does not know as unknown. `retryAfterMs` is the wait before retrying, in milliseconds. The HTTP equivalent is the `Retry-After` header, which counts in seconds.

**Keys and prekey ids.**

- `identityKey` and every `pub` are public keys. `sig` is the identity key's signature over the corresponding `pub`. One-time prekeys carry no signature.
- A prekey `id` is assigned by the client that owns the key, at the moment it generates the key. Ids come from a counter that is persisted per key kind and never reused. An id is a positive 32-bit integer (1 to 2,147,483,647).
- Signed, Kyber, and one-time prekeys have separate id namespaces. Id 3 can exist as a signed prekey, a Kyber prekey, and a one-time prekey at the same time. The client and server schemas (3.2, 4.3) already store each kind separately.
- The server treats an id as an opaque label. It stores the id with the key and returns it unchanged. An upload that repeats an id of the same kind in the same mailbox is rejected.
- The sender names the prekeys it used by id in its first message, and the recipient uses those ids to find the matching private keys. Because ids are never reused, a bundle fetched earlier can never point to a different key than the one the sender saw.

**Decrypted plaintext structure** (never visible to the server):

```json
{
  "kind": "text",
  "messageId": "01J9ZQ3M5X7K2R8T4W6Y0B1C2D",
  "sentAt": 1760000000000,
  "conversationId": "c_8f2a...",
  "body": "Hello",
  "replyTo": null,
  "attachments": [],
  "padding": "AAAA..."
}
```

The sender's mailbox ID is not repeated inside the plaintext. The authoritative sender is the verified sender certificate of the sealed envelope, which must match the session's identity key.

`messageId` is a UUIDv7/ULID, giving idempotency and rough time ordering. Receivers deduplicate on `messageId`.

### 3.2 Server Persistence (opaque relay store)

```sql
CREATE TABLE mailbox (
    mailbox_id         TEXT PRIMARY KEY,
    identity_key       BYTEA       NOT NULL,
    signed_prekey_id   INTEGER     NOT NULL,
    signed_prekey      BYTEA       NOT NULL,
    signed_prekey_sig  BYTEA       NOT NULL,
    kyber_prekey_id    INTEGER     NOT NULL,
    kyber_prekey       BYTEA       NOT NULL,
    kyber_prekey_sig   BYTEA       NOT NULL,
    delivery_key_hash  BYTEA       NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE one_time_prekey (
    mailbox_id TEXT    NOT NULL REFERENCES mailbox(mailbox_id) ON DELETE CASCADE,
    prekey_id  INTEGER NOT NULL,
    public_key BYTEA   NOT NULL,
    PRIMARY KEY (mailbox_id, prekey_id)
);

CREATE TABLE inbox_message (
    mailbox_id TEXT        NOT NULL REFERENCES mailbox(mailbox_id) ON DELETE CASCADE,
    server_seq BIGINT      NOT NULL,
    envelope   BYTEA       NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (mailbox_id, server_seq)
);

CREATE INDEX idx_inbox_expiry ON inbox_message (expires_at);
```

`envelope` is stored as received. There is no sender column, no plaintext index, and no message-level analytics table. The sender-certificate signing key is held in the server's secret store and never in this database.

### 3.3 Android Local Schema (Room over SQLCipher)

Entities are `internal` to `:core:database` and mapped to `:core:model` types at the DAO boundary.

**Cryptographic material**

```sql
CREATE TABLE identity_key (
    id                INTEGER PRIMARY KEY CHECK (id = 1),
    public_key        BLOB    NOT NULL,
    wrapped_private   BLOB    NOT NULL,
    wrap_iv           BLOB    NOT NULL,
    keystore_alias    TEXT    NOT NULL,
    created_at        INTEGER NOT NULL
);

CREATE TABLE signed_prekey (
    prekey_id         INTEGER PRIMARY KEY,
    public_key        BLOB    NOT NULL,
    wrapped_private   BLOB    NOT NULL,
    wrap_iv           BLOB    NOT NULL,
    signature         BLOB    NOT NULL,
    created_at        INTEGER NOT NULL,
    retired_at        INTEGER
);

CREATE TABLE kyber_prekey (
    prekey_id         INTEGER PRIMARY KEY,
    public_key        BLOB    NOT NULL,
    wrapped_private   BLOB    NOT NULL,
    wrap_iv           BLOB    NOT NULL,
    signature         BLOB    NOT NULL,
    created_at        INTEGER NOT NULL,
    retired_at        INTEGER
);

CREATE TABLE one_time_prekey (
    prekey_id         INTEGER PRIMARY KEY,
    public_key        BLOB    NOT NULL,
    wrapped_private   BLOB    NOT NULL,
    wrap_iv           BLOB    NOT NULL,
    uploaded          INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE signal_session (
    contact_id        TEXT    PRIMARY KEY REFERENCES contact(contact_id) ON DELETE CASCADE,
    record_blob       BLOB    NOT NULL,
    record_iv         BLOB    NOT NULL,
    record_version    INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL
);

CREATE TABLE delivery_key (
    id                INTEGER PRIMARY KEY CHECK (id = 1),
    key_bytes         BLOB    NOT NULL,
    created_at        INTEGER NOT NULL
);

CREATE TABLE sender_certificate (
    id                INTEGER PRIMARY KEY CHECK (id = 1),
    certificate       BLOB    NOT NULL,
    expires_at        INTEGER NOT NULL
);
```

The session record is libsignal's serialised session state, including its skipped message keys. It is encrypted as a single blob and written in the same transaction as the message it advanced (see 4.3). This guarantees that the ratchet never advances without the corresponding message being persisted, and vice versa. The delivery key and the contacts' delivery keys are bearer credentials that authorise sends, not decryption keys, so file-level SQLCipher protection is sufficient for them.

**Contacts and conversations**

```sql
CREATE TABLE contact (
    contact_id         TEXT PRIMARY KEY,
    mailbox_id         TEXT    NOT NULL UNIQUE,
    display_name       TEXT    NOT NULL,
    identity_key       BLOB    NOT NULL,
    delivery_key       BLOB    NOT NULL,
    trust_state        INTEGER NOT NULL DEFAULT 0,
    safety_number_hash BLOB,
    created_at         INTEGER NOT NULL
);

CREATE TABLE conversation (
    conversation_id    TEXT PRIMARY KEY,
    contact_id         TEXT    NOT NULL REFERENCES contact(contact_id) ON DELETE CASCADE,
    last_message_id    TEXT,
    last_message_at    INTEGER,
    unread_count       INTEGER NOT NULL DEFAULT 0,
    pinned             INTEGER NOT NULL DEFAULT 0,
    muted_until        INTEGER
);

CREATE INDEX idx_conversation_recent ON conversation (pinned DESC, last_message_at DESC);
```

**Message ledger**

```sql
CREATE TABLE message (
    message_id         TEXT PRIMARY KEY,
    conversation_id    TEXT    NOT NULL REFERENCES conversation(conversation_id) ON DELETE CASCADE,
    direction          INTEGER NOT NULL,
    status             INTEGER NOT NULL,
    kind               INTEGER NOT NULL,
    body               TEXT,
    reply_to_id        TEXT,
    sent_at            INTEGER NOT NULL,
    server_seq         INTEGER,
    server_ts          INTEGER,
    received_at        INTEGER,
    created_at         INTEGER NOT NULL
);

CREATE INDEX idx_message_timeline ON message (conversation_id, sent_at DESC, message_id DESC);
CREATE UNIQUE INDEX idx_message_server_seq ON message (server_seq) WHERE server_seq IS NOT NULL;

CREATE TABLE outbox (
    message_id         TEXT PRIMARY KEY REFERENCES message(message_id) ON DELETE CASCADE,
    envelope_blob      BLOB,
    attempt_count      INTEGER NOT NULL DEFAULT 0,
    next_attempt_at    INTEGER NOT NULL,
    last_error         TEXT
);

CREATE TABLE sync_cursor (
    id                 INTEGER PRIMARY KEY CHECK (id = 1),
    last_confirmed_seq INTEGER NOT NULL
);
```

`direction`: `0 = INBOUND`, `1 = OUTBOUND`.
`status`: `0 = PENDING`, `1 = SENT` (server accepted), `2 = DELIVERED`, `3 = READ`, `4 = FAILED`.
`kind`: `0 = TEXT`, `1 = ATTACHMENT`, `2 = SYSTEM`.

`server_seq` is the sequence number of the **recipient's** inbox, so it is meaningful only for `INBOUND` rows in the owner's own inbox. Outbound rows keep `server_seq` null and record only `server_ts`. This keeps the unique index free of collisions between the two sequences. `outbox.envelope_blob` is null until the message has been encrypted (see 4.4).

Message bodies are protected by SQLCipher at the file level. No per-field encryption is layered on top, because that would defeat `LIKE`/FTS and duplicate the file-level guarantee.

### 3.4 Domain State Contracts

```kotlin
enum class MessageStatus { Pending, Sent, Delivered, Read, Failed }

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data object Connected : ConnectionState
    data class Backoff(val retryAtMillis: Long) : ConnectionState
}

sealed interface InboundResult {
    data class Stored(val messageId: MessageId) : InboundResult
    data class Duplicate(val messageId: MessageId) : InboundResult
    data class DecryptionFailed(val serverSeq: Long, val reason: DecryptFailure) : InboundResult
    data class IdentityChanged(val contactId: ContactId) : InboundResult
}

class UnsealedFrame(
    val serverSeq: Long,
    val senderMailboxId: MailboxId,
    val innerMessage: ByteArray
) {
    val shardKey: Int get() = senderMailboxId.hashCode()
}

sealed interface UnsealResult {
    data class Unsealed(val frame: UnsealedFrame) : UnsealResult
    data class Rejected(val reason: UnsealFailure) : UnsealResult
}
```

---

## 4. High-Concurrency Pipeline

### 4.1 Ktor Backend: Connection Pool

**Connection registry.** Each node holds a `ConcurrentHashMap<MailboxId, ConnectionHandle>`. One mailbox maps to at most one active handle per device slot; a newer connection supersedes and closes the older one.

```kotlin
class ConnectionHandle(
    val mailboxId: MailboxId,
    val session: DefaultWebSocketServerSession,
    val outbound: Channel<OutboundFrame> = Channel(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
)
```

**Per-connection actor model.** Each connection runs exactly two coroutines inside a `supervisorScope` tied to the session:

1. **Reader** consumes control frames from `incoming` (`delivery.confirm`, `sync.resume`, pings), validates frame size and schema, applies the rate limiter, and dispatches to the router. It never touches the socket write side.
2. **Writer** drains `outbound` and is the sole writer to the socket. This removes write contention and gives natural backpressure: a slow client fills its own channel and affects no other connection.

**Delivery path.**

```
POST /v1/messages/{to} ─► verify delivery key ─► validate ─► InboxStore.append(to, envelope) ─► assign serverSeq ─► 201 to sender
                                                                       │
                                                                       └─► Registry.lookup(to)
                                                                                ├─ local handle ─► outbound.trySend(deliver)
                                                                                └─ remote node  ─► Redis Pub/Sub / Streams notify
```

The append to durable storage **precedes** any live delivery. Live push is an optimisation; correctness comes from the persisted queue and the client's cursor.

**Backpressure and limits.**

| Concern | Policy |
|---|---|
| Slow consumer | `outbound` full for more than 5 s: close with code 1013, client resumes from cursor |
| Body size | Hard cap 64 KiB for envelopes, enforced before deserialisation |
| Rate limiting | Anonymous sends: token bucket per recipient mailbox (sustained 20 msg/s, burst 60) plus a per-IP limit. Authenticated endpoints: token bucket per mailbox |
| Idle detection | Ping every 20 s, close after 2 missed pongs |
| Per-node capacity | Sized by file descriptors and heap; target 50k–100k idle sockets per node on Netty with `-Xss` tuned down |
| Graceful shutdown | Stop accepting, send close 1001, drain writers, then terminate |

**Horizontal scale.** Nodes are stateless apart from the in-memory registry. Cross-node delivery uses a Redis Pub/Sub channel per mailbox shard (`mbx:{hash mod N}`). If no node holds the recipient, the message simply remains in the inbox and is delivered on next connect. Push notification (FCM) carries **no content**: a data-only wake-up that tells the client to open the socket.

**Resume protocol.** On connect the client sends `sync.resume { afterServerSeq }`. The server streams the backlog in ordered batches, then switches to live mode. Messages arriving during backlog replay are buffered in the same `outbound` channel to preserve order.

**Persistence concurrency.** `InboxStore.append` uses a per-mailbox sequence (`INSERT ... RETURNING` against a counter row, or a Redis `INCR`) so `serverSeq` is strictly monotonic per mailbox. Database access runs on a bounded `Dispatchers.IO.limitedParallelism(n)` view sized to the connection pool, preventing thread starvation under burst.

### 4.2 Android Client: Pipeline Overview

```
OkHttp WebSocket thread
        │  callbackFlow
        ▼
  Transport Flow<InboundFrame>                       Dispatchers.IO
        │  buffer(capacity = 512, SUSPEND)
        ▼
  Frame Router ─────────────► control frames (error, ping)
        │
        ▼
  Unseal (stateless, outer layer only)               Dispatchers.Default
        │  senderMailboxId + inner message
        ▼
  Sharded Dispatch: hash(senderMailboxId) mod K
        │
   ┌────┴─────┬──────────┬──────────┐
   ▼          ▼          ▼          ▼
 Channel₀  Channel₁   Channel₂   Channel₃                  one consumer coroutine per shard
   │          │          │          │
   ▼          ▼          ▼          ▼
 Decrypt → Persist(txn) → Emit domain event               Dispatchers.Default.limitedParallelism(K)
        │
        ▼
 Batched delivery.confirm (debounced 250 ms)
        │
        ▼
 Room Flow<List<Message>> ─► ViewModel (stateIn) ─► Compose     Main dispatcher only at collection
```

### 4.3 Stage Details

**Ingestion.** The transport exposes inbound frames as a cold `Flow` built with `callbackFlow`. The OkHttp listener uses `trySend` into a buffered channel and closes the flow on failure, which drives reconnection logic in the `ConnectionManager`.

**Unsealing.** The outer Sealed Sender layer is opened with the recipient's identity key. This is stateless and needs no session lookup, so it runs before sharding. It yields the verified sender mailbox ID and the inner session message. Frames whose outer layer or sender certificate is invalid are recorded as failures and confirmed, so the server can delete them. This stage requires the pinned libsignal version to expose unsealing separately from session decryption. If it does not, the pipeline runs with K = 1 (fully sequential), which is correct but slower, until the dependency or a thin adapter provides the split.

**Ordered parallelism.** A Double Ratchet session is a strict state machine, so messages from the same contact must be decrypted **sequentially**, while different contacts can proceed in parallel. The pipeline therefore shards by sender mailbox: a stable hash selects one of K worker channels (K = `min(cores, 4)`). Within a shard, ordering is preserved; across shards, work is concurrent. A global `Mutex` is deliberately avoided.

```kotlin
class InboundPipeline @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val unsealer: InboundUnsealer,
    private val decryptor: InboundDecryptor,
    private val ledger: MessageLedger,
    private val confirmations: ConfirmationBatcher,
    @InboundShardCount private val shardCount: Int
) {
    private val shards = List(shardCount) { Channel<UnsealedFrame>(capacity = 128) }

    fun start(scope: CoroutineScope, frames: Flow<InboundFrame>) {
        shards.forEach { channel -> scope.launch(dispatchers.default) { consume(channel) } }
        scope.launch(dispatchers.default) {
            frames
                .buffer(512)
                .collect { frame -> route(frame) }
        }
    }

    private suspend fun route(frame: InboundFrame) {
        when (val result = unsealer.unseal(frame)) {
            is UnsealResult.Unsealed -> shards[result.frame.shardKey.mod(shardCount)].send(result.frame)
            is UnsealResult.Rejected -> {
                ledger.recordRejected(frame, result.reason)
                confirmations.record(frame.serverSeq)
            }
        }
    }

    private suspend fun consume(channel: Channel<UnsealedFrame>) {
        for (frame in channel) {
            when (val result = decryptor.decryptAndPersist(frame)) {
                is InboundResult.Stored,
                is InboundResult.Duplicate -> confirmations.record(frame.serverSeq)
                is InboundResult.DecryptionFailed,
                is InboundResult.IdentityChanged -> ledger.recordFailure(frame, result)
            }
        }
    }
}
```

**Atomic persistence.** libsignal decrypts synchronously against its stores. `decryptAndPersist` therefore loads the session record and any prekeys it needs into an in-memory store, decrypts, and then executes one Room transaction containing: write the advanced session record, delete any consumed one-time prekey, insert the message (idempotent on `message_id`), update the conversation summary, and advance `sync_cursor`. If any step fails the transaction rolls back and the session is unchanged. A replayed envelope is rejected by the ratchet and reported as `Duplicate`. Confirmation is sent to the server **only after commit**.

**Confirmation batching.** `ConfirmationBatcher` coalesces sequence numbers and emits one `delivery.confirm(upToServerSeq)` per window using `debounce`/`conflate`, reducing outbound chatter during bursts from N frames to roughly one per quarter second.

**UI projection.** Room exposes `Flow<List<MessageEntity>>` through paged queries (Paging 3 with a keyset on `(sent_at, message_id)`). The ViewModel maps to `UiState` on `Dispatchers.Default`, then `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`. Compose collects with `collectAsStateWithLifecycle`. The main thread performs no I/O, no cryptography, and no mapping of large lists.

### 4.4 Outbound Path

1. The ViewModel invokes `SendMessageUseCase`. In one transaction it inserts the `message` row (`PENDING`) and the `outbox` row with a null `envelope_blob`. The UI updates immediately from the database.
2. An `OutboundDispatcher` coroutine consumes a `Channel<MessageId>` signalled by the outbox. On `Dispatchers.Default` (within the same per-contact shard rule) it encrypts the padded plaintext, seals it with a valid sender certificate, and in one transaction writes the advanced session record and the finished `envelope_blob`. A crash therefore never loses a ratchet step without its envelope.
3. The dispatcher sends the stored envelope with an anonymous `POST /v1/messages/{to}` using the contact's delivery key. On `201` the message moves to `SENT`, records `serverTs`, and the outbox row is deleted.
4. Failures follow exponential backoff with jitter, persisted in `next_attempt_at`. Retries resend the stored envelope bytes and never re-encrypt. A WorkManager job (`NetworkType.CONNECTED`) guarantees retry when the process is not alive.

### 4.5 Connection Lifecycle and Resilience

| Concern | Strategy |
|---|---|
| Reconnection | Exponential backoff with full jitter, capped at 60 s, reset on a stable connection of 30 s |
| Resume | `sync.resume` with `last_confirmed_seq` from `sync_cursor`; server replays the gap |
| Duplicates | Idempotent insert on `message_id`; unique index on `server_seq` (inbound rows only); replayed envelopes are rejected by the ratchet |
| Sender certificate | Refreshed when fewer than 6 hours remain; a send uses the cached certificate and does not block on a refresh while it is still valid |
| Process death | All durable state is in Room; the pipeline is reconstructible from the cursor and outbox |
| Foreground/background | Socket maintained while foreground; FCM data message wakes a short-lived `ForegroundService` or expedited WorkManager task in background |
| Structured concurrency | The pipeline lives in an application-scoped `CoroutineScope(SupervisorJob() + dispatchers.default)` provided by Hilt; child failures are isolated and reported to a `CoroutineExceptionHandler` |
| Cancellation | Cryptographic and Room operations are cancellation-safe because they are single transactions |

### 4.6 Dispatcher Policy

| Dispatcher | Used for |
|---|---|
| `Main.immediate` | Compose state collection only |
| `Default.limitedParallelism(K)` | Unsealing, decryption, encryption, mapping |
| `IO` | Socket reads/writes, file and attachment I/O |
| Room's own executor | Queries and transactions, via suspend DAOs |

All dispatchers are injected through `DispatcherProvider` to keep the pipeline deterministic under `StandardTestDispatcher` in tests.

---

## 5. Cross-Cutting Concerns

**Testing strategy.**

- `:core:crypto` integration tests run two in-memory parties through the full flow: registration bundle, session establishment, sealed round trip, out-of-order delivery, replay rejection, and identity change. Cryptographic correctness itself is libsignal's responsibility and is covered by its own test suite. Valence's tests verify that it is wired correctly.
- `:core:data` pipeline tests use virtual time to verify ordering, idempotency, and rollback.
- Server tests run in-memory with `testApplication` plus a WebSocket client.
- Contract tests on both `android/` and `server/` read the golden JSON fixtures from `protocol/` and fail if either side cannot parse or reproduce them.

**Architecture enforcement.** Two mechanisms run in CI:

- The Gradle task `checkModuleGraph` verifies the module-level rules of section 2.3 against the actual dependency model of the build.
- Konsist tests verify source-level rules: imports in `..domain..` packages follow rule 7, libsignal is imported only inside `:core:crypto`, no feature imports another feature's `impl` packages, `api` packages import no `impl` packages, ViewModels do not import data-layer packages, use cases reside in `domain` packages and expose a single public `operator fun invoke`, mappers are injected classes, and field injection is limited to Android entry points.

**Observability.** The server emits only aggregate counters (connections, enqueue rate, queue depth, error codes). No identifiers or payload fields are logged. Client logging is compiled out in release builds for all `:core:crypto` and `:core:data` packages.

**Versioning and migration.** Wire bodies carry `v`. The server supports N and N-1. Room schemas are exported and every migration is covered by `MigrationTestHelper`. Session records carry `record_version` for forward-compatible deserialisation.

**Supply chain.** Dependencies are pinned through a version catalog with verification metadata enabled. Release builds are reproducible. libsignal is the highest-risk dependency: it is pinned to an exact version, its native libraries are verified, desktop native libraries are excluded from the APK, and upgrades happen only through a reviewed pull request that includes a reading of its changelog. libsignal is published for Signal's own use and its use outside Signal is not supported upstream. Valence contains that risk by confining it behind the `:core:crypto` interfaces.

**Licensing.** libsignal is licensed under AGPL-3.0. Valence must therefore be distributed under AGPL-3.0 or a licence that is compatible with it for the combined work. The server component that links libsignal falls under AGPL's network-use clause, which is satisfied by publishing its source. See section 6.

---

## 6. Open Decisions

| Decision | Options | Status and recommendation |
|---|---|---|
| Crypto library | libsignal vs. Tink with a custom ratchet vs. Olm/vodozemac | **Decided: libsignal.** See Appendix A. |
| Project licence | AGPL-3.0 vs. other | **Decided: AGPL-3.0.** libsignal is AGPL-3.0, so the combined work must be distributed under a compatible licence. |
| Unseal API split | Pinned libsignal exposes unsealing separately from session decryption, or it does not | Verify when the dependency is added. If not, run K = 1 or add a thin adapter (see 4.3). |
| Kyber prekeys | Last-resort only (v1) vs. one-time plus last-resort | Last-resort only in v1; add one-time Kyber prekeys before any public release. |
| Certificate trust root | Where the pinned trust root comes from and how it rotates | Ship the public key in the app; rotation through an app update. Specify before server work on certificates. |
| Delivery key rotation | How a leaked key is replaced and redistributed | Rotation sends the new key to every contact in an encrypted message. Specify the retry behaviour for contacts that are offline. |
| Multi-device | Per-device sessions (Signal model) vs. single device | Single device in v1 (a fixed device id); schema keyed by `contact_id` extends to `(contact_id, device_id)` |
| Group messaging | Pairwise fan-out vs. sender keys | Out of scope for v1; libsignal provides sender keys for a later version |
| Inbox store | PostgreSQL vs. Redis Streams | PostgreSQL for durability; Redis for fan-out signalling only |
| Attachments | Encrypted blob store with client-held keys | Separate spec; the message plaintext carries only the blob reference and key |
| Registration challenge | How the client obtains the server-issued challenge that `proof` signs in `POST /v1/register` | Specify together with the server work. The registration body is not part of the wire contract until then. |
| Prekey management API | How a client uploads a rotated signed or Kyber prekey, replenishes one-time prekeys, and learns how many remain | JWT-authenticated endpoints under `/v1/prekeys`. Specify together with the server work. Registration (1.3) already defines the initial upload. |
| Unknown frame types | Ignore vs. close the connection when a frame `type` is not recognised | Clients ignore unknown server frame types, and the server answers an unknown client frame type with an `error` frame. Not implemented yet: decoding currently fails on an unknown `type`. |
| Plaintext structure location | `protocol/` vs. a client-only module | Keep the definition and golden fixtures in `protocol/`, and expose it to `:core:data` through a codec interface in `:core:model` that `:core:network` implements, so rule 6 of section 2.3 still holds. |

---

## Appendix A: Decision Record, Crypto Library

**Requirement.** Strong, audited end-to-end encryption with the least change to the existing structure and no Valence-written cryptographic code (principle 2).

**Chosen: libsignal** (Signal's Rust core with Java/Android bindings).

- It is the reference implementation of the protocol Valence was already modelled on, and it provides PQXDH, the Double Ratchet, Sealed Sender, sender certificates, safety numbers, and Kyber prekeys.
- The existing structure absorbs it with little change. `:core:crypto` already hides the library behind interfaces, the schema already stores identity keys, prekeys, and a serialised per-contact session blob, and the signed-prekey registration flow stays as designed.
- The adjustments it requires are all removals of custom design: no custom key-derivation constants, no custom AEAD parameters, no custom ratchet header, and no skipped-key table.

**Costs and risks.**

- AGPL-3.0 licensing (section 5).
- Upstream states that use outside Signal is unsupported, so the API can change between releases. Mitigated by exact pinning and confinement to `:core:crypto`.
- Sealed Sender needs a server-issued certificate chain, which adds one authenticated endpoint and a trust root to manage.

**Rejected.**

- Tink or libsodium with a hand-built ratchet: violates principle 2, and the ratchet and handshake are the parts that are hardest to get right.
- Olm/vodozemac (Apache-2.0): audited and actively used, but it implements Matrix's protocol (a triple Diffie-Hellman handshake without signed-prekey bundles and without Sealed Sender), so the registration flow and sender-anonymity design would change substantially. No standalone Kotlin binding was confirmed. It is the fallback if AGPL-3.0 turns out to be unacceptable, at the cost of redesigning sections 1.2 to 1.6.

---

## Appendix B: Changes from v1.0

- Cryptography delegated to libsignal: removed the custom X3DH formulas, HKDF constants, AES-GCM nonce scheme, and message header. Added Kyber prekeys (PQXDH).
- Sealed Sender redefined as a two-layer envelope with server-issued sender certificates, applied from the first message. The inbound pipeline gained an unseal stage before sharding.
- Sends moved from the authenticated WebSocket to anonymous HTTPS authorised by a delivery key, because a JWT on the send path would identify the sender. `message.send` and `message.ack` are replaced by `POST /v1/messages/{to}`.
- Added `GET /v1/sender-certificate` and delivery-key-authorised prekey fetch.
- Safety numbers use libsignal's fingerprint generator, which fixes the missing canonical ordering of the two identities.
- Repository layout corrected to the monorepo (`protocol/`, `android/`, `server/`, `docs/`). `protocol/` is a standalone build included by both sides. Wire DTOs are defined only there.
- Rule 1 of the dependency rules now allows `impl → other:api`. `AuthState` and `AuthSessionObserver` both live in `auth:api`.
- Domain packages may import `javax.inject.Inject` and nothing else from injection frameworks. Source-level rules are enforced with Konsist alongside the Gradle graph check.
- Schema: `ratchet_session` and `skipped_message_key` replaced by `signal_session`. Added `kyber_prekey`, `delivery_key`, `sender_certificate`, and `contact.delivery_key`. `server_seq` is now set only on inbound rows (the previous design could collide the sender's and the recipient's sequences in one unique index). `outbox.envelope_blob` is nullable until encrypted. The sender mailbox ID is no longer repeated inside the plaintext.
- Fixed the cross-reference from the schema to the atomic-persistence description (4.3, previously cited as 4.4).

## Appendix C: Changes from v1.1

- Dependency rules restated as a per-module allowlist enforced by `checkModuleGraph`: any dependency not listed fails, and a module without a rule fails.
- Rules 1 and 5 merged: only `:app` may depend on an `impl` module.
- Features may not depend on `:core:data`, so they see repository interfaces only and never implementations.
- Core layering made explicit: `crypto`, `database`, and `network` depend only on `model` and `common`, and never on each other or on `data`.
- `:core:model`, `:core:common`, and feature `api` modules are restricted to Kotlin and kotlinx libraries.
- Domain packages may additionally import feature `api` types and their own `domain` package tree.
- Konsist rules added for use cases (domain package, single `operator fun invoke`), injected mappers, and field injection limited to Android entry points.
- Project licence decided: AGPL-3.0.

## Appendix D: Changes from v1.2

- Section 3.1 gains field semantics: timestamp units, the optional `ttlSeconds` with a 30-day default and cap, inclusive `upToServerSeq`, exclusive `afterServerSeq`, and the handling of error codes.
- Prekey ids are specified: client-assigned, persisted per-kind counters, never reused, positive 32-bit integers, separate namespaces per kind, opaque to the server.
- Four open decisions added: registration challenge, prekey management API, unknown frame types, and the location of the plaintext structure.
