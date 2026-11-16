@file:OptIn(ExperimentalMaterial3Api::class)

package com.kezia.pricescanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
@Composable
fun PrinterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = Graph.prefs
    var type by remember { mutableStateOf(prefs.printerType) }
    var addr by remember { mutableStateOf(prefs.printerAddr) }
    var pname by remember { mutableStateOf(prefs.printerName) }
    var ip by remember { mutableStateOf(if (prefs.printerType == "wifi") prefs.printerAddr else "") }
    val devices = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun adapter(): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    fun loadBonded() { devices.clear(); adapter()?.bondedDevices?.let { devices.addAll(it) } }
    fun startScan() {
        val ad = adapter()
        if (ad == null || !ad.isEnabled) { msg = "Turn Bluetooth on first"; return }
        loadBonded()
        ad.cancelDiscovery()
        scanning = ad.startDiscovery()
    }

    val perms = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { ok -> ok }) startScan() else msg = "Bluetooth permission denied"
    }
    fun hasPerms() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    LaunchedEffect(Unit) { if (hasPerms()) loadBonded() else launcher.launch(perms) }

    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = if (Build.VERSION.SDK_INT >= 33)
                            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (d != null && devices.none { it.address == d.address }) devices.add(d)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { adapter()?.cancelDiscovery() }; ctx.unregisterReceiver(r) }
    }

    Screen("Printer", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Selected printer", fontWeight = FontWeight.Bold)
                    Text(if (addr.isBlank()) "None" else "${if (type == "wifi") "Wi-Fi" else "Bluetooth"}: ${pname.ifBlank { addr }}  ($addr)")
                }
            }

            Text("Bluetooth (Zebra ZQ630)", style = MaterialTheme.typography.titleMedium)
            Text("Paired devices are listed first. Zebra printers are named like “XXZQJ…” or “ZQ630…”.", color = Color.Gray)
            Button(onClick = { if (hasPerms()) startScan() else launcher.launch(perms) }, enabled = !scanning) {
                Text(if (scanning) "Searching…" else "Search nearby printers")
            }
            devices.forEach { d ->
                val n = d.name ?: "Unknown"
                Card(Modifier.fillMaxWidth().clickable {
                    prefs.printerType = "bt"; prefs.printerAddr = d.address; prefs.printerName = n
                    type = "bt"; addr = d.address; pname = n; msg = "Printer saved: $n"
                }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(n, fontWeight = FontWeight.Medium)
                        Text(d.address + if (d.bondState == BluetoothDevice.BOND_BONDED) "  •  paired" else "", color = Color.Gray)
                    }
                }
            }

            HorizontalDivider()
            Text("Wi-Fi (raw port 9100)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(ip, { ip = it }, label = { Text("Printer IP address, e.g. 192.168.1.50") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                if (ip.isNotBlank()) {
                    prefs.printerType = "wifi"; prefs.printerAddr = ip.trim(); prefs.printerName = "Zebra Wi-Fi"
                    type = "wifi"; addr = ip.trim(); pname = "Zebra Wi-Fi"; msg = "Wi-Fi printer saved"
                }
            }) { Text("Use this Wi-Fi printer") }

            HorizontalDivider()
            Button(onClick = {
                scope.launch { msg = "Printing test label…"; msg = printProducts(ctx, listOf(SAMPLE), false) }
            }, enabled = addr.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Print test label (default template)") }
            if (msg.isNotEmpty()) Text(msg)
            Spacer(Modifier.height(24.dp))
        }
    }
}
