package com.lumina.reader.ui.components

import com.lumina.reader.core.model.Book

// The mappers from the library's Book entity to the shared component models
// (:sharedUi). They stay in :app until Book itself is shared (stage 5) and the
// library screens move (stage 8b).

fun Book.toCoverModel(): BookCoverModel = BookCoverModel(
    bookId = id,
    key = "book:$id",
    title = title,
    author = author,
    image = coverImageOf(coverPath),
    thicknessDp = bookThicknessDp(fileSizeBytes, format)
)

fun Book.toShelfBookUi(): ShelfBookUi = ShelfBookUi(
    id = id,
    cover = toCoverModel(),
    title = title,
    author = author,
    progress = (currentProgressPercent / 100f).coerceIn(0f, 1f),
    isFavorite = isFavorite,
    isFinished = isDone(),
    isNew = !isDone() && currentProgressPercent <= 0f,
    seriesNumber = seriesOrder.takeIf { seriesName.isNotBlank() && it > 0 }
)
