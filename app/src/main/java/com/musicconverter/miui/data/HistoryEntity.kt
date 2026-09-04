package com.musicconverter.miui.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inputName: String,
    val outputName: String,
    val operation: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis()
)
