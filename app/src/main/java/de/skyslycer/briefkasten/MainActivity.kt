package de.skyslycer.briefkasten

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthorizationException
import java.util.Date
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : FragmentActivity() {
    private val app get() = application as BriefkastenApp
    private var unlocked by mutableStateOf(false)
    private var selected by mutableStateOf<String?>(null)
    private var showSettings by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var working by mutableStateOf(false)
    private var signedIn by mutableStateOf(false)
    private var notificationsEnabled by mutableStateOf(false)
    private var notificationSettingsRequired by mutableStateOf(false)
    private var lockEnabled = true
    private var prompt: BiometricPrompt? = null
    private var promptActive = false
    private var foregroundSession by mutableIntStateOf(0)

    private var pendingExport: PendingExport? = null
    private val saveDocuments = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val export = pendingExport
        pendingExport = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null && export != null) {
            val save = {
                action {
                    withContext(Dispatchers.IO) { saveExport(contentResolver, export, uri) }
                    message = if (export.files.size == 1) "Saved ${export.files.single().name}." else "Saved ${export.files.size} PDF files."
                }
            }
            if (lockEnabled && !unlocked) unlock(onSuccess = save) else save()
        }
    }

    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val restore = { action {
                val added = BackupImport(app).restore(uri)
                app.schedule()
                message = "Backup imported. $added ${if (added == 1) "envelope" else "envelopes"} added."
            } }
            if (lockEnabled && !unlocked) unlock(onSuccess = restore) else restore()
        }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshNotificationState()
    }
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (data != null) action {
            app.operation.withLock { app.auth.finishLogin(data) }
            signedIn = app.auth.signedIn()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotifications(fromButton = false)
            }
            app.schedule()
            app.checkNow()
            message = "Signed in. Your first check will save the available history quietly."
        } else message = "Login cancelled."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingPaths = savedInstanceState?.getStringArrayList("pending_export_files")
        val pendingMime = savedInstanceState?.getString("pending_export_mime")
        if (!pendingPaths.isNullOrEmpty() && pendingMime != null) {
            val root = java.io.File(cacheDir, "shared_exports").canonicalPath + java.io.File.separator
            val files = pendingPaths.map { java.io.File(it) }
            if (files.all { it.canonicalPath.startsWith(root) }) pendingExport = PendingExport(files, pendingMime)
        }
        val debugEmulator = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            Build.HARDWARE in setOf("ranchu", "goldfish")
        if (!debugEmulator) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        selected = intent.getStringExtra("letter")
        showSettings = false
        lifecycleScope.launch {
            signedIn = withContext(Dispatchers.IO) { app.auth.signedIn() }
            app.preferences.flow.collect { lockEnabled = it.lock }
        }
        setContent {
            val settings by app.preferences.flow.collectAsStateWithLifecycle(initialValue = null)
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF38654B), onPrimary = Color.White,
                primaryContainer = Color(0xFFD4E8D7), onPrimaryContainer = Color(0xFF173C27),
                secondary = Color(0xFF526751), onSecondary = Color.White,
                secondaryContainer = Color(0xFFE1EBDD), onSecondaryContainer = Color(0xFF243923),
                tertiary = Color(0xFF42665B), onTertiary = Color.White,
                tertiaryContainer = Color(0xFFD5EADF), onTertiaryContainer = Color(0xFF203E34),
                background = Color(0xFFF7F7F0), onBackground = Color(0xFF20261F),
                surface = Color(0xFFF7F7F0), onSurface = Color(0xFF20261F),
                surfaceVariant = Color(0xFFEBEEE5), onSurfaceVariant = Color(0xFF465044),
                surfaceDim = Color(0xFFD8DDD2), surfaceBright = Color(0xFFF7F7F0),
                surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3EA),
                surfaceContainer = Color(0xFFEBEEE5), surfaceContainerHigh = Color(0xFFE4E9DF),
                surfaceContainerHighest = Color(0xFFDDE4D8), surfaceTint = Color(0xFF38654B),
                outline = Color(0xFF747E70), outlineVariant = Color(0xFFC5CEBF),
                inverseSurface = Color(0xFF30382F), inverseOnSurface = Color(0xFFF0F3EA),
                inversePrimary = Color(0xFFA7D3B2)
            )) {
                Surface(Modifier.fillMaxSize()) {
                    val s = settings
                    LaunchedEffect(s?.lock, unlocked, foregroundSession) {
                        if (s?.lock == true && !unlocked && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) unlock()
                    }
                    when {
                        s == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                        s.lock && !unlocked -> LockedScreen { unlock() }
                        else -> {
                            ArchiveScreen(s)
                        }
                    }
                    if (message != null && s != null && (!s.lock || unlocked)) {
                        AlertDialog(onDismissRequest = { message = null }, title = { Text("Briefkasten") },
                            text = { Text(message.orEmpty()) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExport?.let { export ->
            outState.putStringArrayList("pending_export_files", ArrayList(export.files.map { it.absolutePath }))
            outState.putString("pending_export_mime", export.mime)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showSettings = false
        selected = intent.getStringExtra("letter")
    }
    override fun onStart() {
        super.onStart()
        foregroundSession++
    }

    override fun onStop() {
        super.onStop()
        // Credential confirmation may itself stop this activity. Do not cancel the system prompt.
        if (lockEnabled) unlocked = false
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationState()
    }

    private fun notificationPermissionGranted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun refreshNotificationState() {
        notificationsEnabled = app.notifications.enabled()
        notificationSettingsRequired = !notificationsEnabled && (notificationPermissionGranted() ||
            (getSharedPreferences("permission-ui", MODE_PRIVATE).getBoolean("notifications-asked", false) &&
                Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)))
    }

    private fun requestNotifications(fromButton: Boolean = true) {
        refreshNotificationState()
        if (notificationsEnabled) return
        val history = getSharedPreferences("permission-ui", MODE_PRIVATE)
        if (notificationSettingsRequired) {
            if (fromButton) openNotificationSettings()
            return
        }
        history.edit().putBoolean("notifications-asked", true).apply()
        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        val appEnabled = notificationPermissionGranted() &&
            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        val intent = Intent(if (appEnabled) android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        if (appEnabled) intent.putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, LetterNotifications.CHANNEL)
        startActivity(intent)
    }

    private fun exportZip(period: ExportPeriod, backup: Boolean, images: PdfImages, destination: ExportDestination) = action {
        val archive = if (backup) BackupExport(app).prepare() else ArchiveExport(app).prepareShare(period, images)
        deliverExport(PendingExport(listOf(archive.file), "application/zip"), destination)
    }

    private fun exportPdf(period: ExportPeriod, images: PdfImages, separate: Boolean, destination: ExportDestination) = action {
        val export = PdfExport(app).prepareShare(period, images, separate)
        deliverExport(PendingExport(export.files, "application/pdf"), destination)
    }

    private fun deliverExport(export: PendingExport, destination: ExportDestination) {
        if (lockEnabled && !unlocked) return
        if (destination == ExportDestination.SAVE) {
            pendingExport = export
            saveDocuments.launch(exportSaveIntent(export))
            return
        }
        val uris = export.files.map { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.exports", it) }
        val clips = android.content.ClipData.newUri(contentResolver, "Archive export", uris.first())
        uris.drop(1).forEach { clips.addItem(android.content.ClipData.Item(it)) }
        val share = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = export.mime
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = clips
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share export"))
    }

    private fun action(block: suspend () -> Unit) {
        lifecycleScope.launch {
            working = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: AuthorizationException) { message = "DHL login failed. Please try again." }
            catch (e: Exception) { message = friendlyError(e) }
            finally { working = false }
        }
    }

    private fun unlock(onSuccess: (() -> Unit)? = null) {
        if (promptActive) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            message = "Set up a fingerprint, face unlock or screen PIN in Android settings first."
            return
        }
        promptActive = true
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptActive = false
                unlocked = true
                onSuccess?.invoke()
            }
            override fun onAuthenticationError(code: Int, text: CharSequence) { promptActive = false }
        })
        prompt?.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Briefkasten")
            .setSubtitle("Your letter archive stays on this device")
            .setAllowedAuthenticators(authenticators).build())
    }

    @Composable private fun LockedScreen(onUnlock: () -> Unit) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("YOUR POST, KEPT PRIVATE", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Text("A little privacy\nfor your post.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text("Unlock to browse saved envelopes and export your archive. Background downloads keep running.")
            Spacer(Modifier.height(28.dp))
            Button(onClick = onUnlock) { Text("Unlock archive") }
            if (message != null) Text(message.orEmpty(), Modifier.padding(top = 16.dp))
        }
    }

    @Composable private fun ArchiveScreen(settings: Settings) {
        val letters by app.database.archive().observe().collectAsStateWithLifecycle(initialValue = emptyList())
        val groups by app.database.archive().observeGroups().collectAsStateWithLifecycle(initialValue = emptyList())
        val detail = letters.firstOrNull { it.id == selected }
        var showCutout by rememberSaveable(selected) { mutableStateOf(true) }
        var showRecognizedText by rememberSaveable(selected) { mutableStateOf(false) }
        if (showRecognizedText && detail != null && !showSettings) {
            RecognizedTextSheet(app, detail, cutout = showCutout) { showRecognizedText = false }
        }
        androidx.activity.compose.BackHandler(enabled = showSettings || selected != null) {
            if (showSettings) showSettings = false else selected = null
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (detail != null && !showSettings) Envelope(detail, Modifier.fillMaxSize(), full = true, cutout = showCutout)
            Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = if (showSettings || detail != null) 12.dp else 24.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showSettings || detail != null) IconButton(onClick = {
                    if (showSettings) showSettings = false else selected = null
                }) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = if (showSettings) "Back" else "All letters")
                }
                Column(Modifier.weight(1f)) {
                    Text("BRIEFKASTEN", fontSize = 12.sp, letterSpacing = 3.sp, color = MaterialTheme.colorScheme.primary)
                    Text(if (showSettings) "Settings" else if (detail == null) "Your post, collected." else "Your envelope", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
                if (!showSettings) IconButton(onClick = { showSettings = true }) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                }
            }
            if (showSettings) {
                SettingsPage(settings, letters)
                return@Column
            }
            if (detail != null) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(formatEnvelopeDate(detail.date), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        SingleChoiceSegmentedButtonRow {
                            listOf("Cutout", "Full").forEachIndexed { index, label ->
                                SegmentedButton(selected = showCutout == (index == 0), onClick = { showCutout = index == 0 },
                                    icon = {}, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GroupTag(groups.firstOrNull { it.id == detail.groupId }, Modifier.widthIn(max = 150.dp))
                        Spacer(Modifier.weight(1f))
                        Text("Saved ${formatTimestamp(detail.firstSeen)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { showRecognizedText = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)) {
                    Text("Recognized text")
                }
                return@Column
            }
            Card(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(20.dp)) {
                    Text(if (signedIn) "${letters.size} saved ${if (letters.size == 1) "envelope" else "envelopes"}" else "Make room for your post", fontWeight = FontWeight.SemiBold)
                    val routineStatus = settings.status.startsWith("Up to date") ||
                        settings.status.startsWith("Archive ready.") || settings.status == "Checking for letters…"
                    if (!routineStatus) Text(settings.status, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
                    if (settings.lastCheck > 0) Text("Last complete check ${formatTimestamp(settings.lastCheck)}",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(
                enabled = !working,
                onClick = {
                    if (signedIn) app.checkNow()
                    else action { login.launch(app.auth.loginIntent()) }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp).fillMaxWidth().heightIn(min = 52.dp)
            ) { Text(if (signedIn) "Check now" else "Sign in with DHL") }
            if (letters.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("THE ARCHIVE", fontSize = 12.sp, letterSpacing = 2.sp)
                    Text("${letters.size} saved", style = MaterialTheme.typography.bodySmall)
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val currentMonth = YearMonth.now()
                    val zone = ZoneId.systemDefault()
                    val months = letters.groupBy { YearMonth.from(announcementDate(it, zone)) }.toSortedMap(reverseOrder())
                    months.forEach { (month, entries) ->
                        item(key = "month-$month", span = { GridItemSpan(maxLineSpan) }) {
                            val heading = when (month) {
                                currentMonth -> "This month"
                                currentMonth.minusMonths(1) -> "Last month"
                                else -> month.format(DateTimeFormatter.ofPattern(
                                    if (month.year == currentMonth.year) "LLLL" else "LLLL yyyy", Locale.getDefault()))
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                HorizontalDivider(Modifier.weight(1f))
                            }
                        }
                        items(entries.sortedByDescending { announcementDate(it, zone) }, key = { it.id }) { letter ->
                            Card(onClick = { selected = letter.id }, shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                Envelope(letter, Modifier.fillMaxWidth(), cutout = true)
                                Column(Modifier.padding(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(formatEnvelopeDate(letter.date), fontWeight = FontWeight.Medium,
                                            maxLines = 1, modifier = Modifier.weight(1f))
                                        Box(
                                            Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(50))
                                                .toggleable(
                                                    value = letter.received,
                                                    role = Role.Checkbox,
                                                    onValueChange = { value ->
                                                        lifecycleScope.launch { app.database.archive().received(letter.id, value) }
                                                    }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(painterResource(R.drawable.ic_check), contentDescription = "Received",
                                                modifier = Modifier.size(18.dp),
                                                tint = if (letter.received) MaterialTheme.colorScheme.primary
                                                    else Color(0xFF9E9E9E))
                                        }
                                    }
                                    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(28.dp)) {
                                        GroupTag(groups.firstOrNull { it.id == letter.groupId })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    private fun regionalDate(date: java.time.LocalDate): String {
        val locale = resources.configuration.locales[0]
        val region = locale.getUnicodeLocaleType("rg")?.take(2) ?: locale.country
        val pattern = if (region.equals("DE", ignoreCase = true)) "dd.MM.yyyy"
            else android.text.format.DateFormat.getBestDateTimePattern(locale, "yyyyMMdd")
        return date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    private fun formatEnvelopeDate(raw: String): String = parsedAnnouncementDate(raw)?.let(::regionalDate) ?: raw

    private fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        return "${regionalDate(java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate())} ${android.text.format.DateFormat.getTimeFormat(this).format(date)}"
    }

    @Composable private fun SettingsPage(settings: Settings, letters: List<Letter>) {
        var exportIndex by rememberSaveable { mutableIntStateOf(0) }
        var showPdfDialog by rememberSaveable { mutableStateOf(false) }
        var showZipDialog by rememberSaveable { mutableStateOf(false) }
        val exportPeriod = ExportPeriod.entries[exportIndex]
        val exportCount = filterExport(letters, exportPeriod).size
        if (showPdfDialog) PdfExportDialog(exportPeriod, exportCount, close = { showPdfDialog = false }) { images, separate, destination ->
            showPdfDialog = false
            exportPdf(exportPeriod, images, separate, destination)
        }
        if (showZipDialog) ZipExportDialog(exportPeriod, exportCount, letters.size,
            selectPeriod = { exportIndex = it.ordinal }, close = { showZipDialog = false }) { backup, images, destination ->
            showZipDialog = false
            exportZip(if (backup) ExportPeriod.ALL else exportPeriod, backup, images, destination)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("Check frequency", style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15L, 30L, 60L, 180L).forEach { minutes ->
                    FilterChip(selected = settings.interval == minutes, onClick = { action { app.preferences.interval(minutes); app.schedule() } }, label = { Text(if (minutes < 60) "${minutes}m" else "${minutes / 60}h") })
                }
            }
            SettingToggle("Save new letters automatically", settings.polling) { value -> action { app.preferences.polling(value); app.schedule() } }
            SettingToggle("Require fingerprint, face or PIN", settings.lock) { value ->
                if (value) unlock { action { app.preferences.lock(true) } }
                else action { app.preferences.lock(false) }
            }
            SettingToggle("Envelope preview in notifications", settings.pictures) { value -> action { app.preferences.pictures(value); app.notifications.cancelAll() } }
            Text("Previews can be visible outside the app. Sensitive content is hidden on the lock screen unless Android settings override it.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            SettingsButton(onLongClick = if (signedIn && letters.isNotEmpty()) ({
                action {
                    val latest = letters.maxWithOrNull(compareBy<Letter> { announcementDate(it, ZoneId.systemDefault()) }.thenBy { it.firstSeen })
                    if (latest != null) {
                        val posted = withContext(Dispatchers.IO) { app.notifications.post(latest, picture = true, test = true, groupName = app.groups.index(latest)?.name) }
                        if (!posted) message = "Enable notifications in Android settings to send a test."
                    }
                }
            }) else null, onClick = {
                action {
                    if (settings.notifications && notificationsEnabled) {
                        app.preferences.notifications(false)
                        app.notifications.cancelAll()
                    } else {
                        app.preferences.notifications(true)
                        if (!notificationsEnabled) requestNotifications()
                    }
                }
            }) {
                Text(when {
                    settings.notifications && notificationsEnabled -> "Disable notifications"
                    notificationSettingsRequired -> "Open notification settings"
                    else -> "Enable notifications"
                })
            }
            if (!signedIn) {
                Spacer(Modifier.height(24.dp))
                Text("DHL sign-in opens in Chrome for compatibility. Your Android default browser is unchanged. You can also try your system browser below.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                SettingsButton(enabled = !working, onClick = { action { login.launch(app.auth.loginIntent(useChrome = false)) } }) { Text("Try system browser instead") }
            }
            HorizontalDivider(Modifier.padding(vertical = 24.dp))
            GroupSettings(app)
            HorizontalDivider(Modifier.padding(vertical = 24.dp))
            Text("Export archive", style = MaterialTheme.typography.bodyLarge)
            Text("${letters.size} images saved. Export images, searchable PDFs or a full backup. Exports are not encrypted.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Text(exportPeriod.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Slider(
                value = exportIndex.toFloat(),
                onValueChange = { exportIndex = it.roundToInt() },
                valueRange = 0f..ExportPeriod.entries.lastIndex.toFloat(),
                steps = ExportPeriod.entries.size - 2,
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = exportPeriod.label }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("This month", style = MaterialTheme.typography.bodySmall)
                Text("All time", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    SettingsButton(enabled = !working, onClick = { showZipDialog = true }) { Text("Export ZIP") }
                }
                Box(Modifier.weight(1f)) {
                    SettingsButton(enabled = !working && exportCount > 0, onClick = { showPdfDialog = true }) { Text("Export PDF") }
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsButton(enabled = !working, onClick = { importBackup.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("Import backup") }
            if (signedIn) {
                Spacer(Modifier.height(24.dp))
                SettingsButton(enabled = !working, onClick = { action {
                app.operation.withLock { app.auth.signOut(); app.notifications.cancelAll() }
                signedIn = false; app.schedule()
                app.preferences.status("Disconnected from DHL. Your saved archive is still here.")
                } }) { Text("Disconnect DHL") }
            }
            Text("Independent personal app. Not affiliated with Deutsche Post or DHL.", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Version ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable private fun SettingsButton(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
        val colors = MaterialTheme.colorScheme
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(
                enabled = enabled, role = Role.Button, onClick = onClick,
                onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) "Send test notification" else null
            ),
            shape = RoundedCornerShape(16.dp),
            color = if (enabled) lerp(colors.surface, colors.primary, 0.20f) else colors.onSurface.copy(alpha = 0.12f),
            contentColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
        ) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal)) { content() }
            }
        }
    }

    @Composable private fun SettingToggle(title: String, value: Boolean, change: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).padding(end = 8.dp))
            Switch(checked = value, onCheckedChange = change)
        }
    }

    @Composable private fun Envelope(letter: Letter, modifier: Modifier, full: Boolean = false, cutout: Boolean = false) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, letter.id, full, cutout) {
            value = withContext(Dispatchers.IO) {
                runCatching { (if (cutout) app.cutouts.bytes(letter) else app.vault.read(letter.fileName))?.let {
                    if (full) android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) else previewBitmap(it, 600)
                } }.getOrNull()
            }
        }
        val frame = if (full) modifier else modifier.aspectRatio(4f / 3f).background(Color.White)
        Box(frame, contentAlignment = Alignment.Center) {
            bitmap?.let {
                if (full) ZoomableEnvelope(it, "Envelope announced for ${formatEnvelopeDate(letter.date)}", fit = cutout)
                else Image(it.asImageBitmap(), contentDescription = "Envelope announced for ${formatEnvelopeDate(letter.date)}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } ?: if (full) CircularProgressIndicator() else Text("Envelope preview", style = MaterialTheme.typography.bodySmall)
        }
    }
}
