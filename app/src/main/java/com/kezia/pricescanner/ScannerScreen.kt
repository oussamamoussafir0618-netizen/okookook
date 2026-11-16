@file:OptIn(ExperimentalMaterial3Api::class)

package com.kezia.pricescanner

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private class LastScan { var code = ""; var time = 0L }

@Composable
fun ScannerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dao = Graph.db.dao()

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var code by remember { mutableStateOf<String?>(null) }
    var product by remember { mutableStateOf<Product?>(null) }
    var torch by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val last = remember { LastScan() }
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
    val previewView = remember { PreviewView(ctx) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_QR_CODE
            ).build()
        )
    }

    val onCode: (String) -> Unit = { c ->
        val now = SystemClock.elapsedRealtime()
        val same = c == last.code && now - last.time < 2500
        last.code = c; last.time = now
        if (!same) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            scope.launch {
                msg = ""
                product = dao.get(barcodeVariants(c))
                code = c
            }
        }
    }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) {
                    proxy.close()
                } else {
                    val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(img)
                        .addOnSuccessListener { list -> list.firstOrNull { it.rawValue != null }?.rawValue?.let(onCode) }
                        .addOnCompleteListener { proxy.close() }
                }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdown(); tone.release() } }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    val cur = Graph.prefs.currency
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is required", color = Color.White)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        Box(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(140.dp)
            .border(2.dp, Color.Red.copy(alpha = 0.85f), RoundedCornerShape(8.dp)))
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("← Back") }
            Button(onClick = { torch = !torch }) { Text(if (torch) "Torch ON" else "Torch") }
        }
        Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = product
                val c = code
                when {
                    c == null -> Text("Point the camera at a barcode — scanning is automatic.")
                    p == null -> {
                        Text("Not in database: $c", fontWeight = FontWeight.Bold)
                        Button(onClick = { editing = true }) { Text("Add this product") }
                    }
                    else -> {
                        Text(p.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${fmtPrice(p.salePrice)} $cur", fontSize = 42.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            if (p.changed && p.oldPrice != null)
                                Text(fmtPrice(p.oldPrice), color = Color.Gray, textDecoration = TextDecoration.LineThrough)
                        }
                        Text(buildString {
                            append(p.barcode)
                            p.purchasePrice?.let { append("   •   Purchase: ${fmtPrice(it)} $cur") }
                            if (p.category.isNotEmpty()) append("   •   ${p.category}")
                        }, color = Color.Gray, fontSize = 12.sp)
                        if (p.changed && !p.printed) Text("Price changed — label not printed yet", color = Color(0xFFD84315))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = true }) { Text("Edit") }
                            Button(onClick = {
                                scope.launch {
                                    msg = "Printing…"
                                    msg = printProducts(ctx, listOf(p), markPrinted = p.changed && !p.printed)
                                    product = dao.get(barcodeVariants(p.barcode))
                                }
                            }) { Text("Print label") }
                        }
                    }
                }
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp)
            }
        }
    }

    if (editing) ProductDialog(product, code, { editing = false }, {
        editing = false
        scope.launch { code?.let { product = dao.get(barcodeVariants(it)) } }
    })
}
