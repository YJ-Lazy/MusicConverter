package com.musicconverter.miui.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HistoryDao {
    @Insert fun insert(item: HistoryEntity)
    @Query("SELECT * FROM conversion_history ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): List<HistoryEntity>
    @Query("DELETE FROM conversion_history") fun clear()
}
