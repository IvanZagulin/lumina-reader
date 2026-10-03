package com.lumina.reader.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String = "Неизвестный автор",
    val filePath: String,
    val coverPath: String? = null,
    val format: BookFormat = BookFormat.EPUB,
    val currentChapterIndex: Int = 0,
    val currentParagraphIndex: Int = 0,
    /** First visible character of the saved page inside [currentParagraphIndex]. */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val currentCharOffset: Int = 0,
    val currentProgressPercent: Float = 0f,
    val totalChapters: Int = 1,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val fileSizeBytes: Long = 0L,
    val language: String = "ru",
    val description: String = "",
    val isFavorite: Boolean = false,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val collection: String = "Основная",
    val tags: String = "",
    val seriesName: String = "",
    val seriesOrder: Int = 0
) {
    fun isDone(): Boolean = isCompleted || currentProgressPercent >= 99f
}
