from pathlib import Path


def replace_exact(text: str, old: str, new: str, expected: int = 1) -> str:
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"Expected {expected} occurrence(s), found {count}: {old[:80]!r}")
    return text.replace(old, new, expected if expected == 1 else -1)


# Move the movie watched badge to the top-right.
p = Path("app/src/main/java/com/streamvault/app/ui/components/shell/AppMediaCards.kt")
text = p.read_text()
text = replace_exact(
    text,
    """modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)""",
    """modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp, top = 8.dp)""",
)
p.write_text(text)


# Add watched/unwatched state and persistence to the movie detail view model.
p = Path("app/src/main/java/com/streamvault/app/ui/screens/movies/MovieDetailViewModel.kt")
text = p.read_text()
text = replace_exact(
    text,
    "import com.streamvault.domain.model.Movie\nimport com.streamvault.domain.model.Result\n",
    "import com.streamvault.domain.model.Movie\nimport com.streamvault.domain.model.PlaybackHistory\nimport com.streamvault.domain.model.PlaybackWatchedStatus\nimport com.streamvault.domain.model.Result\n",
)

anchor = """    fun toggleFavorite() {
        val movie = _uiState.value.movie ?: return
        viewModelScope.launch {
            val newState = !movie.isFavorite
            if (newState) {
                favoriteRepository.addFavorite(movie.providerId, movie.id, ContentType.MOVIE)
            } else {
                favoriteRepository.removeFavorite(movie.providerId, movie.id, ContentType.MOVIE)
            }
            _uiState.update { it.copy(movie = movie.copy(isFavorite = newState)) }
        }
    }
"""
addition = anchor + """
    fun toggleWatched() {
        val movie = _uiState.value.movie ?: return
        viewModelScope.launch {
            val currentlyWatched = _uiState.value.isWatched
            val totalDurationMs = movie.durationSeconds.takeIf { it > 0 }?.times(1000L) ?: 0L
            val result = if (currentlyWatched) {
                playbackHistoryRepository.removeFromHistory(
                    contentId = movie.id,
                    contentType = ContentType.MOVIE,
                    providerId = movie.providerId
                )
            } else {
                val existing = playbackHistoryRepository.getPlaybackHistory(
                    contentId = movie.id,
                    contentType = ContentType.MOVIE,
                    providerId = movie.providerId
                )
                val history = existing ?: PlaybackHistory(
                    contentId = movie.id,
                    contentType = ContentType.MOVIE,
                    providerId = movie.providerId,
                    title = movie.name,
                    posterUrl = movie.posterUrl,
                    streamUrl = movie.streamUrl,
                    resumePositionMs = movie.watchProgress,
                    totalDurationMs = totalDurationMs,
                    lastWatchedAt = System.currentTimeMillis()
                )
                playbackHistoryRepository.markAsWatched(history)
            }

            if (result is Result.Success) {
                val updatedProgress = if (currentlyWatched) 0L else totalDurationMs
                _uiState.update { state ->
                    state.copy(
                        movie = state.movie?.copy(watchProgress = updatedProgress),
                        isWatched = !currentlyWatched,
                        hasResume = false,
                        resumePositionMs = 0L
                    )
                }
            }
        }
    }
"""
text = replace_exact(text, anchor, addition)
text = replace_exact(
    text,
    """        val resumePositionMs = playbackHistory?.resumePositionMs ?: movie.watchProgress
        val hasResume = resumePositionMs > 5000L && !isPlaybackComplete(
            progressMs = resumePositionMs,
            totalDurationMs = playbackHistory?.totalDurationMs?.takeIf { it > 0L } ?: movieDurationMs
        )
""",
    """        val resumePositionMs = playbackHistory?.resumePositionMs ?: movie.watchProgress
        val resolvedDurationMs = playbackHistory?.totalDurationMs?.takeIf { it > 0L } ?: movieDurationMs
        val isWatched = playbackHistory?.watchedStatus == PlaybackWatchedStatus.COMPLETED_MANUAL ||
            playbackHistory?.watchedStatus == PlaybackWatchedStatus.COMPLETED_AUTO ||
            isPlaybackComplete(progressMs = resumePositionMs, totalDurationMs = resolvedDurationMs)
        val hasResume = !isWatched && resumePositionMs > 5000L && !isPlaybackComplete(
            progressMs = resumePositionMs,
            totalDurationMs = resolvedDurationMs
        )
""",
)
text = replace_exact(
    text,
    """                hasResume = hasResume,
                resumePositionMs = if (hasResume) resumePositionMs else 0L
""",
    """                hasResume = hasResume,
                resumePositionMs = if (hasResume) resumePositionMs else 0L,
                isWatched = isWatched
""",
)
text = replace_exact(
    text,
    """    val hasResume: Boolean = false,
    val resumePositionMs: Long = 0L,
    val isCasting: Boolean = false,
""",
    """    val hasResume: Boolean = false,
    val resumePositionMs: Long = 0L,
    val isWatched: Boolean = false,
    val isCasting: Boolean = false,
""",
)
p.write_text(text)


# Wire the action into the movie detail screen.
p = Path("app/src/main/java/com/streamvault/app/ui/screens/movies/MovieDetailScreen.kt")
text = p.read_text()
text = replace_exact(
    text,
    """                hasResume = uiState.hasResume,
                resumePositionMs = uiState.resumePositionMs,
                isCasting = uiState.isCasting,
""",
    """                hasResume = uiState.hasResume,
                resumePositionMs = uiState.resumePositionMs,
                isWatched = uiState.isWatched,
                isCasting = uiState.isCasting,
""",
)
text = replace_exact(
    text,
    """                onCast = viewModel::castMovie,
                onToggleFavorite = viewModel::toggleFavorite,
                onSelectVariant = viewModel::selectMovieVariant,
""",
    """                onCast = viewModel::castMovie,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleWatched = viewModel::toggleWatched,
                onSelectVariant = viewModel::selectMovieVariant,
""",
)
text = replace_exact(
    text,
    """    hasResume: Boolean,
    resumePositionMs: Long,
    isCasting: Boolean,
""",
    """    hasResume: Boolean,
    resumePositionMs: Long,
    isWatched: Boolean,
    isCasting: Boolean,
""",
)
text = replace_exact(
    text,
    """    onCast: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectVariant: (Long) -> Unit,
""",
    """    onCast: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onSelectVariant: (Long) -> Unit,
""",
)
text = replace_exact(
    text,
    """                            hasResume = hasResume,
                            resumePositionMs = resumePositionMs,
                            isCasting = isCasting,
""",
    """                            hasResume = hasResume,
                            resumePositionMs = resumePositionMs,
                            isWatched = isWatched,
                            isCasting = isCasting,
""",
    expected=2,
)
text = replace_exact(
    text,
    """                            onCast = onCast,
                            onToggleFavorite = onToggleFavorite,
                            onSelectVariant = onSelectVariant,
""",
    """                            onCast = onCast,
                            onToggleFavorite = onToggleFavorite,
                            onToggleWatched = onToggleWatched,
                            onSelectVariant = onSelectVariant,
""",
    expected=2,
)
text = replace_exact(
    text,
    """    hasResume: Boolean,
    resumePositionMs: Long,
    isCasting: Boolean,
    externalRatings: ExternalRatings,
""",
    """    hasResume: Boolean,
    resumePositionMs: Long,
    isWatched: Boolean,
    isCasting: Boolean,
    externalRatings: ExternalRatings,
""",
)
text = replace_exact(
    text,
    """    onCast: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectVariant: (Long) -> Unit,
    playButtonFocusRequester: FocusRequester,
""",
    """    onCast: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onSelectVariant: (Long) -> Unit,
    playButtonFocusRequester: FocusRequester,
""",
)
text = replace_exact(
    text,
    """            if (hasTrailer) {
                TvButton(
                    onClick = onPlayTrailer,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.SurfaceEmphasis,
                        contentColor = AppColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.movie_detail_trailer))
                }
            }
            TvIconButton(
""",
    """            if (hasTrailer) {
                TvButton(
                    onClick = onPlayTrailer,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.SurfaceEmphasis,
                        contentColor = AppColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.movie_detail_trailer))
                }
            }
            TvButton(
                onClick = onToggleWatched,
                colors = ButtonDefaults.colors(
                    containerColor = if (isWatched) AppColors.Brand else AppColors.SurfaceEmphasis,
                    contentColor = if (isWatched) Color.White else AppColors.TextPrimary
                )
            ) {
                Text(if (isWatched) "↶ Non vu" else "✓ Vu")
            }
            TvIconButton(
""",
)
p.write_text(text)
