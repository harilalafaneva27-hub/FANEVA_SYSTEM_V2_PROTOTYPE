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

private enum class LoginMode { LOGIN, OWNER_BOOTSTRAP }

@Composable
fun LoginScreen(
    onLogin: (String, String, (String?) -> Unit) -> Unit,
    onOwnerBootstrap: (String, String, String, String, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(LoginMode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var bootstrapCode by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("FANEVA SYSTEM V2", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Authentification sécurisée par serveur", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (mode == LoginMode.LOGIN) "Connexion" else "Initialisation du premier ADMIN",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("Identifiant") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("auth_username_input")
                )
                if (mode == LoginMode.OWNER_BOOTSTRAP) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it; error = null },
                        label = { Text("Nom complet ADMIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bootstrap_display_name_input")
                    )
                    OutlinedTextField(
                        value = bootstrapCode,
                        onValueChange = { bootstrapCode = it; error = null },
                        label = { Text("Code propriétaire à usage unique (30 min)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bootstrap_code_input")
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Mot de passe") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("auth_password_input")
                )
                Button(
                    enabled = !busy && username.isNotBlank() && password.isNotEmpty() &&
                        (mode == LoginMode.LOGIN || (bootstrapCode.isNotBlank() && displayName.isNotBlank())),
                    onClick = {
                        busy = true
                        if (mode == LoginMode.LOGIN) {
                            onLogin(username.trim(), password) { result ->
                                error = result
                                busy = false
                            }
                        } else {
                            onOwnerBootstrap(bootstrapCode.trim(), username.trim(), password, displayName.trim()) { result ->
                                error = result
                                busy = false
                                if (result == null) {
                                    bootstrapCode = ""
                                    password = ""
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag(
                        if (mode == LoginMode.LOGIN) "server_login_button" else "owner_bootstrap_button"
                    )
                ) {
                    Text(if (mode == LoginMode.LOGIN) "Se connecter" else "Créer le premier ADMIN")
                }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        mode = if (mode == LoginMode.LOGIN) LoginMode.OWNER_BOOTSTRAP else LoginMode.LOGIN
                        error = null
                        bootstrapCode = ""
                    },
                    modifier = Modifier.fillMaxWidth().testTag("auth_mode_toggle")
                ) {
                    Text(
                        if (mode == LoginMode.LOGIN) "Initialiser le premier ADMIN sur ce serveur"
                        else "Retour à la connexion"
                    )
                }
                Text(
                    "Le rôle et les magasins autorisés sont fournis par le serveur. Aucun compte local de démonstration n'est accepté.",
                    style = MaterialTheme.typography.bodySmall
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login_error"))
                }
            }
        }
    }
}
