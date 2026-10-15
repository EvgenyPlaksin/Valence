# AGENTS.md

Instructions for AI coding agents working on Valence. Read this file completely before every task.

## Project

Valence is an open-source, zero-knowledge, end-to-end encrypted messaging platform.

- Android client: Kotlin, Jetpack Compose, Dagger Hilt, Room. Modular, offline-first.
- Backend: Kotlin, Ktor. Asynchronous and lightweight.
- CI: GitHub Actions.

The authoritative design lives in `docs/ARCHITECTURE.md`. Read the sections relevant to your task before writing code. If code and `ARCHITECTURE.md` disagree, `ARCHITECTURE.md` wins. If `ARCHITECTURE.md` is silent or ambiguous on a decision you need, stop and ask instead of improvising.

## Repository Map

This is a monorepo with two independent builds. There is no root Gradle build.

| Path | Contents | Build |
|---|---|---|
| `android/` | Android client: Kotlin, Jetpack Compose, Hilt, Room, multi-module | Own Gradle build and wrapper. Run Gradle commands from inside `android/`. |
| `server/` | Backend: Kotlin, Ktor | Own Gradle build and wrapper. Run Gradle commands from inside `server/`. |
| `protocol/` | Shared contract between client and server: message formats, cryptographic protocol, API definitions, test vectors | Not a build of its own. Treated as the source of truth for both sides. |
| `docs/` | Documentation, including `docs/ARCHITECTURE.md` | None. |

Rules for working across these areas:

- `android/` and `server/` never import each other's code. They communicate only through the contract defined in `protocol/`.
- `protocol/` is the single source of truth for anything both sides must agree on. If client and server behavior disagree, `protocol/` decides which one is wrong.
- Never change `protocol/` as a side effect of a client or server task. A change there affects both sides, so it needs its own task and explicit approval.
- A task normally touches one area only. If a task seems to require changes in more than one, stop and ask.
- Run all Gradle commands from the directory of the build you are working on, for example `cd android` then `.\gradlew.bat test`.

## Working Mode

- Work on exactly one task at a time. Do not start adjacent work.
- Before writing code, state in a few sentences which `ARCHITECTURE.md` sections apply and which files you intend to touch.
- Change only what the task requires. Do not refactor, rename, reformat, or reorder code outside the task scope, even if you see improvements. Report such observations at the end instead.
- Do not add, remove, or upgrade dependencies without asking.
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

Follow clean architecture with strict dependency direction. Dependencies point inward only.

| Layer | May depend on | Must not depend on |
|---|---|---|
| Presentation (Compose UI, ViewModels) | Domain | Data, Android framework types in ViewModel logic |
| Domain (use cases, models, repository interfaces) | Nothing outside the Kotlin standard library and coroutines | Android, Room, Ktor, Hilt, any crypto library implementation |
| Data (repositories, Room, network, crypto adapters) | Domain | Presentation |

Rules that follow from this:

- Domain code is pure Kotlin. It never imports `android.*`, `androidx.*`, Room, Ktor, or Hilt.
- Repository interfaces live in domain. Implementations live in data.
- ViewModels expose immutable UI state and receive events. They contain no business logic. Business logic belongs in use cases.
- Each use case has one public entry point and a single responsibility.
- Feature modules never depend on other feature modules. Shared code goes through a core or domain module.
- Offline-first: the local Room database is the single source of truth for the UI. Network results are written to the database, and the UI observes the database.
- Concurrency uses structured concurrency. Inject dispatchers; never hardcode `Dispatchers.IO` or `Dispatchers.Default` in production classes.

## Mapping and Data Models

- Mappers are injected classes. No static or top-level mapper functions and no companion-object mappers.
- Use one DTO per concrete type and purpose. Do not create shared generic DTOs or catch-all wrapper types.
- Keep separate models for each layer: network DTOs, database entities, domain models, UI state. Never leak a DTO or entity beyond the data layer.

## Dependency Injection

- Hilt for the Android client. Constructor injection only.
- No service locators, no manual singletons, no field injection outside Android entry points.
- Bindings live in modules owned by the layer that provides the implementation.

## Cryptography and Security

This is the highest-risk area of the project. Special rules apply.

- Never implement cryptographic primitives. Use audited libraries only (for example libsodium bindings or Tink), and only the ones already chosen in `ARCHITECTURE.md`.
- Do not invent, modify, or combine protocols. Implement exactly what `ARCHITECTURE.md` specifies.
- Any task touching key generation, key storage, key exchange, encryption, decryption, signatures, nonces, or randomness requires you to stop first and explain the intended approach in plain language, including why each step is needed. Wait for approval before writing code.
- Use only cryptographically secure randomness from the chosen library or the platform.
- Never hardcode keys, nonces, salts, or secrets. Never put them in tests as real values that could be mistaken for production material.
- Keep key material out of logs, crash reports, analytics, backups, and serialized state.
- Compare secrets and MACs in constant time using the library-provided function.
- If you are unsure whether something is secure, say so explicitly. Do not guess.

## Testing

- Every behavior change comes with tests in the same change.
- Domain and use-case logic: plain JUnit tests with fakes. No mocking frameworks for types you own; write fakes.
- Test names describe behavior, not implementation.
- Tests must be deterministic. Inject clocks and dispatchers; no real time, no real network.
- Crypto-related code additionally requires tests against known-answer vectors where the library provides them.
- Do not weaken, delete, or skip an existing test to make a build pass. If a test seems wrong, report it.

If any of these tasks do not exist in the build yet, say so instead of silently skipping them. Architecture tests are part of `test`; a failing architecture test means the change violates the rules above and must be fixed in the code, not in the test.

## Verification

Run the checks for the build you changed, from inside its directory. Use `.\gradlew.bat` on Windows PowerShell and `./gradlew` on Linux, macOS, and in CI.

Android, from `android/`:

    .\gradlew.bat test
    .\gradlew.bat assembleDebug

Server, from `server/`:

    .\gradlew.bat test
    .\gradlew.bat build

## Environment Notes

- Primary development happens on Windows with PowerShell. Do not generate Bash-only commands for local use. Avoid `touch`, multi-argument `mkdir`, `&&` chaining assumptions, and `export`. Use PowerShell equivalents or give the command for both shells.
- CI runs on Linux in GitHub Actions. Scripts committed to the repo must work there.

## Definition of Done

A task is done only when all of the following hold:

1. The change matches the task and `ARCHITECTURE.md`.
2. No unrelated files are modified.
3. No comments were added.
4. Tests for the new behavior exist and pass.
5. Linting, tests, and the debug build pass.
6. Dependency direction rules are respected.
7. You have provided a short summary: what changed, which files, which decisions you made, and anything you noticed but deliberately left alone.

## When to Stop and Ask

Stop and ask instead of continuing when:

- `ARCHITECTURE.md` does not cover a decision you must make.
- The task requires touching cryptography, key handling, or authentication.
- The task requires a new dependency, a new module, or a public interface change.
- You would need to modify code outside the task scope to make it work.
- A requirement conflicts with these rules.
