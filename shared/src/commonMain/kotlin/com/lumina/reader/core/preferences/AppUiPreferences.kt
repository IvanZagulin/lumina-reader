package com.lumina.reader.core.preferences

import com.lumina.reader.ui.transition.OpenAnimation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the library lays out its books. */
enum class LibraryViewMode { SHELVES, BOOKCASE, LIST }

/** Order of books in the flat (non-shelf) library views. */
enum class LibrarySort { RECENT, TITLE, AUTHOR, ADDED, PROGRESS }

/**
 * Small synchronous key-value storage for UI choices that the first frame
 * already needs. Android: the SharedPreferences file [PreferenceFiles.UI];
 * iOS: NSUserDefaults.
 */
interface UiPreferencesStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/**
 * Small UI preferences of the library and the app shell (read synchronously,
 * so the first frame already uses the stored view). One instance per process:
 * the library and the settings sheet observe the same flows. On Android
 * `AppUiPreferences.get(context)` (androidMain) gives it.
 */
class AppUiPreferences(private val store: UiPreferencesStore) {

    private val mutableOpenAnimation = MutableStateFlow(
        parseEnum(store.getString(KEY_OPEN_ANIMATION), OpenAnimation.FULL)
    )
    private val mutableShelfCaptions = MutableStateFlow(store.getBoolean(KEY_SHELF_CAPTIONS, false))
    private val mutableLibraryView = MutableStateFlow(
        parseEnum(store.getString(KEY_LIBRARY_VIEW), LibraryViewMode.SHELVES)
    )
    private val mutableLibrarySort = MutableStateFlow(
        parseEnum(store.getString(KEY_LIBRARY_SORT), LibrarySort.RECENT)
    )

    /** «Анимация открытия книги»: Полная / Быстрая / Выкл. */
    val openAnimation: StateFlow<OpenAnimation> = mutableOpenAnimation.asStateFlow()

    /** «Подписи под книгами» on the shelves (the bookcase always shows captions). */
    val shelfCaptions: StateFlow<Boolean> = mutableShelfCaptions.asStateFlow()

    /** «Вид»: Полки / Шкаф / Список. */
    val libraryView: StateFlow<LibraryViewMode> = mutableLibraryView.asStateFlow()

    /** «Сортировка» of the flat library views. */
    val librarySort: StateFlow<LibrarySort> = mutableLibrarySort.asStateFlow()

    fun setOpenAnimation(value: OpenAnimation) {
        mutableOpenAnimation.value = value
        store.putString(KEY_OPEN_ANIMATION, value.name)
    }

    fun setShelfCaptions(value: Boolean) {
        mutableShelfCaptions.value = value
        store.putBoolean(KEY_SHELF_CAPTIONS, value)
    }

    fun setLibraryView(value: LibraryViewMode) {
        mutableLibraryView.value = value
        store.putString(KEY_LIBRARY_VIEW, value.name)
    }

    fun setLibrarySort(value: LibrarySort) {
        mutableLibrarySort.value = value
        store.putString(KEY_LIBRARY_SORT, value.name)
    }

    companion object {
        // Keys stored on the device; they must not change.
        const val KEY_OPEN_ANIMATION = "open_animation"
        const val KEY_SHELF_CAPTIONS = "shelf_captions"
        const val KEY_LIBRARY_VIEW = "library_view"
        const val KEY_LIBRARY_SORT = "library_sort"

        /** Stored enum names survive renames: unknown values fall back to [default]. */
        inline fun <reified T : Enum<T>> parseEnum(stored: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == stored } ?: default
    }
}
