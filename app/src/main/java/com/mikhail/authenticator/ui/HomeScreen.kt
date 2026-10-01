package com.mikhail.authenticator.ui

import android.Manifest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    var showAddSheet by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var showManualForm by remember { mutableStateOf(false) }
    var vaultDialog by remember { mutableStateOf<VaultDialog?>(null) }

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
        else scope.launch { snackbarHostState.showSnackbar("Без доступа к камере можно ввести ключ вручную") }
    }

    /**
     * Google Authenticator import: the system PhotoPicker hands back one image URI, so the app
     * needs no READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE permission at all.
     */
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { snackbarHostState.showSnackbar(viewModel.importFromImage(uri)) }
    }

    /** Copy the code and wipe the clipboard 30 s later (spec §3.Г). */
    fun copyCode(code: String) {
        clipboard.setText(AnnotatedString(code))
        scope.launch {
            snackbarHostState.showSnackbar("Код скопирован")
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
                    scope.launch { snackbarHostState.showSnackbar("Это не TOTP-код: ожидался otpauth://totp/…") }
                } else {
                    viewModel.add(parsed)
                    scope.launch { snackbarHostState.showSnackbar("Добавлено: ${parsed.issuer}") }
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
                    IconButton(onClick = { vaultDialog = VaultDialog.Export }) {
                        Icon(Icons.Filled.Share, contentDescription = "Экспорт и импорт")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить аккаунт")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchQuery.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("Поиск") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            if (accounts.isEmpty()) {
                EmptyState(query = searchQuery, modifier = Modifier.fillMaxSize())
            } else {
                val columns = if (isLandscape()) 2 else 1
                LazyAccountGrid(
                    accounts = accounts,
                    columns = columns,
                    nowSeconds = nowSeconds,
                    onCopy = ::copyCode,
                    onLongPress = { account ->
                        scope.launch {
                            val result = snackbarHostState.showSnackbar("Удалить ${account.issuer}?", actionLabel = "Удалить")
                            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.delete(account)
                        }
                    },
                )
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            ListItem(
                headlineContent = { Text("Сканировать QR") },
                leadingContent = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                modifier = Modifier.clickableItem {
                    showAddSheet = false
                    if (hasCamera(context)) cameraPermission.launch(Manifest.permission.CAMERA) else null
                },
            )
            ListItem(
                headlineContent = { Text("Импорт из скриншота") },
                supportingContent = { Text("Ключи из Google Authenticator") },
                leadingContent = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                modifier = Modifier.clickableItem {
                    showAddSheet = false
                    screenshotPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Ввести вручную") },
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
                scope.launch { snackbarHostState.showSnackbar("Добавлено: ${account.issuer}") }
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
}

/** Small helper so list rows read as tappable without pulling in the experimental API. */
private fun Modifier.clickableItem(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

private fun hasCamera(context: android.content.Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

@Composable
private fun EmptyState(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (query.isBlank()) "Пока пусто" else "Ничего не найдено",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (query.isBlank()) "Добавьте аккаунт кнопкой +" else "Попробуйте другой запрос",
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
        title = { Text("Новый аккаунт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text("Сервис") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("Аккаунт") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Секретный ключ (Base32)") },
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
                    cleanSecret.isEmpty() -> "Введите секретный ключ"
                    else -> runCatching { Totp.generate(cleanSecret) }.exceptionOrNull()?.let { "Ключ не похож на Base32" }
                }
                if (problem != null) {
                    error = problem
                    return@TextButton
                }
                onSave(
                    OtpAccount(
                        issuer = issuer.trim().ifEmpty { account.trim().ifEmpty { "Аккаунт" } },
                        account = account.trim(),
                        secret = cleanSecret.uppercase().filter { !it.isWhitespace() },
                        algorithm = OtpAlgorithm.SHA1,
                        digits = Totp.DEFAULT_DIGITS,
                        period = Totp.DEFAULT_PERIOD,
                    ),
                )
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
