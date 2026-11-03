package de.skyslycer.briefkasten

import android.graphics.BitmapFactory
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class SyncEngine(private val app: BriefkastenApp) {
    suspend fun run() = app.operation.withLock {
        app.preferences.status("Checking for letters…")
        var session = app.auth.session()
        var api = DhlApi(session)
        val response = try { api.advices() } catch (_: LoginRequired) {
            session = app.auth.session(forceRefresh = true)
            api = DhlApi(session)
            api.advices()
        }
        when (response.serviceState.contractState) {
            "ACTIVE" -> Unit
            "BLOCKED" -> { app.preferences.status("DHL has blocked Briefankündigung for this account. Check your DHL account."); return@withLock }
            "NONEXISTENT", "DRAFT" -> { app.preferences.status("Activate Briefankündigung in your DHL account first."); return@withLock }
            else -> { app.preferences.status("DHL returned an unknown account state. Please check your account."); return@withLock }
        }
        val dao = app.database.archive()
        val baseline = dao.initialized(session.account) != true
        val candidates = response.candidates()
        var grantReady = false
        var activeResponse = response
        var refreshAttempted = false
        var added = 0
        var failures = 0
        fun sourceKey(candidate: Candidate) = sha256("${session.account}\u0000${candidate.date}\u0000${LetterCrypto.identifier(candidate.advice.imageUrl)}".toByteArray())
        val knownSources = candidates.associate { candidate ->
            sourceKey(candidate) to dao.source(sourceKey(candidate))?.let { dao.find(it.letterId) }
        }
        // Reserve mapped identities first, so a mix of rotated and stable URLs cannot reuse
        // the identity of another identical-looking envelope in the same response.
        val occurrences = mutableMapOf<Pair<String, String>, MutableSet<Int>>()
        knownSources.values.filterNotNull().forEach { letter ->
            occurrences.getOrPut(letter.date to letter.contentHash) { mutableSetOf() }.add(letter.occurrence)
        }
        for (candidate in candidates) {
            try {
                val sourceKey = sourceKey(candidate)
                val known = knownSources[sourceKey]
                if (known != null && app.vault.read(known.fileName) != null) {
                    continue
                }
                if (!grantReady) { api.authorizeImages(activeResponse); grantReady = true }
                val image = try { api.image(candidate.advice.imageUrl, activeResponse.basicAuth) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Renew a stale image session/link once per check, matching by date and position.
                    if (refreshAttempted) throw e
                    refreshAttempted = true
                    activeResponse = api.advices()
                    require(activeResponse.serviceState.contractState == "ACTIVE")
                    api.authorizeImages(activeResponse)
                    val sameDateBefore = candidates.filter { it.date == candidate.date }
                    val position = sameDateBefore.indexOf(candidate)
                    val fresh = activeResponse.candidates().filter { it.date == candidate.date }.getOrNull(position)
                        ?: throw IOException("Letter is no longer available from DHL.")
                    api.image(fresh.advice.imageUrl, activeResponse.basicAuth)
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(image, 0, image.size, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "Invalid decrypted image." }
                val extension = when (options.outMimeType) {
                    "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
                    else -> throw IOException("Unsupported envelope image format.")
                }
                val digest = sha256(image)
                val key = candidate.date to digest
                val used = occurrences.getOrPut(key) { mutableSetOf() }
                val occurrence = known?.occurrence ?: generateSequence(1) { it + 1 }.first { it !in used }
                used.add(occurrence)
                val id = archiveId(session.account, candidate.date, digest, occurrence)
                val existing = dao.find(id)
                val alertsEnabled = app.preferences.flow.first().notifications
                val letter = existing ?: Letter(id, session.account, candidate.date, System.currentTimeMillis(),
                    "$id.$extension", digest, occurrence, !baseline && candidate.current && alertsEnabled)
                app.vault.write(letter.fileName, image)
                app.database.withTransaction {
                    dao.insert(letter)
                    dao.source(Source(sourceKey, id))
                }
                if (existing == null) added++
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failures++ }
        }
        if (failures == 0) dao.account(Account(session.account, true))
        app.reindexGroups()
        val settings = app.preferences.flow.first()
        for (letter in dao.pending(session.account)) {
            val group = try { app.groups.index(letter) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (!settings.notifications || app.notifications.post(letter, settings.pictures, groupName = group?.name)) dao.notified(letter.id)
        }
        if (failures > 0) {
            app.preferences.status("Saved $added images; $failures downloads failed. Saved letters remain available; retry Check now.")
            throw PartialSync()
        }
        app.preferences.status(if (baseline) "Archive ready. Saved $added available images. Future letters will notify you."
            else "Up to date · $added new images saved.", success = true)
    }
}

class PartialSync : IOException()
