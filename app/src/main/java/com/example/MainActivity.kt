package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.data.local.FanevaDatabase
import com.example.data.repository.AuthRepository
import com.example.data.repository.RemoteAuthRepository
import com.example.data.repository.FanevaRepository
import com.example.data.repository.OkHttpSyncHttpClient
import com.example.data.repository.SyncRepositoryImpl
import com.example.data.security.DeviceIdentityProvider
import com.example.data.security.AuthSessionClient
import com.example.data.security.EncryptedSessionTokenStore
import com.example.ui.FanevaViewModel
import com.example.ui.navigation.FanevaApp
import com.example.ui.theme.FanevaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: FanevaViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val database = FanevaDatabase.getDatabase(applicationContext, lifecycleScope)
                val fanevaRepository = FanevaRepository(database)
                val identityProvider = DeviceIdentityProvider()
                val identity = identityProvider.getOrCreate()
                val syncRepository = SyncRepositoryImpl(
                    database.pendingSyncDao(),
                    database.syncCursorDao(),
                    httpClient = OkHttpSyncHttpClient(identityProvider),
                    deviceId = identity.deviceId
                )
                val authRepository = AuthRepository(database.authDao())
                val remoteAuthRepository = RemoteAuthRepository(
                    client = AuthSessionClient(identityProvider),
                    tokenStore = EncryptedSessionTokenStore(applicationContext)
                )
                return FanevaViewModel(
                    fanevaRepository = fanevaRepository,
                    syncRepository = syncRepository,
                    authRepository = authRepository,
                    remoteAuthRepository = remoteAuthRepository,
                    stagingApiBaseUrl = BuildConfig.FANEVA_STAGING_API_URL
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FanevaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FanevaApp(viewModel = viewModel)
                }
            }
        }
    }
}
