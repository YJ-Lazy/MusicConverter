package com.musicconverter.miui.data

import android.content.Context

class HistoryRepository(context: Context) {
    private val dao = AppDatabase.get(context).historyDao()
    fun record(input: String, output: String, operation: String, status: String) {
        dao.insert(HistoryEntity(inputName = input, outputName = output, operation = operation, status = status))
    }
    fun recent(limit: Int = 50): List<HistoryEntity> = dao.recent(limit)
}
