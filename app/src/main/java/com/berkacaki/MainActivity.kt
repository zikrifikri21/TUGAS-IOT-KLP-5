package com.berkacaki

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.berkacaki.ui.theme.BerkacaKiTheme

private const val TAG = "BerkacaKi_Main"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate - APK build ini AKTIF dan berjalan")
        handleDeepLink(intent)
        setContent {
            BerkacaKiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RoleSelectionScreen(
                        onSourceClick = {
                            Log.d(TAG, "User memilih role: Source")
                            startActivity(Intent(this, SourceActivity::class.java))
                        },
                        onViewerClick = {
                            Log.d(TAG, "User memilih role: Viewer")
                            startActivity(Intent(this, ViewerActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data
        if (uri != null) {
            Log.d(TAG, "Aplikasi dibuka melalui deep link: $uri")
        }
    }
}

@Composable
fun RoleSelectionScreen(onSourceClick: () -> Unit, onViewerClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SystemProtected", style = MaterialTheme.typography.headlineMedium)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp))
        Button(onClick = onSourceClick) { Text("Jadi Sumber (bagikan PIN)") }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Button(onClick = onViewerClick) { Text("Masukkan PIN") }
    }
}