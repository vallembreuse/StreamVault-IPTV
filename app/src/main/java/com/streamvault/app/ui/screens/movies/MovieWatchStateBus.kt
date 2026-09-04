package com.streamvault.app.ui.screens.movies

import com.streamvault.domain.model.Movie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Small in-process bridge used to reflect a watched/unwatched toggle immediately
 * in movie cards while the Movies screen is still alive in the back stack.
 * Persistence remains owned by PlaybackHistoryRepository.
 */
object MovieWatchStateBus {
    data class Key(val providerId: Long, val movieId: Long)

    private val _overrides = MutableStateFlow<Map<Key, Boolean>>(emptyMap())
    val overrides: StateFlow<Map<Key, Boolean>> = _overrides.asStateFlow()

    fun publish(movie: Movie, watched: Boolean) {
        val ids = buildSet {
            add(movie.id)
            movie.selectedVariantId?.let(::add)
            movie.variants.forEach { add(it.rawMovieId) }
        }
        _overrides.update { current ->
            current + ids.associate { id -> Key(movie.providerId, id) to watched }
        }
    }

    fun publish(providerId: Long, movieId: Long, watched: Boolean) {
        _overrides.update { current ->
            current + (Key(providerId, movieId) to watched)
        }
    }

    fun watchedOverride(movie: Movie, values: Map<Key, Boolean>): Boolean? {
        val ids = buildList {
            add(movie.id)
            movie.selectedVariantId?.let(::add)
            movie.variants.forEach { add(it.rawMovieId) }
        }
        return ids.firstNotNullOfOrNull { id -> values[Key(movie.providerId, id)] }
    }
}
