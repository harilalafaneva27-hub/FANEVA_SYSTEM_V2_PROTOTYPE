package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

private enum class LoginMode { ADMIN, SELLER }

@Composable
fun LoginScreen(
    onAdminLogin: (String, (String?) -> Unit) -> Unit,
    onSellerLogin: (String, String, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(LoginMode.ADMIN) }
    var adminCode by remember { mutableStateOf("") }
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
        Text("Connexion obligatoire", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { mode = LoginMode.ADMIN; error = null }, modifier = Modifier.testTag("login_mode_admin")) {
                        Text("Administrateur")
                    }
                    TextButton(onClick = { mode = LoginMode.SELLER; error = null }, modifier = Modifier.testTag("login_mode_seller")) {
                        Text("Vendeur")
                    }
                }
                if (mode == LoginMode.ADMIN) {
                    OutlinedTextField(
                        value = adminCode,
                        onValueChange = { adminCode = it; error = null },
                        label = { Text("Code Admin") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("admin_code_input")
                    )
                    Button(
                        enabled = !busy && adminCode.isNotEmpty(),
                        onClick = { busy = true; onAdminLogin(adminCode) { error = it; busy = false } },
                        modifier = Modifier.fillMaxWidth().testTag("admin_login_button")
                    ) { Text("Ouvrir la session Admin") }
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; error = null },
                        label = { Text("Identifiant vendeur") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("seller_username_input")
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Mot de passe") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("seller_password_input")
                    )
                    Button(
                        enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                        onClick = { busy = true; onSellerLogin(username, password) { error = it; busy = false } },
                        modifier = Modifier.fillMaxWidth().testTag("seller_login_button")
                    ) { Text("Se connecter") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login_error")) }
            }
        }
    }
}
