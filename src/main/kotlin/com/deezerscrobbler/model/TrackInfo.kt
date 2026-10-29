package com.deezerscrobbler.model

import kotlinx.serialization.Serializable

@Serializable
data class TrackInfo(
    val artist: String,
    val title: String,
    val album: String? = null,
    val durationSeconds: Int = 180 // Default estimated 3 minutes if unknown
) {
    val displayTitle: String get() = "$artist - $title"

    override fun toString(): String = displayTitle
}

enum class PlaybackState {
    PLAYING,
    PAUSED,
    IDLE,
    DEEZER_NOT_RUNNING
}

@Serializable
data class PendingScrobble(
    val artist: String,
    val title: String,
    val album: String? = null,
    val timestampSeconds: Long
)

enum class ScrobbleLogType {
    NOW_PLAYING,
    SCROBBLED,
    QUEUED_OFFLINE,
    ERROR,
    INFO
}

data class ScrobbleLogEntry(
    val timestamp: String,
    val message: String,
    val type: ScrobbleLogType
)
