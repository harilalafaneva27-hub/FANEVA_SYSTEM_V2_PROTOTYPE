package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    onRemoteLogin: (String, String, String, (String?) -> Unit) -> Unit,
    onEnrollDevice: (String, (String?, String?) -> Unit) -> Unit,
    onOwnerBootstrap: (String, String, String, String, String, (String?) -> Unit) -> Unit,
    initialServerOrigin: String = "",
    modifier: Modifier = Modifier
) {
    var serverOrigin by remember(initialServerOrigin) { mutableStateOf(initialServerOrigin) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf<String?>(null) }
    var bootstrapCode by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("FANEVA SYSTEM V2", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Authentification serveur obligatoire", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = serverOrigin,
                    onValueChange = { serverOrigin = it; error = null },
                    label = { Text("Origine HTTPS du Worker") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("server_origin_input")
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("Identifiant serveur") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("remote_username_input")
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Mot de passe serveur") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("remote_password_input")
                )
                Button(
                    enabled = !busy && serverOrigin.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        busy = true
                        onRemoteLogin(serverOrigin, username, password) {
                            error = it
                            if (it == null) password = ""
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("remote_login_button")
                ) { Text("Connexion serveur + validation session") }

                TextButton(
                    enabled = !busy && serverOrigin.isNotBlank(),
                    onClick = {
                        busy = true
                        onEnrollDevice(serverOrigin) { message, enrolledDeviceId ->
                            error = message
                            if (enrolledDeviceId != null) {
                                deviceId = enrolledDeviceId
                                status = if (message == null) "Appareil enregistré en attente d'approbation ADMIN." else null
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("enroll_device_button")
                ) { Text("Inscrire cet appareil") }

                deviceId?.let {
                    Text("Device ID à communiquer à l'ADMIN :", style = MaterialTheme.typography.labelMedium)
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("enrolled_device_id"))
                }

                TextButton(
                    enabled = !busy && serverOrigin.isNotBlank(),
                    onClick = { status = "Saisir le code initial uniquement sur le premier appareil ADMIN." },
                    modifier = Modifier.fillMaxWidth().testTag("show_owner_bootstrap")
                ) { Text("Initialisation du premier ADMIN") }

                OutlinedTextField(
                    value = bootstrapCode,
                    onValueChange = { bootstrapCode = it; error = null },
                    label = { Text("Code bootstrap à usage unique") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("owner_bootstrap_code")
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; error = null },
                    label = { Text("Nom ADMIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("owner_bootstrap_name")
                )
                Button(
                    enabled = !busy && serverOrigin.isNotBlank() && bootstrapCode.isNotBlank() &&
                        username.isNotBlank() && password.length >= 12 && displayName.isNotBlank(),
                    onClick = {
                        busy = true
                        onOwnerBootstrap(serverOrigin, bootstrapCode, username, password, displayName) {
                            error = it
                            if (it == null) { password = ""; bootstrapCode = "" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("owner_bootstrap_button")
                ) { Text("Créer le premier ADMIN") }

                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login_error")) }
            }
        }
    }
}
