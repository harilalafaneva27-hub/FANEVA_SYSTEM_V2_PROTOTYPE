package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private enum class LoginMode { ADMIN, SELLER }

@Composable
fun LoginScreen(
    adminSetupRequired: Boolean,
    oneTimeRecoveryCode: String?,
    onInitializeAdmin: (String, String, String, (String?) -> Unit) -> Unit,
    onAcknowledgeRecoveryCode: () -> Unit,
    onAdminLogin: (String, String, (String?) -> Unit) -> Unit,
    onSellerLogin: (String, String, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(LoginMode.ADMIN) }
    var adminUsername by remember { mutableStateOf("") }
    var adminName by remember { mutableStateOf("") }
    var adminPassword by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("FANEVA SYSTEM V2", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        if (oneTimeRecoveryCode != null) {
            Text("CODE DE RÉCUPÉRATION ADMIN", style = MaterialTheme.typography.titleMedium)
            Text("Copiez et conservez ce code hors de l'app. Il ne sera affiché qu'une seule fois.")
            SelectionContainer { Text(oneTimeRecoveryCode, style = MaterialTheme.typography.titleLarge) }
            Button(onClick = onAcknowledgeRecoveryCode, modifier = Modifier.testTag("recovery_code_ack")) {
                Text("J'ai conservé le code")
            }
        } else if (adminSetupRequired) {
            Text("Première configuration ADMIN", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(adminUsername, { adminUsername = it; error = null }, label = { Text("Identifiant ADMIN") }, singleLine = true)
            OutlinedTextField(adminName, { adminName = it; error = null }, label = { Text("Nom affiché") }, singleLine = true)
            OutlinedTextField(adminPassword, { adminPassword = it; error = null }, label = { Text("Mot de passe (12 caractères minimum)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Button(
                enabled = !busy && adminUsername.isNotBlank() && adminName.isNotBlank() && adminPassword.length >= 12,
                onClick = { busy = true; onInitializeAdmin(adminUsername, adminName, adminPassword) { error = it; busy = false } },
                modifier = Modifier.fillMaxWidth().testTag("admin_initial_setup")
            ) { Text("Créer le compte ADMIN") }
        } else {
            Text("Connexion obligatoire", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { mode = LoginMode.ADMIN; error = null }, modifier = Modifier.testTag("login_mode_admin")) { Text("Administrateur") }
                        TextButton(onClick = { mode = LoginMode.SELLER; error = null }, modifier = Modifier.testTag("login_mode_seller")) { Text("Vendeur") }
                    }
                    if (mode == LoginMode.ADMIN) {
                        OutlinedTextField(adminUsername, { adminUsername = it; error = null }, label = { Text("Identifiant ADMIN") }, singleLine = true)
                        OutlinedTextField(adminPassword, { adminPassword = it; error = null }, label = { Text("Mot de passe") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        Button(
                            enabled = !busy && adminUsername.isNotBlank() && adminPassword.isNotEmpty(),
                            onClick = { busy = true; onAdminLogin(adminUsername, adminPassword) { error = it; busy = false } },
                            modifier = Modifier.fillMaxWidth().testTag("admin_login_button")
                        ) { Text("Ouvrir la session Admin") }
                    } else {
                        OutlinedTextField(username, { username = it; error = null }, label = { Text("Identifiant vendeur") }, singleLine = true)
                        OutlinedTextField(password, { password = it; error = null }, label = { Text("Mot de passe") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        Button(
                            enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                            onClick = { busy = true; onSellerLogin(username, password) { error = it; busy = false } },
                            modifier = Modifier.fillMaxWidth().testTag("seller_login_button")
                        ) { Text("Se connecter") }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login_error")) }
    }
}
