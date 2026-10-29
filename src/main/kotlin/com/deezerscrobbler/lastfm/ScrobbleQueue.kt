package com.deezerscrobbler.lastfm

import com.deezerscrobbler.config.AppConfigManager
import com.deezerscrobbler.model.PendingScrobble
import com.deezerscrobbler.util.FileLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ScrobbleQueue {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val queueFile = File(AppConfigManager.appDir, "offline_queue.json")
    private val queue = mutableListOf<PendingScrobble>()

    init {
        loadQueue()
    }

    @Synchronized
    fun enqueue(scrobble: PendingScrobble) {
        queue.add(scrobble)
        FileLogger.info("ScrobbleQueue", "Enqueued scrobble: ${scrobble.artist} - ${scrobble.title}. New queue size: ${queue.size}")
        saveQueue()
    }

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun getPendingList(): List<PendingScrobble> = queue.toList()

    @Synchronized
    fun processQueue(lastFmClient: LastFmClient): Int {
        if (queue.isEmpty()) return 0

        FileLogger.info("ScrobbleQueue", "Processing offline queue of size ${queue.size}")
        var successCount = 0
        val iterator = queue.iterator()

        while (iterator.hasNext()) {
            val pending = iterator.next()
            FileLogger.info("ScrobbleQueue", "Attempting offline scrobble: ${pending.artist} - ${pending.title}")
            val result = lastFmClient.scrobble(
                artist = pending.artist,
                track = pending.title,
                timestampSeconds = pending.timestampSeconds,
                album = pending.album
            )

            if (result.isSuccess) {
                iterator.remove()
                successCount++
                FileLogger.info("ScrobbleQueue", "Offline scrobble success: ${pending.artist} - ${pending.title}")
            } else {
                FileLogger.warn("ScrobbleQueue", "Offline scrobble failed for ${pending.artist} - ${pending.title}: ${result.exceptionOrNull()?.message}")
                // Stop on first failure (network down or auth issue)
                break
            }
        }

        if (successCount > 0) {
            saveQueue()
        }

        FileLogger.info("ScrobbleQueue", "Finished processing offline queue. Processed: $successCount, Remaining: ${queue.size}")
        return successCount
    }

    @Synchronized
    fun clear() {
        FileLogger.info("ScrobbleQueue", "Clearing scrobble queue (was ${queue.size})")
        queue.clear()
        saveQueue()
    }

    private fun loadQueue() {
        try {
            if (queueFile.exists()) {
                val list = json.decodeFromString<List<PendingScrobble>>(queueFile.readText())
                queue.clear()
                queue.addAll(list)
                FileLogger.info("ScrobbleQueue", "Loaded ${queue.size} items from offline queue file")
            }
        } catch (e: Exception) {
            FileLogger.error("ScrobbleQueue", "Failed to load queue file", e)
        }
    }

    private fun saveQueue() {
        try {
            queueFile.writeText(json.encodeToString(queue))
        } catch (e: Exception) {
            FileLogger.error("ScrobbleQueue", "Failed to save queue file", e)
        }
    }
}
