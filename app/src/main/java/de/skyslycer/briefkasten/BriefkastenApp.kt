package de.skyslycer.briefkasten

import android.app.Application
import androidx.room.Room
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

class BriefkastenApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val vault by lazy { Vault(this) }
    val cutouts by lazy { CutoutImages(vault) }
    val preferences by lazy { Preferences(this) }
    val auth by lazy { Auth(this, vault) }
    val database by lazy { Room.databaseBuilder(this, ArchiveDatabase::class.java, "archive.db").addMigrations(GROUP_MIGRATION).build() }
    val notifications by lazy { LetterNotifications(this, cutouts) }
    val operation = Mutex()
    val groups by lazy { GroupIndexer(this) }
    val sync by lazy { SyncEngine(this) }

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
        scope.launch { schedule() }
        reindexGroups()
    }

    suspend fun schedule() {
        val settings = preferences.flow.first()
        val manager = WorkManager.getInstance(this)
        if (!settings.polling || !auth.signedIn()) {
            manager.cancelUniqueWork("poll")
            return
        }
        val work = PeriodicWorkRequestBuilder<PollWorker>(settings.interval, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork("poll", ExistingPeriodicWorkPolicy.UPDATE, work)
    }
    fun reindexGroups() {
        WorkManager.getInstance(this).enqueueUniqueWork("group-index", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<GroupIndexWorker>().build())
    }
    fun checkNow() {
        val work = OneTimeWorkRequestBuilder<PollWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniqueWork("manual-check", ExistingWorkPolicy.KEEP, work)
    }
}

class PollWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BriefkastenApp
        if (!app.auth.signedIn()) return Result.success()
        return try {
            app.sync.run()
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: LoginRequired) {
            app.preferences.status("Sign in again to resume downloads. Your archive is safe.")
            Result.failure()
        } catch (e: Exception) {
            app.preferences.status(friendlyError(e))
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

fun friendlyError(e: Exception): String = when (e) {
    is EmptyExport -> e.message!!
    is PartialSync -> "Some envelope downloads failed. The images already saved are safe; the next check will retry the missing downloads."
    is LoginRequired -> e.message!!
    is ApiFailure -> e.message!!
    is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "Could not reach DHL. Will try again when connected."
    is java.security.GeneralSecurityException -> "Could not decrypt an envelope. The next check will request fresh image links."
    else -> "The check could not finish. Check your connection, DHL activation and sign-in, then try again."
}
