package com.fetchclone.core.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.fetchclone.core.data.database.entity.OfferRemoteKeyEntity

/**
 * Room access for the `offer_remote_keys` table used by `OffersRemoteMediator`.
 *
 * Kept as its own DAO (rather than extra methods on [OffersDao]) so the paging-key
 * bookkeeping is visibly separate from the domain data — they have different
 * lifecycles and it makes the mediator's transaction easy to read.
 */
@Dao
interface OfferRemoteKeysDao {

    @Upsert
    suspend fun upsertAll(keys: List<OfferRemoteKeyEntity>)

    /**
     * Look up the keys for a specific offer. The mediator calls this with the **last**
     * currently-loaded offer to find the `nextKey` for an `APPEND`.
     */
    @Query("SELECT * FROM offer_remote_keys WHERE offerId = :offerId")
    suspend fun remoteKeyByOfferId(offerId: Int): OfferRemoteKeyEntity?

    @Query("DELETE FROM offer_remote_keys")
    suspend fun clearAll()
}
