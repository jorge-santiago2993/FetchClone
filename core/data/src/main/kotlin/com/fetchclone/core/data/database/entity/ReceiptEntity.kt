package com.fetchclone.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A scanned receipt, backing the receipt-history screen. */
@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val merchant: String,
    val totalCents: Long,
    val pointsEarned: Int,
    val purchasedAtEpochMillis: Long,
)
