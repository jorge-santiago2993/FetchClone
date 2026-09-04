# CLAUDE.md

Android app. Kotlin 2.x, Jetpack Compose (Material 3), MVVM with Hilt, Coroutines
and Flow, Retrofit with kotlinx.serialization, Room, multi-module.

---

## Concurrency

- Inject `CoroutineDispatcher`. Never hardcode `Dispatchers.IO` or `Dispatchers.Main`.
- Never use `GlobalScope`. Use `viewModelScope`, or an injected application scope for
  work that must outlive the screen.
- Rethrow `CancellationException` before catching generic exceptions.
- Every long-running operation has a named owner. If you cannot say what cancels it,
  it does not get written.
- `withContext` for switching dispatchers, not `launch` inside a suspend function.

## State and UI

- UI state is a sealed interface. Never a data class of nullable fields and booleans.
- Impossible states must be unrepresentable. If two states can be true at once, remodel.
- `StateFlow` for state, never `LiveData`.
- `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)` for
  cold-to-hot conversion.
- `SharedFlow` or a channel only for genuine one-shot events, not for state.
- No business logic in composables. Composables render state and emit events.
- `SavedStateHandle` for anything the user typed or selected that must survive
  process death.

## Compose

- `collectAsStateWithLifecycle()`, never `collectAsState()`.
- `LazyColumn` and `LazyRow` items always take a stable `key`.
- Parameters must be stable. Use `ImmutableList` from kotlinx.collections.immutable,
  or annotate with `@Immutable`, rather than passing raw `List`.
- Do not allocate lambdas inline inside lazy item scopes. Hoist them or use method
  references.
- `rememberSaveable` for anything that must survive configuration change.
  `remember` only for genuinely ephemeral state.
- `LaunchedEffect` takes a meaningful key. `LaunchedEffect(Unit)` requires justification.
- Prefer lambda-based modifiers (`offset { }`, `drawBehind { }`) when the value changes
  frequently, to defer the read out of composition.
- Every interactive element has a content description or is explicitly marked decorative.

## Data and persistence

- Room is the single source of truth. The UI observes the database; the network writes
  to it. The UI never reads the network directly.
- Never `fallbackToDestructiveMigration()`. Write explicit migrations.
- DAOs return `Flow` for observed data.
- Repositories map Data Transfer Objects to domain models. DTOs never reach the UI.
- Money and points are `Int` in minor units. Never `Double` or `Float`.
- Retry and backoff state is persisted in the database, never held in memory.

## Networking

- Configure explicit connect, read, and write timeouts on OkHttp.
- Failure classification: no connectivity is retryable and does not consume an attempt;
  4xx is terminal and never retried (401 excepted, which refreshes once); 5xx and IO
  failures are retryable with backoff.
- Exponential backoff always includes jitter.
- Only idempotent operations are retried. Mutations carry a client-generated
  idempotency key.
- Auth lives in `:core:network`. Feature modules do not know tokens exist.
- Token attach happens in an `Interceptor`; 401 refresh happens in an `Authenticator`.
- Refresh is single-flight: a `Mutex` with a re-check inside the lock.
- `ignoreUnknownKeys = true` on the serializer. A new backend field must not crash the app.

## Logging and telemetry

- Timber. `DebugTree` planted in debug builds only.
- Never log tokens, credentials, personally identifiable information, receipt contents,
  or full response bodies.
- Production telemetry is structured events with properties, never formatted strings.
- Instrument the success path, not only failures. Silent failures are the dangerous ones.

## Testing

- JUnit 4. `runTest` with an injected `TestDispatcher`. Never `runBlockingTest` or
  `TestCoroutineDispatcher.pauseDispatcher`, both deprecated.
- Turbine for Flow emissions.
- Fakes over mocks. Assert observable behavior, never that a method was called.
- Test the unhappy paths: each failure category, each boundary from both sides,
  cancellation.
- Do not test Retrofit, Room, or Paging themselves.

## Hygiene

- No `!!`.
- No `Context` in a ViewModel.
- User-facing strings live in resources.
- Delete generated scaffolding you did not use.

---

## How to work with me

- **I make the architecture decisions.** Implement what I specify. If you think the
  approach is wrong, say so before writing code, not after.
- Show me the plan before writing anything over roughly 50 lines.
- Small steps. One concern at a time.
- Flag anything version-sensitive you are unsure about rather than guessing.
- No summaries or commentary unless I ask.

## Deliberately not in this file

Architecture is decided per problem, not encoded here: source of truth, module
boundaries, whether a mutation needs an outbox, sync and conflict strategy, and
what belongs in the screen versus the app. Those are mine to make and to explain.