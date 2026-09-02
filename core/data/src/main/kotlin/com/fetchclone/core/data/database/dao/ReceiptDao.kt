package com.fetchclone.core.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.fetchclone.core.data.database.entity.ReceiptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {

    @Query("SELECT * FROM receipts ORDER BY purchasedAtEpochMillis DESC")
    fun observeReceipts(): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun getReceipt(id: String): ReceiptEntity?

    @Upsert
    suspend fun upsertReceipts(receipts: List<ReceiptEntity>)

    @Query("DELETE FROM receipts")
    suspend fun clear()
}
