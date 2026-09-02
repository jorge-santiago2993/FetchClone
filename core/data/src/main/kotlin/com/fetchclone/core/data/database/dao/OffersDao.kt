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

    /** Cleared at the start of every `LoadType.REFRESH` so the cache mirrors the server. */
    @Query("DELETE FROM offers")
    suspend fun clearAll()
}
