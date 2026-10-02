package com.mikhail.authenticator.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException

/** Which half of spec §3.В is on screen. */
sealed interface VaultDialog {
    data object Export : VaultDialog
    data object Import : VaultDialog
}

/**
 * Encrypted export and import through SAF, in two steps each:
 *
 *  - Export: password (+ confirmation) → "create document" picker → file written.
 *  - Import: "open document" picker → password → decrypted and merged.
 *
 * The file is never written unencrypted: the JSON envelope carries only KDF/cipher metadata,
 * and the accounts themselves sit inside AES-256-GCM ciphertext (see VaultCodec).
 */
@Composable
fun VaultDialogs(
    dialog: VaultDialog,
    onDismiss: () -> Unit,
    onExport: suspend (CharArray) -> String,
    onImport: suspend (String, CharArray) -> Int,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        val secret = password.toCharArray()
        password = ""
        confirmation = ""
        scope.launch {
            runCatching {
                val text = onExport(secret)
                withContext(Dispatchers.IO) { VaultIo.write(context, target, text) }
            }.onSuccess {
                onMessage("Exported to ${VaultIo.displayName(context, target)}")
                onDismiss()
            }.onFailure {
                onMessage("Could not save the file: ${it.message}")
                onDismiss()
            }
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val source = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { VaultIo.read(context, source) } }
                .onSuccess { text ->
                    pendingImportText = text
                    error = null
                }
                .onFailure {
                    onMessage("Could not read the file: ${it.message}")
                    onDismiss()
                }
        }
    }

    when (dialog) {
        VaultDialog.Export -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Export accounts") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The file is encrypted with a password. Losing it means losing the copy: without the password it cannot be restored.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Repeat the password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        password.length < 8 -> error = "Password is shorter than 8 characters"
                        password != confirmation -> error = "Passwords do not match"
                        else -> {
                            error = null
                            createFile.launch(VaultIo.suggestedFileName())
                        }
                    }
                }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        VaultDialog.Import -> {
            val fileText = pendingImportText
            if (fileText == null) {
                // First step of the import: pick the file.
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("Import accounts") },
                    text = { Text("Pick an export file (.json). It may live in any folder — access is granted to the chosen file only.") },
                    confirmButton = {
                        TextButton(onClick = { openFile.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                            Text("Choose file")
                        }
                    },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                )
            } else {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("File password") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val secret = password.toCharArray()
                            password = ""
                            scope.launch {
                                runCatching { onImport(fileText, secret) }
                                    .onSuccess { added ->
                                        onMessage(if (added == 0) "No new accounts — everything is already added" else "Accounts added: $added")
                                        onDismiss()
                                    }
                                    .onFailure { failure ->
                                        error = when (failure) {
                                            is AEADBadTagException -> "Wrong password or a damaged file"
                                            else -> failure.message ?: "Could not read the file"
                                        }
                                    }
                            }
                        }) { Text("Import") }
                    },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                )
            }
        }
    }
}
