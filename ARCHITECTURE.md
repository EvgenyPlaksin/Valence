# Valence — Architecture Specification

| | |
|---|---|
| **Status** | Draft v1.0 |
| **Scope** | System contracts, module boundaries, data design, concurrency model |
| **Out of scope** | Application code, UI design, push-notification vendor integration |
| **Audience** | Maintainers, security reviewers, contributors |

Valence is an open-source, zero-knowledge messaging platform. The client is an offline-first Android application (Kotlin, Jetpack Compose, Dagger Hilt, Room). The server is a stateless-by-design asynchronous relay (Kotlin, Ktor) that routes opaque ciphertext and has no capability to read message content.

---

## 0. Principles and Non-Goals

1. **Zero knowledge by construction.** The server is never given key material that could decrypt content. Privacy does not depend on server-side policy or operator honesty.
2. **Do not invent cryptography.** Valence composes audited primitives (X25519, Ed25519, HKDF-SHA256, AES-256-GCM) in a protocol modelled on Signal's X3DH and Double Ratchet. Implementation must use a vetted library (libsignal or Google Tink plus a reviewed ratchet implementation). Hand-rolled primitives are prohibited.
3. **Offline-first.** The local database is the single source of truth for the UI. The network is a synchronisation channel, never a rendering dependency.
4. **Strict boundaries.** Dependencies point inward. Features never reference each other. Infrastructure is replaceable behind interfaces.
5. **Honest threat model.** Section 1.7 states what the server can and cannot observe. Claims are limited to what the design guarantees.

---

## 1. System Design & Security Protocol

### 1.1 Primitive Selection

| Purpose | Primitive | Rationale |
|---|---|---|
| Key agreement | X25519 (ECDH) | Small keys, constant-time, no parameter pitfalls. Preferred over RSA, which offers no forward secrecy in a static-key setup and requires 3072-bit keys for parity. |
| Identity signatures | Ed25519 | Authenticates signed prekeys and registration. |
| Key derivation | HKDF-SHA256 | Domain-separated derivation of root, chain, and message keys. |
| Content encryption | AES-256-GCM (AEAD) | Confidentiality plus integrity. 96-bit nonce derived per message key, never reused. |
| Local at-rest | SQLCipher (AES-256) with a Keystore-wrapped passphrase | Database file is opaque if the device is imaged. |
| Key wrapping on device | Android Keystore AES-256-GCM key (StrongBox when available) | Private keys never persist in plaintext. |

RSA is explicitly not used. If a deployment mandates it, RSA-OAEP may replace the X25519 step only for the one-shot key transport mode, and the document must be amended with the resulting loss of forward secrecy.

### 1.2 Identity and Key Inventory

Each device holds:

| Key | Type | Lifetime | Published to server |
|---|---|---|---|
| Identity key pair (IK) | Ed25519 for signing, X25519 form for DH | Permanent per install | Public part |
| Signed prekey (SPK) | X25519, signed by IK | Rotated every 7 days | Public part and signature |
| One-time prekeys (OPK) | X25519 | Single use, batch of 100, replenished at threshold 25 | Public parts |
| Ephemeral key (EK) | X25519 | Generated per session initiation | Sent in the first message header |
| Ratchet key pair | X25519 | Rotates on every DH ratchet step | Public part in each message header |

The **mailbox ID** is the only server-visible address. It is a random 128-bit value (`mbx_` plus base32), generated client-side, not derived from phone number, e-mail, or username. Contact discovery maps human identifiers to mailbox IDs out of band (QR code, invite link, or an optional hashed-contact directory that is disabled by default).

### 1.3 Registration Handshake

```
Client                                                     Ktor Server
  | generate IK, SPK, OPK[100]                                  |
  | sign SPK with IK                                            |
  |-- POST /v1/register ---------------------------------------->|
  |   { mailboxId, identityKey, signedPreKey{id,pub,sig},        |
  |     oneTimePreKeys[{id,pub}], proof }                        |
  |                                                              | verify sig(SPK) under IK
  |                                                              | verify proof-of-possession of IK
  |<-- 201 { accessToken(short JWT), refreshToken } -------------|
```

`proof` is an Ed25519 signature over the server-issued registration challenge, binding the mailbox to the identity key. The server stores public keys only.

### 1.4 Session Establishment (X3DH)

Alice initiates to Bob.

1. Alice calls `GET /v1/prekeys/{bobMailboxId}`. The server returns Bob's `IK_B`, `SPK_B`, signature, and atomically **consumes** one `OPK_B` (or none if exhausted).
2. Alice verifies `sig(SPK_B)` under `IK_B`. Abort on failure.
3. Alice generates `EK_A` and computes:

```
DH1 = X25519(IK_A,  SPK_B)
DH2 = X25519(EK_A,  IK_B)
DH3 = X25519(EK_A,  SPK_B)
DH4 = X25519(EK_A,  OPK_B)          // omitted if no OPK was available
SK  = HKDF-SHA256(salt = 0x00*32,
                  ikm  = 0xFF*32 || DH1 || DH2 || DH3 [|| DH4],
                  info = "valence/x3dh/v1", L = 32)
```

4. `SK` seeds the Double Ratchet root key. Alice's first message carries an `x3dh` header (`IK_A`, `EK_A`, the consumed `spkId`, `opkId`). Bob recomputes the same `SK` from his private keys and deletes the consumed OPK.

### 1.5 Message Encryption (Double Ratchet)

Per message:

```
(chainKey', messageKey) = KDF_CK(chainKey)                 // HMAC-SHA256 with constants 0x01 / 0x02
(key[32], nonce[12])    = HKDF-SHA256(messageKey, info = "valence/msg/v1", L = 44)
ciphertext || tag       = AES-256-GCM(key, nonce, plaintext, AAD)
AAD                     = canonical(header) || senderMailboxId || recipientMailboxId
```

- Because key and nonce derive from a one-time message key, **nonce reuse is structurally impossible**.
- The DH ratchet advances whenever a new ratchet public key is received, providing forward secrecy and post-compromise security.
- Skipped message keys are cached (bounded at 1000 per session, TTL 30 days) to tolerate out-of-order delivery.
- Plaintext is a length-padded structure (bucketed to 256 B multiples) containing message type, sender timestamp, body, and attachments metadata. The sender timestamp therefore lives only inside the ciphertext.

### 1.6 Blind-Router Contract

The server is restricted to the following operations on message traffic:

| Server may | Server must never |
|---|---|
| Authenticate the connection, resolve `to` mailbox, enqueue the opaque `payload`, deliver, delete on ack | Parse, decrypt, log, or index `payload` or `header` internals |
| Stamp `serverSeq` and `serverTs` on enqueue | Store sender identity in cleartext alongside the message record |
| Enforce TTL (default 30 days) and per-mailbox quotas | Retain delivered messages after ack |
| Rate-limit by token and mailbox | Hold any private key or session secret |

**Sealed-sender mode.** After the first exchange, the sender mailbox is omitted from the transit envelope and recovered by the recipient from within the decrypted payload. Delivery authorisation uses a 128-bit delivery token derived from the shared session, so the server can authorise a send without learning who sent it.

### 1.7 Threat Model: What the Server Can Observe

"Never stores unencrypted metadata" is only achievable for **content-bearing** metadata. Valence guarantees the following and documents the residue.

| Data | Server visibility |
|---|---|
| Message body, attachments, filenames, MIME types | None |
| Sender timestamp, read/typing state | None (inside ciphertext; receipts are ordinary encrypted messages) |
| Sender identity (sealed-sender mode) | None after first contact |
| Recipient mailbox ID | Visible, required for routing; pseudonymous and random |
| Approximate message size | Visible; mitigated by padding buckets |
| Server arrival time and IP address | Visible; IP is not persisted, arrival time is retained only until ack |
| Public key bundles | Visible by design |

Out of scope for v1: traffic-analysis resistance against a global passive adversary, and protection against a compromised client OS. Contributors must not claim otherwise in documentation.

### 1.8 Key Verification

Users verify a contact by comparing a **safety number**: `SHA-512(IK_A || IK_B)` truncated and rendered as 60 digits or a QR code. A changed identity key raises a blocking trust-change event in the conversation layer rather than silently re-keying.

---

## 2. Android Multi-Module Architecture

### 2.1 Directory Structure

```
valence/
├── app/                                  Composition root only
├── build-logic/                          Convention plugins (Gradle)
│   └── convention/
├── core/
│   ├── model/                            Pure Kotlin domain types
│   ├── common/                           Result types, dispatchers, clock, id generators
│   ├── crypto/                           Key management, X3DH, ratchet, AEAD
│   ├── database/                         Room, DAOs, SQLCipher, migrations
│   ├── network/                          Ktor/OkHttp client, WebSocket transport, DTOs
│   ├── data/                             Repository implementations
│   └── ui-theme/                         Material 3 theme, design tokens, shared composables
├── features/
│   ├── auth/
│   │   ├── api/                          Public contract: navigation entry, AuthState
│   │   └── impl/                         Screens, ViewModels, use cases, Hilt module
│   ├── chat-list/
│   │   ├── api/
│   │   └── impl/
│   └── conversation/
│       ├── api/
│       └── impl/
└── server/                               Ktor backend (separate Gradle project, shares :protocol)
    └── protocol/                         Shared wire contracts (kotlinx.serialization)
```

### 2.2 Core Module Responsibilities

| Module | Responsibility | Exposes | Hides |
|---|---|---|---|
| `:core:model` | Domain entities (`Message`, `Contact`, `Conversation`, `MessageStatus`), value classes (`MailboxId`, `MessageId`) | Immutable data types | Nothing; has zero Android dependencies |
| `:core:common` | `DispatcherProvider`, `Clock`, `IdGenerator`, `AppResult<T>`, error taxonomy | Interfaces | Implementations |
| `:core:crypto` | Key generation, Keystore wrapping, X3DH, ratchet state machine, AEAD, safety numbers | `KeyStore`, `SessionCipher`, `PreKeyProvider`, `SafetyNumberGenerator` interfaces | Library choice (libsignal or Tink), raw key bytes, ratchet internals |
| `:core:database` | Room schema, DAOs, SQLCipher integration, migrations, type converters | DAO interfaces, `ValenceDatabase` | Entity classes are `internal` and mapped at the boundary |
| `:core:network` | HTTP client, WebSocket transport, reconnection, auth interceptor, wire DTOs | `MessageTransport`, `PreKeyApi`, `RegistrationApi` interfaces | OkHttp/Ktor client types, retry policy, TLS pinning |
| `:core:data` | Repository implementations binding crypto, database, and network | `MessageRepository`, `ContactRepository`, `SessionRepository` | Orchestration, inbound pipeline, outbox |
| `:core:ui-theme` | Theme, typography, spacing tokens, reusable stateless composables | `ValenceTheme`, shared components | Nothing business-related |

Repository interfaces are declared in `:core:model`'s companion contract package (`core.model.repository`) so that features and `:core:data` depend on the same abstraction without depending on each other.

### 2.3 Dependency Rules

```
app ──────────► features:*:impl ──► features:*:api
 │                    │                    │
 │                    ▼                    ▼
 └──────────────► core:data ───► core:crypto / database / network
                      │
                      ▼
               core:model ◄── core:common
```

Enforced rules (verified in CI by a dependency-graph check task):

1. `features:X:impl` **must not** depend on `features:Y:impl` or `features:Y:api` for any Y ≠ X.
2. `features:*` **must not** depend on `:core:database`, `:core:network`, or `:core:crypto`. They consume only repository interfaces from `:core:model`.
3. `:core:model` and `:core:common` have no Android or third-party runtime dependencies other than kotlinx.
4. `:app` is the only module that sees every `impl`. It wires them through Hilt.
5. `:api` modules contain only interfaces, navigation route definitions, and immutable value types.

### 2.4 Feature Decoupling

Features communicate through three mechanisms only.

**a) Navigation contracts.** Each feature's `api` module exports a destination and a navigator interface. Other features request navigation without knowing the target implementation.

```kotlin
interface ConversationNavigator {
    fun routeFor(conversationId: ConversationId): NavRoute
}

interface AuthSessionObserver {
    val state: StateFlow<AuthState>
}
```

**b) Shared domain interfaces** in `:core:model` (repositories, `AuthSessionObserver`). A feature receives them through constructor injection and never knows the concrete class.

**c) Event bus via Flow.** Cross-feature notifications that are not state (for example, "identity key changed") are published on a typed `SharedFlow` owned by `:core:data` and exposed through a domain interface.

Hilt binding strategy:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
interface ConversationFeatureModule {
    @Binds fun bindNavigator(impl: ConversationNavigatorImpl): ConversationNavigator
}
```

`chat-list` navigates to a conversation by injecting `ConversationNavigator`. Removing or replacing the `conversation:impl` module requires no change in `chat-list`.

### 2.5 Presentation Layer Within a Feature

Each `impl` module follows the same internal layering: `ui` (Compose screens, stateless), `presentation` (ViewModel with a single immutable `UiState` and a sealed `UiEvent`/`UiAction` pair), `domain` (use cases, one public `operator fun invoke` each). Unidirectional data flow is mandatory.

---

## 3. Data Models & State Contracts

### 3.1 Wire Contract (WebSocket, JSON, `kotlinx.serialization`)

All frames share a typed envelope. Binary fields are Base64URL without padding. Unknown fields are ignored; `v` governs breaking changes.

**Client → Server: `message.send`**

```json
{
  "type": "message.send",
  "v": 1,
  "clientMsgId": "01J9ZQ3M5X7K2R8T4W6Y0B1C2D",
  "to": "mbx_k7q2m9x4t8w3v5n1p6r0",
  "deliveryToken": "wQ3p1...Zk",
  "header": {
    "ratchetKey": "mC9e...Qw",
    "previousChainLength": 4,
    "messageNumber": 17,
    "x3dh": null
  },
  "payload": "u8F2...3sA",
  "ttlSeconds": 2592000
}
```

First message of a session populates `x3dh`:

```json
{
  "x3dh": {
    "identityKey": "Zp0t...Lk",
    "ephemeralKey": "b7Xc...9d",
    "signedPreKeyId": 12,
    "oneTimePreKeyId": 48
  }
}
```

**Server → Client: `message.ack` (enqueue confirmation)**

```json
{
  "type": "message.ack",
  "v": 1,
  "clientMsgId": "01J9ZQ3M5X7K2R8T4W6Y0B1C2D",
  "serverSeq": 884213,
  "serverTs": 1760000000123
}
```

**Server → Client: `message.deliver`**

```json
{
  "type": "message.deliver",
  "v": 1,
  "serverSeq": 884213,
  "serverTs": 1760000000123,
  "from": null,
  "header": {
    "ratchetKey": "mC9e...Qw",
    "previousChainLength": 4,
    "messageNumber": 17,
    "x3dh": null
  },
  "payload": "u8F2...3sA"
}
```

**Client → Server: `delivery.confirm`** (client has durably stored; server may delete)

```json
{
  "type": "delivery.confirm",
  "v": 1,
  "upToServerSeq": 884213
}
```

**Server → Client: `error`**

```json
{
  "type": "error",
  "v": 1,
  "code": "RATE_LIMITED",
  "retryAfterMs": 2000,
  "ref": "01J9ZQ3M5X7K2R8T4W6Y0B1C2D"
}
```

**Decrypted plaintext structure** (never visible to the server):

```json
{
  "kind": "text",
  "messageId": "01J9ZQ3M5X7K2R8T4W6Y0B1C2D",
  "senderMailboxId": "mbx_a1b2c3d4e5f6g7h8i9j0",
  "sentAt": 1760000000000,
  "conversationId": "c_8f2a...",
  "body": "Hello",
  "replyTo": null,
  "attachments": [],
  "padding": "AAAA..."
}
```

`messageId` is a UUIDv7/ULID, giving idempotency and rough time ordering. Receivers deduplicate on `messageId`.

### 3.2 Server Persistence (opaque relay store)

```sql
CREATE TABLE mailbox (
    mailbox_id        TEXT PRIMARY KEY,
    identity_key      BYTEA       NOT NULL,
    signed_prekey_id  INTEGER     NOT NULL,
    signed_prekey     BYTEA       NOT NULL,
    signed_prekey_sig BYTEA       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
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

`envelope` is stored as received. There is no sender column, no plaintext index, and no message-level analytics table.

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

CREATE TABLE one_time_prekey (
    prekey_id         INTEGER PRIMARY KEY,
    public_key        BLOB    NOT NULL,
    wrapped_private   BLOB    NOT NULL,
    wrap_iv           BLOB    NOT NULL,
    uploaded          INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE ratchet_session (
    contact_id        TEXT    PRIMARY KEY REFERENCES contact(contact_id) ON DELETE CASCADE,
    state_blob        BLOB    NOT NULL,
    state_iv          BLOB    NOT NULL,
    state_version     INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL
);

CREATE TABLE skipped_message_key (
    contact_id        TEXT    NOT NULL REFERENCES contact(contact_id) ON DELETE CASCADE,
    ratchet_key       BLOB    NOT NULL,
    message_number    INTEGER NOT NULL,
    wrapped_key       BLOB    NOT NULL,
    wrap_iv           BLOB    NOT NULL,
    expires_at        INTEGER NOT NULL,
    PRIMARY KEY (contact_id, ratchet_key, message_number)
);
```

The ratchet state is serialised and encrypted as a single blob, written in the same transaction as the message it advanced (see 4.4). This guarantees that the ratchet never advances without the corresponding message being persisted, and vice versa.

**Contacts and conversations**

```sql
CREATE TABLE contact (
    contact_id         TEXT PRIMARY KEY,
    mailbox_id         TEXT    NOT NULL UNIQUE,
    display_name        TEXT    NOT NULL,
    identity_key       BLOB    NOT NULL,
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
    envelope_blob      BLOB    NOT NULL,
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
`status`: `0 = PENDING`, `1 = SENT` (server ack), `2 = DELIVERED`, `3 = READ`, `4 = FAILED`.
`kind`: `0 = TEXT`, `1 = ATTACHMENT`, `2 = SYSTEM`.

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

1. **Reader** consumes `incoming`, validates frame size and schema, applies the rate limiter, and dispatches to the router. It never touches the socket write side.
2. **Writer** drains `outbound` and is the sole writer to the socket. This removes write contention and gives natural backpressure: a slow client fills its own channel and affects no other connection.

**Delivery path.**

```
message.send ─► validate ─► InboxStore.append(to, envelope) ─► assign serverSeq ─► message.ack to sender
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
| Frame size | Hard cap 64 KiB for message frames, enforced before deserialisation |
| Rate limiting | Token bucket per mailbox (sustained 20 msg/s, burst 60) |
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
  Frame Router ─────────────► control frames (ack, error, ping)
        │
        ▼
  Sharded Dispatch: hash(conversation key) mod K
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

**Ordered parallelism.** A Double Ratchet session is a strict state machine, so messages from the same contact must be decrypted **sequentially**, while different contacts can proceed in parallel. The pipeline therefore shards by `contactId`: a stable hash selects one of K worker channels (K = `min(cores, 4)`). Within a shard, ordering is preserved; across shards, work is concurrent. A global `Mutex` is deliberately avoided.

```kotlin
class InboundPipeline @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val decryptor: InboundDecryptor,
    private val ledger: MessageLedger,
    private val confirmations: ConfirmationBatcher,
    @InboundShardCount private val shardCount: Int
) {
    private val shards = List(shardCount) { Channel<InboundFrame>(capacity = 128) }

    fun start(scope: CoroutineScope, frames: Flow<InboundFrame>) {
        shards.forEach { channel -> scope.launch(dispatchers.default) { consume(channel) } }
        scope.launch(dispatchers.io) {
            frames
                .buffer(512)
                .collect { frame -> shards[frame.shardKey.mod(shardCount)].send(frame) }
        }
    }

    private suspend fun consume(channel: Channel<InboundFrame>) {
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

**Atomic persistence.** `decryptAndPersist` executes one Room transaction containing: load ratchet state, decrypt, insert message (idempotent on `message_id`), update conversation summary, write advanced ratchet state, and advance `sync_cursor`. If any step fails the transaction rolls back and the ratchet is unchanged. Confirmation is sent to the server **only after commit**.

**Confirmation batching.** `ConfirmationBatcher` coalesces sequence numbers and emits one `delivery.confirm(upToServerSeq)` per window using `debounce`/`conflate`, reducing outbound chatter during bursts from N frames to roughly one per quarter second.

**UI projection.** Room exposes `Flow<List<MessageEntity>>` through paged queries (Paging 3 with a keyset on `(sent_at, message_id)`). The ViewModel maps to `UiState` on `Dispatchers.Default`, then `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`. Compose collects with `collectAsStateWithLifecycle`. The main thread performs no I/O, no cryptography, and no mapping of large lists.

### 4.4 Outbound Path

1. The ViewModel invokes `SendMessageUseCase`. In one transaction it inserts the `message` row (`PENDING`) and the `outbox` row. The UI updates immediately from the database.
2. An `OutboundDispatcher` coroutine consumes a `Channel<MessageId>` signalled by the outbox, encrypts on `Dispatchers.Default` (within the same per-contact shard rule), and sends via `MessageTransport`.
3. On `message.ack`, the message moves to `SENT`, receives `serverSeq`, and the outbox row is deleted.
4. Failures follow exponential backoff with jitter, persisted in `next_attempt_at`. A WorkManager job (`NetworkType.CONNECTED`) guarantees retry when the process is not alive.

### 4.5 Connection Lifecycle and Resilience

| Concern | Strategy |
|---|---|
| Reconnection | Exponential backoff with full jitter, capped at 60 s, reset on a stable connection of 30 s |
| Resume | `sync.resume` with `last_confirmed_seq` from `sync_cursor`; server replays the gap |
| Duplicates | Idempotent insert on `message_id`; unique index on `server_seq` |
| Process death | All durable state is in Room; the pipeline is reconstructible from the cursor and outbox |
| Foreground/background | Socket maintained while foreground; FCM data message wakes a short-lived `ForegroundService` or expedited WorkManager task in background |
| Structured concurrency | The pipeline lives in an application-scoped `CoroutineScope(SupervisorJob() + dispatchers.default)` provided by Hilt; child failures are isolated and reported to a `CoroutineExceptionHandler` |
| Cancellation | Cryptographic and Room operations are cancellation-safe because they are single transactions |

### 4.6 Dispatcher Policy

| Dispatcher | Used for |
|---|---|
| `Main.immediate` | Compose state collection only |
| `Default.limitedParallelism(K)` | Decryption, encryption, mapping |
| `IO` | Socket reads/writes, file and attachment I/O |
| Room's own executor | Queries and transactions, via suspend DAOs |

All dispatchers are injected through `DispatcherProvider` to keep the pipeline deterministic under `StandardTestDispatcher` in tests.

---

## 5. Cross-Cutting Concerns

**Testing strategy.** `:core:crypto` carries known-answer tests for X3DH and ratchet vectors; `:core:data` pipeline tests use virtual time to verify ordering, idempotency, and rollback; server tests run in-memory with `testApplication` plus a WebSocket client; architecture rules are asserted by a Gradle dependency-graph check.

**Observability.** The server emits only aggregate counters (connections, enqueue rate, queue depth, error codes). No identifiers or payload fields are logged. Client logging is compiled out in release builds for all `:core:crypto` and `:core:data` packages.

**Versioning and migration.** Wire frames carry `v`. The server supports N and N-1. Room schemas are exported and every migration is covered by `MigrationTestHelper`. Ratchet state blobs carry `state_version` for forward-compatible deserialisation.

**Supply chain.** Dependencies are pinned through a version catalog with verification metadata enabled. Release builds are reproducible. Cryptographic libraries are upgraded only through a reviewed pull request.

---

## 6. Open Decisions

| Decision | Options | Recommendation |
|---|---|---|
| Crypto library | libsignal vs. Tink with a custom ratchet | libsignal, to avoid maintaining protocol code |
| Multi-device | Per-device sessions (Signal model) vs. single device | Single device in v1; schema keyed by `contact_id` extends to `(contact_id, device_id)` |
| Group messaging | Pairwise fan-out vs. sender keys | Out of scope for v1; sender-keys planned |
| Inbox store | PostgreSQL vs. Redis Streams | PostgreSQL for durability; Redis for fan-out signalling only |
| Attachments | Encrypted blob store with client-held keys | Separate spec; the message plaintext carries only the blob reference and key |
