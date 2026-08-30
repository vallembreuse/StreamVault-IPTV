from pathlib import Path


def replace_once(text: str, old: str, new: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence, found {count}: {old[:100]!r}")
    return text.replace(old, new, 1)


# Movie detail publishes the successful watched/unwatched change immediately.
p = Path("app/src/main/java/com/streamvault/app/ui/screens/movies/MovieDetailViewModel.kt")
text = p.read_text()
text = replace_once(
    text,
    """            if (result is Result.Success) {
                val updatedProgress = if (currentlyWatched) 0L else totalDurationMs
""",
    """            if (result is Result.Success) {
                MovieWatchStateBus.publish(movie, !currentlyWatched)
                val updatedProgress = if (currentlyWatched) 0L else totalDurationMs
""",
)
p.write_text(text)


# Movie cards observe the in-process watched override while the Movies screen remains
# on the navigation back stack. This avoids requiring a Films -> Series -> Films reload.
p = Path("app/src/main/java/com/streamvault/app/ui/components/shell/AppMediaCards.kt")
text = p.read_text()
text = replace_once(
    text,
    "import com.streamvault.app.R\n",
    "import com.streamvault.app.R\nimport com.streamvault.app.ui.screens.movies.MovieWatchStateBus\n",
)
text = replace_once(
    text,
    """fun MoviePosterCard(movie: Movie, modifier: Modifier = Modifier) {
    val durationMs = movie.durationSeconds.toLong() * 1000L
    val isWatched = durationMs > 0L && isPlaybackComplete(movie.watchProgress, durationMs)
    val progress = if (movie.watchProgress > 5000L && durationMs > 0L && !isWatched) {
""",
    """fun MoviePosterCard(movie: Movie, modifier: Modifier = Modifier) {
    val watchedOverrides by MovieWatchStateBus.overrides.collectAsStateWithLifecycle()
    val durationMs = movie.durationSeconds.toLong() * 1000L
    val persistedWatched = durationMs > 0L && isPlaybackComplete(movie.watchProgress, durationMs)
    val isWatched = MovieWatchStateBus.watchedOverride(movie, watchedOverrides) ?: persistedWatched
    val progress = if (movie.watchProgress > 5000L && durationMs > 0L && !isWatched) {
""",
)
p.write_text(text)
