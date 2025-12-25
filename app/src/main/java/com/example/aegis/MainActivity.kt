package com.example.aegis

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.aegis.ui.theme.AegisTheme
import com.example.aegis.vpn.VpnController

/**
 * MainActivity - Phase 1: VPN Skeleton & Lifecycle
 *                Phase 3: Packet Parsing (Observation Only)
 *                Phase 4: Flow Table & Metadata (Read-Only)
 *                Phase 5: UID Attribution (Best-Effort, Metadata Only)
 *                Phase 6: Decision Engine (Decision-Only, No Enforcement)
 *
 * Provides basic UI to start and stop VPN service.
 * Handles VPN permission request flow.
 */
class MainActivity : ComponentActivity() {

    private var pendingVpnStart = false

    // Activity result launcher for VPN permission
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, start VPN
            VpnController.startVpn(this)
            Toast.makeText(this, "VPN Starting", Toast.LENGTH_SHORT).show()
        } else {
            // Permission denied
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingVpnStart = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VpnControlScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartVpn = { requestVpnStart() },
                        onStopVpn = { requestVpnStop() }
                    )
                }
            }
        }
    }

    /**
     * Requests VPN start. Handles permission request if needed.
     */
    private fun requestVpnStart() {
        if (pendingVpnStart) {
            return
        }

        // Check if VPN permission is needed
        val prepareIntent = VpnController.prepare(this)

        if (prepareIntent != null) {
            // Need to request permission
            pendingVpnStart = true
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // Permission already granted
            VpnController.startVpn(this)
            Toast.makeText(this, "VPN Starting", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Requests VPN stop.
     */
    private fun requestVpnStop() {
        VpnController.stopVpn(this)
        Toast.makeText(this, "VPN Stopping", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun VpnControlScreen(
    modifier: Modifier = Modifier,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Aegis VPN",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Text(
            text = "Phase 6: Decision Engine (Decision-Only, No Enforcement)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        Button(
            onClick = onStartVpn,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Start VPN")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onStopVpn,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Stop VPN")
        }

        Spacer(modifier = Modifier.height(48.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "• Decision engine active\n• Flows evaluated (ALLOW/BLOCK)\n• Decisions are metadata only\n• No enforcement (dropped)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

