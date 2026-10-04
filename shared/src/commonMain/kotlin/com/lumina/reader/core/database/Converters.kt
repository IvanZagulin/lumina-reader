package com.lumina.reader.core.database

import androidx.room.TypeConverter
import com.lumina.reader.core.model.BookFormat

class Converters {
    @TypeConverter
    fun fromBookFormat(format: BookFormat): String {
        return format.name
    }

    @TypeConverter
    fun toBookFormat(value: String): BookFormat {
        return try {
            BookFormat.valueOf(value)
        } catch (e: Exception) {
            BookFormat.EPUB
        }
    }
}
