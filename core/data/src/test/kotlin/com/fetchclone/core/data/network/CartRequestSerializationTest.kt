package com.fetchclone.core.data.network

import com.fetchclone.core.data.network.model.CartProductRequest
import com.fetchclone.core.data.network.model.CartRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the **encoded bytes** of the upload body.
 *
 * ## Why this test exists
 *
 * It is a regression test for a bug that shipped past every other check in this project.
 * `CartRequest.userId` had a Kotlin default (`= 1`), and
 * kotlinx.serialization does not encode default values — `encodeDefaults` is `false` out of
 * the box — so the field never reached the wire. DummyJSON answered
 * `400 {"message":"User id is required"}` and the outbox dutifully classified it as a
 * terminal rejection, exactly as designed. The pipeline was working perfectly on a request
 * that was malformed.
 *
 * Nothing else could have caught it:
 * - The **unit tests** passed, because `FakeCartsApi` receives a `CartRequest` *object*,
 *   which had `userId = 1` on it. The object was always right; only its serialization was
 *   wrong.
 * - The **instrumented tests** passed, because they never touch the network.
 * - The **compiler** saw nothing, because a Kotlin default and a wire-required field are
 *   indistinguishable in source.
 *
 * The gap is a general one worth naming: **a fake typed at the object boundary cannot test
 * serialization.** Every test above substituted the API at the Retrofit-interface level, so
 * the encoder was never exercised. The lesson is not "use fewer fakes" — those tests are
 * the right shape for the logic they cover — it is that the encode step needs its own test,
 * because it is a boundary no object-level fake crosses.
 *
 * ## Why it asserts on a JSON string rather than round-tripping
 *
 * A round trip (`decode(encode(x)) == x`) would have **passed while the bug was live**:
 * the decoder would apply the very same Kotlin default the encoder omitted, and the
 * missing field would restore itself. Only an assertion about the literal text catches a
 * field that is absent — which is exactly what the remote server sees.
 */
class CartRequestSerializationTest {

    /**
     * Deliberately a *default* `Json`, matching `NetworkModule`'s configuration on the
     * setting under test: neither enables `encodeDefaults`. Using a bespoke instance with
     * `encodeDefaults = true` would make this test pass while production still failed.
     */
    private val json = Json

    @Test
    fun `the encoded body always carries userId`() {
        val body = json.encodeToString(
            CartRequest(
                userId = 1,
                products = listOf(CartProductRequest(id = 144, quantity = 4)),
            ),
        )

        // The exact assertion the live 400 was telling us about.
        assertTrue("userId missing from encoded body: $body", body.contains("\"userId\""))
    }

    @Test
    fun `the encoded body matches the shape the endpoint documents`() {
        val body = json.encodeToString(
            CartRequest(
                userId = 1,
                products = listOf(
                    CartProductRequest(id = 144, quantity = 4),
                    CartProductRequest(id = 98, quantity = 1),
                ),
            ),
        )

        // Pinned in full, character for character. A looser assertion (`contains`) would
        // let a renamed or reordered field through, and the endpoint cares about both.
        assertEquals(
            """{"userId":1,"products":[{"id":144,"quantity":4},{"id":98,"quantity":1}]}""",
            body,
        )
    }

    @Test
    fun `the body carries no prices, only what was bought`() {
        val body = json.encodeToString(
            CartRequest(userId = 1, products = listOf(CartProductRequest(id = 7, quantity = 2))),
        )

        // The server knows what things cost. A client that asserted prices would be a
        // client that could be modified to assert cheaper ones — so the request describes
        // *what* was bought and never *what it was worth*. See CartProductRequest.
        assertTrue(body, !body.contains("price"))
        assertTrue(body, !body.contains("Cents"))
        assertTrue(body, !body.contains("discount"))
    }
}
