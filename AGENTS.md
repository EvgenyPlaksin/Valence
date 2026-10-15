# AGENTS.md

Instructions for AI coding agents working on Valence. Read this file completely before every task.

## Project

Valence is an open-source, zero-knowledge, end-to-end encrypted messaging platform.

- Android client: Kotlin, Jetpack Compose, Dagger Hilt, Room. Modular, offline-first.
- Backend: Kotlin, Ktor. Asynchronous and lightweight.
- Cryptography: libsignal (PQXDH, Double Ratchet, Sealed Sender), used only through `:core:crypto`.
- CI: GitHub Actions.

The authoritative design is `docs/ARCHITECTURE.md`. Read the sections relevant to your task before writing code. If code and `docs/ARCHITECTURE.md` disagree, `docs/ARCHITECTURE.md` wins. If it is silent or ambiguous on a decision you need, stop and ask instead of improvising. Do not restate its rules in code or documentation; refer to it.

## Repository Map

This is a monorepo of three independent Gradle builds. There is no root Gradle build.

| Path | Contents | Build |
|---|---|---|
| `android/` | Android client: Kotlin, Jetpack Compose, Hilt, Room, multi-module | Own Gradle build and wrapper. Run Gradle from inside `android/`. |
| `server/` | Backend: Kotlin, Ktor | Own Gradle build and wrapper. Run Gradle from inside `server/`. |
| `protocol/` | Shared wire contracts: one class per frame and per request or response body, version constants, golden JSON fixtures | Own Gradle build and wrapper. Included by `android/` and `server/` with `includeBuild("../protocol")`. |
| `docs/` | Documentation, including `docs/ARCHITECTURE.md` | None. |

Rules for working across these areas:

- `android/` and `server/` never import each other's code. They communicate only through the contracts in `protocol/`.
- Wire DTOs are defined only in `protocol/`. Never define a wire DTO in `android/` or `server/`.
- `protocol/` contains no cryptography, no Android code, and no Ktor code.
- Never change `protocol/` as a side effect of a client or server task. A change there affects both sides, so it needs its own task and explicit approval.
- A task normally touches one build only. If a task seems to require changes in more than one, stop and ask.
- Run Gradle from the directory of the build you are working on, for example `cd android` and then `.\gradlew.bat test`.

## Working Mode

- Work on exactly one task at a time. Do not start adjacent work.
- Before writing code, state in a few sentences which sections of `docs/ARCHITECTURE.md` apply and which files you intend to touch.
- Change only what the task requires. Do not refactor, rename, reformat, or reorder code outside the task scope, even if you see improvements. Report such observations at the end instead.
- Do not add, remove, or upgrade dependencies without asking. Never add a dependency whose licence is incompatible with AGPL-3.0.
- Do not change public interfaces of a module without asking.
- Never commit to `main`. Work on the branch provided for the task.
- If requirements are ambiguous, ask one focused question before implementing.

## Code Style

- Do not write comments in code. No inline comments, no KDoc, no TODO markers, unless the task explicitly asks for them. Code must be self-explanatory through naming and structure.
- Match the language already used in the file and module. Kotlin only, unless the task states otherwise.
- All identifiers, class names, and resource IDs are in English.
- Prefer small, single-purpose classes and functions.
- Prefer immutability: `val` over `var`, immutable data classes, read-only collections in public APIs.
- Model absence and failure explicitly with sealed types or a result type. Do not use exceptions for expected control flow.
- No `!!`. No `GlobalScope`. No `runBlocking` outside tests.
- Do not log or print secrets, keys, plaintext message content, or identifiers that could link users.

## Architecture Rules

Follow clean architecture with strict dependency direction. Dependencies point inward only. The rules below summarise `docs/ARCHITECTURE.md` section 2.3, which is authoritative.

| Layer | May depend on | Must not depend on |
|---|---|---|
| Presentation (Compose UI, ViewModels) | Domain | Data, crypto, network, database |
| Domain (`:core:model`, `:core:common`, and the `..domain..` packages of feature `impl` modules) | Kotlin standard library, kotlinx.coroutines, `:core:model` and `:core:common` types | Android, Room, Ktor, Hilt, libsignal |
| Data (`:core:data`, `:core:database`, `:core:network`, `:core:crypto`) | Domain | Presentation |

Rules that follow from this:

- `javax.inject.Inject` is the only injection import allowed in a `..domain..` package, and only on use-case constructors. Nothing else from Hilt, Dagger, or Android may appear there.
- Repository interfaces live in `:core:model` (`core.model.repository`). Implementations live in `:core:data`.
- ViewModels expose one immutable `UiState` and receive events. They contain no business logic. Business logic belongs in use cases.
- Each use case has one public entry point (`operator fun invoke`) and a single responsibility.
- Feature modules are split into `api` and `impl`. A feature's `impl` may depend on another feature's `api` but never on another feature's `impl`. `api` modules depend only on `:core:model` and `:core:common`.
- Features never depend on `:core:database`, `:core:network`, `:core:crypto`, or `protocol`. Only `:app` sees every `impl`.
- libsignal is a dependency of `:core:crypto` only. Its types never cross that module's boundary.
- Only `:core:network` depends on `protocol` on the client, and it maps protocol DTOs to domain types at its boundary.
- Offline-first: the local Room database is the single source of truth for the UI. Network results are written to the database, and the UI observes the database.
- Concurrency uses structured concurrency. Inject dispatchers through `DispatcherProvider`; never hardcode `Dispatchers.IO`, `Dispatchers.Default`, or `Dispatchers.Main` in production classes.

## Mapping and Data Models

- Mappers are injected classes. No static or top-level mapper functions and no companion-object mappers.
- Use one DTO per concrete type and purpose. Do not create shared generic DTOs or catch-all wrapper types.
- Keep separate models for each layer: wire DTOs (in `protocol/`), database entities, domain models, UI state. Never leak a DTO or entity beyond the data layer.

## Dependency Injection

- Hilt for the Android client. Constructor injection only.
- No service locators, no manual singletons, no field injection outside Android entry points.
- Bindings live in modules owned by the layer that provides the implementation.

## Cryptography and Security

This is the highest-risk area of the project. Special rules apply.

- Never implement cryptographic primitives, key agreement, ratcheting, or message encryption. All of it comes from libsignal, which is the chosen and only permitted cryptographic library.
- Never call libsignal outside `:core:crypto` on the client or the `crypto-support` package on the server. The server uses it only to verify signatures and issue sender certificates, never to decrypt.
- Do not invent, modify, or combine protocols. Implement what `docs/ARCHITECTURE.md` specifies, including the anonymous send path, the delivery key, and the two-layer sealed envelope.
- Any task touching key generation, key storage, key exchange, encryption, decryption, signatures, sender certificates, delivery keys, nonces, or randomness requires you to stop first and explain the intended approach in plain language, including why each step is needed. Wait for approval before writing code.
- Never attach a JWT or any account credential to a message send or a prekey fetch.
- Use only cryptographically secure randomness from libsignal or the platform.
- Never hardcode keys, delivery keys, salts, or secrets. Never put real-looking key material in tests.
- Keep key material out of logs, crash reports, analytics, backups, and serialised state.
- Compare secrets and hashes in constant time using the library-provided function.
- If you are unsure whether something is secure, say so explicitly. Do not guess.

## Testing

- Every behavior change comes with tests in the same change.
- Domain and use-case logic: plain JUnit tests with fakes. No mocking frameworks for types you own; write fakes.
- Test names describe behavior, not implementation.
- Tests must be deterministic. Inject clocks and dispatchers; no real time, no real network.
- Wire-format changes are covered by the golden JSON fixtures in `protocol/`, consumed by contract tests on both sides.
- Architecture tests (the Gradle dependency-graph check and the Konsist tests) are part of the build. A failing architecture test means the change violates the rules above and must be fixed in the code, never in the test.
- Do not weaken, delete, or skip an existing test to make a build pass. If a test seems wrong, report it.

## Verification

Run the checks for every build you changed, from inside its directory. Use `.\gradlew.bat` on Windows PowerShell and `./gradlew` on Linux, macOS, and in CI.

Android, from `android/`:

    .\gradlew.bat test
    .\gradlew.bat assembleDebug

Server, from `server/`:

    .\gradlew.bat test
    .\gradlew.bat build

Protocol, from `protocol/`:

    .\gradlew.bat test

Linting and architecture-check tasks are added to these lists when they exist. If a task you are told to run does not exist yet, say so instead of silently skipping it.

## Environment Notes

- Primary development happens on Windows with PowerShell. Do not generate Bash-only commands for local use. Avoid `touch`, multi-argument `mkdir`, `&&` chaining assumptions, and `export`. Use PowerShell equivalents or give the command for both shells.
- CI runs on Linux in GitHub Actions. Scripts committed to the repo must work there.

## Definition of Done

A task is done only when all of the following hold:

1. The change matches the task and `docs/ARCHITECTURE.md`.
2. No unrelated files are modified.
3. No comments were added.
4. Tests for the new behavior exist and pass.
5. The verification commands for every changed build pass.
6. Dependency direction rules are respected.
7. You have provided a short summary: what changed, which files, which decisions you made, and anything you noticed but deliberately left alone.

## When to Stop and Ask

Stop and ask instead of continuing when:

- `docs/ARCHITECTURE.md` does not cover a decision you must make.
- The task requires touching cryptography, key handling, certificates, or authentication.
- The task requires a new dependency, a new module, or a public interface change.
- The task requires a change in `protocol/`, or in more than one build.
- You would need to modify code outside the task scope to make it work.
- A requirement conflicts with these rules.
