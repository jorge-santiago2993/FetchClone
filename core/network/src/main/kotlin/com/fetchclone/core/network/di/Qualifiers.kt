package com.fetchclone.core.network.di

import javax.inject.Qualifier

/**
 * The **unauthenticated** HTTP client: logging only, no `AuthInterceptor`, no
 * `TokenAuthenticator`.
 *
 * Two things are built on it, and both would be broken by the authenticated one:
 *
 * - `AuthApi.login` — there is no token to attach yet, and a 401 from a wrong password is
 *   an answer, not something to retry with a fresh token.
 * - `AuthApi.refresh` — the important one. On the authenticated client, a 401 from the
 *   refresh endpoint would invoke the authenticator, which calls refresh, which 401s. The
 *   `priorResponse` guard stops that loop, but only after it has run; here it cannot start.
 *
 * The unqualified `OkHttpClient` in the graph is the authenticated one, so that every
 * service added later is auth-enabled by default and opting *out* is the deliberate act.
 * The failure mode of the opposite convention — forget the annotation, ship a request with
 * no credentials — is a runtime 401 in a corner of the app; the failure mode of this one is
 * a token attached to a login call, which is harmless.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class BaseClient

/**
 * The `Retrofit` built on [BaseClient], for the auth endpoints only.
 *
 * Note that this and the unqualified `Retrofit` share a base URL and a `Json` instance;
 * the only difference between them is which client sends the bytes.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class AuthRetrofit

/**
 * The hostname the bearer token may be sent to.
 *
 * Injected rather than read from `FetchCloneApi.HOST` inside `AuthInterceptor`, and the
 * reason is a test that failed: against `MockWebServer` the host is `localhost`, so a
 * hardcoded `dummyjson.com` check silently skipped attaching the token and the whole
 * refresh path became unreachable from a test. The interceptor looked correct and was
 * untestable.
 *
 * The general point is worth more than the fix: **a constant that encodes an assumption
 * about the environment is configuration, not a constant.** Hardcoding it does not remove
 * the dependency, it just makes it impossible to vary — including for the one caller that
 * legitimately needs to.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ApiHost
