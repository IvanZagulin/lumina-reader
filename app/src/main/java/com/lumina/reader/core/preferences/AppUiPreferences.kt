package com.lumina.reader.core.preferences

import android.content.Context
import android.content.SharedPreferences
import com.lumina.reader.ui.transition.OpenAnimation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the library lays out its books. */
enum class LibraryViewMode { SHELVES, BOOKCASE, LIST }

/** Order of books in the flat (non-shelf) library views. */
enum class LibrarySort { RECENT, TITLE, AUTHOR, ADDED, PROGRESS }

/**
 * Small UI preferences of the library and the app shell, kept in the
 * SharedPreferences file `lumina_ui` (read synchronously, so the first frame
 * already uses the stored view). One instance per process: the library and the
 * settings sheet observe the same flows.
 */
class AppUiPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val mutableOpenAnimation = MutableStateFlow(
        parseEnum(prefs.getString(KEY_OPEN_ANIMATION, null), OpenAnimation.FULL)
    )
    private val mutableShelfCaptions = MutableStateFlow(prefs.getBoolean(KEY_SHELF_CAPTIONS, false))
    private val mutableLibraryView = MutableStateFlow(
        parseEnum(prefs.getString(KEY_LIBRARY_VIEW, null), LibraryViewMode.SHELVES)
    )
    private val mutableLibrarySort = MutableStateFlow(
        parseEnum(prefs.getString(KEY_LIBRARY_SORT, null), LibrarySort.RECENT)
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
        prefs.edit().putString(KEY_OPEN_ANIMATION, value.name).apply()
    }

    fun setShelfCaptions(value: Boolean) {
        mutableShelfCaptions.value = value
        prefs.edit().putBoolean(KEY_SHELF_CAPTIONS, value).apply()
    }

    fun setLibraryView(value: LibraryViewMode) {
        mutableLibraryView.value = value
        prefs.edit().putString(KEY_LIBRARY_VIEW, value.name).apply()
    }

    fun setLibrarySort(value: LibrarySort) {
        mutableLibrarySort.value = value
        prefs.edit().putString(KEY_LIBRARY_SORT, value.name).apply()
    }

    companion object {
        private const val FILE_NAME = "lumina_ui"
        private const val KEY_OPEN_ANIMATION = "open_animation"
        private const val KEY_SHELF_CAPTIONS = "shelf_captions"
        private const val KEY_LIBRARY_VIEW = "library_view"
        private const val KEY_LIBRARY_SORT = "library_sort"

        @Volatile
        private var instance: AppUiPreferences? = null

        fun get(context: Context): AppUiPreferences =
            instance ?: synchronized(this) {
                instance ?: AppUiPreferences(context.applicationContext).also { instance = it }
            }

        /** Stored enum names survive renames: unknown values fall back to [default]. */
        internal inline fun <reified T : Enum<T>> parseEnum(stored: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == stored } ?: default
    }
}
