package com.fetchclone.core.network.token

/**
 * A freshly issued credential pair, on its way into [TokenStore].
 *
 * @property accessToken the short-lived bearer sent with every API request.
 * @property refreshToken the long-lived credential that mints new access tokens. **This is
 *   the sensitive one.** An access token buys an attacker a minute; a refresh token buys
 *   them thirty days, which is why only this half is written to disk, and only encrypted.
 * @property accessTokenLifetimeMillis how long [accessToken] is good for, measured from
 *   *now* rather than expressed as an absolute instant — see the note below.
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenLifetimeMillis: Long,
)

/*
 * ## Where the lifetime comes from, and why it is a duration rather than a deadline
 *
 * DummyJSON takes `expiresInMins` on both login and refresh and honours it exactly —
 * verified against the live API: asking for 1 yields a JWT whose `exp - iat` is 60
 * seconds. So the client already knows the lifetime it was granted, and passing it here as
 * a duration lets `TokenStore` turn it into a deadline on the *monotonic* clock. See
 * `ElapsedTime` for why that distinction is the whole point.
 *
 * Two alternatives, both declined:
 *
 * **Decode the JWT's `exp` claim.** The token is a JWT and does carry one. Declined
 * because `exp` is an absolute wall-clock instant, so using it means comparing against
 * `System.currentTimeMillis()` and inheriting every device-clock failure this design is
 * trying to avoid. It is also more code — base64url decode, JSON parse, error handling for
 * a malformed token — to recover a number we already have.
 *
 * **Trust `expiresInMins` blindly without knowing the server honours it.** That is what
 * makes this safe rather than lucky, and it is the part a real integration must check: a
 * server is free to clamp the request (DummyJSON caps it at 43200 minutes) and hand back
 * something shorter than you asked for. The robust general answer is to read the
 * server-stated duration — a standard OAuth2 response returns `expires_in` for exactly
 * this reason — and measure *that* duration with your own monotonic clock. DummyJSON
 * returns no such field, so the requested value is the best available source, and it was
 * verified rather than assumed.
 *
 * The failure mode if this number is ever wrong is mild and self-correcting either way: too
 * long and the token 401s and `TokenAuthenticator` refreshes it; too short and we refresh
 * slightly early. Neither loses data. That is a deliberate property — the reactive path is
 * the safety net under every expiry estimate.
 */
