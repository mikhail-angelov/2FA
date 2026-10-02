package com.mikhail.authenticator.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikhail.authenticator.crypto.OtpAlgorithm
import com.mikhail.authenticator.crypto.Totp
import com.mikhail.authenticator.data.StoredAccount
import com.mikhail.authenticator.otp.OtpAccount
import com.mikhail.authenticator.otp.OtpAuthUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    // Настройки: как в прошлый раз выбрал пользователь, столько колонок и показываем.
    // Отдельного экрана настроек в приложении нет и не нужно — одна строка, одна настройка.
    val preferences = remember { context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE) }
    var portraitColumns by remember { mutableStateOf(preferences.getInt(KEY_COLUMNS, 1).coerceIn(1, 2)) }
    var showSettings by remember { mutableStateOf(false) }

    var showAddSheet by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var showManualForm by remember { mutableStateOf(false) }
    var vaultDialog by remember { mutableStateOf<VaultDialog?>(null) }
    var longPressed by remember { mutableStateOf<StoredAccount?>(null) }

    // One clock for the whole list: every code and ring advances together, once a second.
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSeconds = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true
        else scope.launch { snackbarHostState.showSnackbar("Without camera access you can enter the key by hand") }
    }

    /** Текст неудачного импорта: показывается окном, пока пользователь сам его не закроет. */
    var importProblem by remember { mutableStateOf<String?>(null) }

    /**
     * Переслать текст в любой мессенджер: снимки экрана в приложении запрещены, так что это
     * единственный способ унести сообщение об ошибке наружу и показать его целиком.
     */
    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share the message")) }
    }

    /**
     * Google Authenticator import: the system PhotoPicker hands back one image URI, so the app
     * needs no READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE permission at all.
     */
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val message = viewModel.importFromImage(uri)
            // Об успехе сообщает всплывашка, а неудачу показываем окном: её нужно прочитать
            // целиком и унести из приложения (скриншоты здесь запрещены), а всплывашка исчезает
            // сама и обрезает длинный текст. Окно закрывается только кнопкой.
            if (message.startsWith("Accounts imported")) {
                snackbarHostState.showSnackbar(message)
            } else {
                importProblem = message
            }
        }
    }

    importProblem?.let { text ->
        AlertDialog(
            onDismissRequest = { },
            icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
            title = { Text("Import from image failed") },
            text = { SelectionContainer { Text(text) } },
            confirmButton = { TextButton(onClick = { importProblem = null }) { Text("Close") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
                    TextButton(onClick = { shareText(text) }) { Text("Share") }
                }
            },
        )
    }

    /** Copy the code and wipe the clipboard 30 s later (spec §3.Г). */
    fun copyCode(code: String) {
        clipboard.setText(AnnotatedString(code))
        scope.launch {
            snackbarHostState.showSnackbar("Code copied")
            // Android 13+ shows its own "copied" toast; clearing silently 30 s later is still right.
            delay(30_000)
            val current = clipboard.getText()?.text
            if (current == code) clipboard.setText(AnnotatedString(""))
        }
    }

    if (scanning) {
        ScannerScreen(
            onResult = { raw ->
                scanning = false
                val parsed = OtpAuthUri.parse(raw)
                if (parsed == null) {
                    scope.launch { snackbarHostState.showSnackbar("Not a TOTP code: expected otpauth://totp/…") }
                } else {
                    viewModel.add(parsed)
                    scope.launch { snackbarHostState.showSnackbar("Added: ${parsed.issuer}") }
                }
            },
            onCancel = { scanning = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("2FA") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { vaultDialog = VaultDialog.Export }) {
                        Icon(Icons.Filled.Share, contentDescription = "Export and import")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (accounts.isEmpty()) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                // В портретном режиме — сколько выбрано в настройках, в ландшафте всегда две.
                val columns = if (isLandscape()) 2 else portraitColumns
                LazyAccountGrid(
                    accounts = accounts,
                    columns = columns,
                    nowSeconds = nowSeconds,
                    onCopy = ::copyCode,
                    onLongPress = { account -> longPressed = account },
                )
            }
        }
    }

    // Долгое нажатие открывает действия по аккаунту. Раньше оно сразу предлагало удаление, и
    // промах пальцем вёл к нему же; теперь между поднятием и удалением стоит выбор.
    val pressed = longPressed
    if (pressed != null) {
        ModalBottomSheet(
            onDismissRequest = { longPressed = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            ListItem(
                headlineContent = { Text("Move to top") },
                supportingContent = { Text("Show first in the list") },
                leadingContent = { Icon(Icons.Filled.VerticalAlignTop, contentDescription = null) },
                modifier = Modifier.clickableItem {
                    viewModel.moveToTop(pressed)
                    longPressed = null
                },
            )
            ListItem(
                headlineContent = { Text("Delete") },
                supportingContent = { Text(pressed.issuer) },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                modifier = Modifier.clickableItem {
                    longPressed = null
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            "Delete ${pressed.issuer}?",
                            actionLabel = "Delete",
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            viewModel.delete(pressed)
                        }
                    }
                },
            )
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            ListItem(
                headlineContent = { Text("Scan QR") },
                leadingContent = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                modifier = Modifier.clickableItem {
                    showAddSheet = false
                    if (hasCamera(context)) cameraPermission.launch(Manifest.permission.CAMERA) else null
                },
            )
            ListItem(
                headlineContent = { Text("Import from screenshot") },
                supportingContent = { Text("Keys from Google Authenticator") },
                leadingContent = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                modifier = Modifier.clickableItem {
                    showAddSheet = false
                    screenshotPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Enter manually") },
                leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                modifier = Modifier.clickableItem {
                    showAddSheet = false
                    showManualForm = true
                },
            )
        }
    }

    if (showManualForm) {
        ManualEntryDialog(
            onDismiss = { showManualForm = false },
            onSave = { account ->
                viewModel.add(account)
                showManualForm = false
                scope.launch { snackbarHostState.showSnackbar("Added: ${account.issuer}") }
            },
        )
    }

    vaultDialog?.let { dialog ->
        VaultDialogs(
            dialog = dialog,
            onDismiss = { vaultDialog = null },
            onExport = { password -> viewModel.export(password) },
            onImport = { text, password -> viewModel.import(text, password) },
            onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        )
    }

    // Единственная настройка приложения: сколько колонок в списке в портретном режиме.
    // В ландшафте всегда две — там места хватает, и спрашивать не о чем.
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Columns in the list (portrait)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "One", 2 to "Two").forEach { (count, label) ->
                            FilterChip(
                                selected = portraitColumns == count,
                                onClick = {
                                    portraitColumns = count
                                    preferences.edit().putInt(KEY_COLUMNS, count).apply()
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } },
        )
    }
}

/** Имена для настроек: одно поле, одна настройка. */
private const val SETTINGS = "settings"
private const val KEY_COLUMNS = "columns_portrait"

/** Small helper so list rows read as tappable without pulling in the experimental API. */
private fun Modifier.clickableItem(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

private fun hasCamera(context: android.content.Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Nothing here yet", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Add an account with the + button",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Manual entry (spec §3.А): issuer, account, Base32 secret. */
@Composable
private fun ManualEntryDialog(onDismiss: () -> Unit, onSave: (OtpAccount) -> Unit) {
    var issuer by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text("Issuer") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("Account") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Secret key (Base32)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleanSecret = secret.trim()
                val problem = when {
                    cleanSecret.isEmpty() -> "Enter the secret key"
                    else -> runCatching { Totp.generate(cleanSecret) }.exceptionOrNull()?.let { "The key does not look like Base32" }
                }
                if (problem != null) {
                    error = problem
                    return@TextButton
                }
                onSave(
                    OtpAccount(
                        issuer = issuer.trim().ifEmpty { account.trim().ifEmpty { "Account" } },
                        account = account.trim(),
                        secret = cleanSecret.uppercase().filter { !it.isWhitespace() },
                        algorithm = OtpAlgorithm.SHA1,
                        digits = Totp.DEFAULT_DIGITS,
                        period = Totp.DEFAULT_PERIOD,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
