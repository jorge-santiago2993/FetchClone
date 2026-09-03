package com.fetchclone.core.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.fetchclone.core.data.database.entity.OfferEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the `offers` table.
 *
 * ### `pagingSource()` — the heart of "Room is the single source of truth"
 *
 * Returning [PagingSource]`<Int, OfferEntity>` from a `@Query` (enabled by the
 * `room-paging` artifact) means the `Pager` reads pages **straight out of SQLite**.
 * Room also *invalidates* this `PagingSource` automatically whenever the `offers`
 * table changes, so when the `RemoteMediator` writes a freshly fetched page the list
 * on screen updates itself. The network is never on the UI's critical path.
 *
 * The `Int` key is a row offset — that is what Room's generated
 * `LimitOffsetPagingSource` uses internally; we never construct it ourselves.
 *
 * ### `observeCount()` returns a `Flow`, `upsert`/`clear` are `suspend`
 *
 * Reads that the UI observes are cold `Flow`s (Room emits a new list on every write).
 * One-shot writes are `suspend` so callers stay off the main thread — Room throws if a
 * blocking DB call happens on the main thread, and `suspend` makes the dispatcher
 * boundary explicit.
 */
@Dao
interface OffersDao {

    /**
     * Insert-or-update a page of offers. `@Upsert` (not `@Insert(onConflict = REPLACE)`)
     * so that re-fetching an already-cached product updates it in place without
     * churning the primary key / firing spurious deletions.
     */
    @Upsert
    suspend fun upsertOffers(offers: List<OfferEntity>)

    /** Feed order is defined solely by [OfferEntity.feedPosition]; see that class. */
    @Query("SELECT * FROM offers ORDER BY feedPosition ASC")
    fun pagingSource(): PagingSource<Int, OfferEntity>

    /**
     * Drives the ViewModel's empty-vs-loading decision. A senior point worth making:
     * this is deliberately *not* folded into the paging stream — the paging
     * `LoadState` tells you about network/DB load progress, while "how many offers do
     * we actually have cached" is a separate question that survives when the network
     * is down.
     */
    @Query("SELECT COUNT(*) FROM offers")
    fun observeCount(): Flow<Int>

    /**
     * Cleared at the start of every `LoadType.REFRESH` so the cache mirrors the server.
     *
     * Scoped to this one table: `DELETE FROM offers` cannot touch `receipts`. Worth
     * stating explicitly because the receipt outbox shares this database, and a refresh
     * wiping queued receipts would be catastrophic rather than merely inconvenient.
     */
    @Query("DELETE FROM offers")
    suspend fun clearAll()

    // ---------------------------------------------------------------------------------
    // Non-paging reads, added for the receipt submission pipeline.
    //
    // This deliberately does NOT go through the Paging stack. Paging exists to stream a
    // list into a scrolling UI; the caller below wants a small one-shot set for a
    // background computation, and forcing that through a Pager would mean building
    // paging infrastructure to immediately flatten it.
    //
    // There is deliberately no `offersByIds(...)` lookup for bonus-point matching. An
    // earlier design had one; `ReceiptLineItem` explains why it was removed. The award
    // reads promotional terms snapshotted onto the receipt at capture, so it cannot
    // depend on whether a product happens to be cached when the receipt resolves.
    // ---------------------------------------------------------------------------------

    /**
     * A random sample of cached offers — the stand-in for camera + OCR. `ReceiptScanner`
     * uses it to fabricate the 3-4 line items a real scan would have produced.
     *
     * `ORDER BY RANDOM()` sorts the whole table to take a handful of rows. That is
     * genuinely wasteful in general, and fine here: the offers cache holds tens to low
     * hundreds of rows (one to a few pages of 20), it runs once per button press, and it
     * never blocks the UI. The alternative — `WHERE id >= (random offset)` against a
     * `COUNT(*)` — is faster on a large table but biased when ids are sparse, which they
     * are here after `clearAll()` cycles. Correct and simple beats fast for a simulation
     * of a feature we are not building.
     *
     * Returns fewer rows than requested (including none) when the cache is cold. Callers
     * must handle that — the feed may never have been opened.
     */
    @Query("SELECT * FROM offers ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomOffers(limit: Int): List<OfferEntity>
}
