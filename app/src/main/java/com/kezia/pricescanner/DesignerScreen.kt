@file:OptIn(ExperimentalMaterial3Api::class)

package com.kezia.pricescanner

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val swatches = listOf(Color.White, Color.Black, Color(0xFFFFEB3B), Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047))
private val elLabels = listOf("STORE" to "Store", "NAME" to "Name", "PRICE" to "Price", "UNIT" to "Unit", "BARCODE" to "Barcode")

@Composable
fun NumField(label: String, v: Float, modifier: Modifier, onV: (Float) -> Unit) {
    var t by remember(v) { mutableStateOf(if (v % 1f == 0f) v.toInt().toString() else v.toString()) }
    OutlinedTextField(t, {
        t = it
        it.replace(',', '.').toFloatOrNull()?.takeIf { x -> x > 5f }?.let(onV)
    }, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
fun Swatches(label: String, cur: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(100.dp))
        swatches.forEach { c ->
            val on = c.toArgb() == cur
            Box(Modifier.padding(4.dp).size(34.dp).background(c, CircleShape)
                .border(if (on) 3.dp else 1.dp, if (on) Color(0xFF0B57D0) else Color.Gray, CircleShape)
                .clickable { onPick(c.toArgb()) })
        }
    }
}

@Composable
fun ElementView(e: El, t: Template, p: Product, s: Float) {
    val fg = Color(t.fg)
    val fontSp = with(LocalDensity.current) { (e.size * s * 0.95f).dp.toSp() }
    if (e.type == "BARCODE") {
        val wMm = 190f / (Graph.prefs.dpi / 25.4f)
        Canvas(Modifier.size((wMm * s).dp, (e.size * s).dp)) {
            val n = 60
            val bw = size.width / n
            var seed = p.barcode.hashCode()
            for (i in 0 until n) {
                seed = seed * 1103515245 + 12345
                if (((seed shr 16) and 1) == 1) drawRect(fg, Offset(i * bw, 0f), Size(bw, size.height * 0.85f))
            }
        }
    } else {
        val wide = e.type == "NAME" || e.type == "STORE"
        Text(
            Zpl.textFor(e.type, p, t), color = fg, fontSize = fontSp, lineHeight = fontSp,
            maxLines = if (wide) 2 else 1,
            fontWeight = if (e.type == "PRICE") FontWeight.Bold else FontWeight.Normal,
            modifier = if (wide) Modifier.width(((t.wMm - e.x - 1f).coerceAtLeast(1f) * s).dp) else Modifier
        )
    }
}

@Composable
fun DesignerScreen(onBack: () -> Unit) {
    val prefs = Graph.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf(prefs.templates()) }
    var cur by remember { mutableStateOf(prefs.defaultTemplate()) }
    var orig by remember { mutableStateOf(cur.name) }
    var defName by remember { mutableStateOf(prefs.defaultTemplate().name) }
    var sel by remember { mutableStateOf("PRICE") }
    var msg by remember { mutableStateOf("") }

    fun upd(type: String, f: (El) -> El) { cur = cur.copy(els = cur.els.map { if (it.type == type) f(it) else it }) }
    fun save() {
        templates = templates.filter { it.name != orig && it.name != cur.name } + cur
        orig = cur.name
        prefs.saveTemplates(templates)
    }

    Screen("Price Label Designer", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                templates.forEach {
                    FilterChip(it.name == orig, { cur = it; orig = it.name },
                        label = { Text(it.name + if (it.name == defName) " ★" else "") })
                }
            }

            Text("Preview — drag elements to move them", color = Color.Gray, fontSize = 12.sp)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val s = (maxWidth.value - 4f) / cur.wMm      // dp per mm
                val dens = LocalDensity.current.density
                Box(Modifier.width((cur.wMm * s).dp).height((cur.hMm * s).dp)
                    .background(Color(cur.bg)).border(1.dp, Color.Gray)) {
                    cur.els.filter { it.visible }.forEach { e ->
                        key(e.type) {
                            Box(Modifier
                                .offset { IntOffset((e.x * s * dens).roundToInt(), (e.y * s * dens).roundToInt()) }
                                .then(if (sel == e.type) Modifier.border(1.dp, Color(0xFF0B57D0)) else Modifier)
                                .pointerInput(e.type, s) {
                                    detectDragGestures(onDragStart = { sel = e.type }) { change, drag ->
                                        change.consume()
                                        val dx = drag.x / dens / s
                                        val dy = drag.y / dens / s
                                        upd(e.type) {
                                            it.copy(x = (it.x + dx).coerceIn(0f, cur.wMm - 1f),
                                                y = (it.y + dy).coerceIn(0f, cur.hMm - 1f))
                                        }
                                    }
                                }) { ElementView(e, cur, SAMPLE, s) }
                        }
                    }
                }
            }

            OutlinedTextField(cur.name, { cur = cur.copy(name = it) }, label = { Text("Template name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Width (mm)", cur.wMm, Modifier.weight(1f)) { cur = cur.copy(wMm = it) }
                NumField("Height (mm)", cur.hMm, Modifier.weight(1f)) { cur = cur.copy(hMm = it) }
            }
            Swatches("Background", cur.bg) { cur = cur.copy(bg = it) }
            Swatches("Text", cur.fg) { cur = cur.copy(fg = it) }
            Text("Note: the thermal printer prints black & white (dark background = reversed).", color = Color.Gray, fontSize = 12.sp)

            OutlinedTextField(cur.store, { cur = cur.copy(store = it) }, label = { Text("Store name / logo text") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cur.unit, { cur = cur.copy(unit = it) }, label = { Text("Unit (e.g. €/kg, €/pièce)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("€/kg", "€/pièce", "€/L", "").forEach { u ->
                    AssistChip(onClick = { cur = cur.copy(unit = u) }, label = { Text(u.ifEmpty { "none" }) })
                }
            }

            Text("Elements", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                elLabels.forEach { (k, l) -> FilterChip(sel == k, { sel = k }, label = { Text(l) }) }
            }
            cur.els.firstOrNull { it.type == sel }?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Visible", Modifier.weight(1f))
                    Switch(e.visible, { v -> upd(sel) { it.copy(visible = v) } })
                }
                Text(if (sel == "BARCODE") "Bar height: ${"%.1f".format(e.size)} mm" else "Font size: ${"%.1f".format(e.size)} mm")
                Slider(e.size, { v -> upd(sel) { it.copy(size = v) } }, valueRange = 2f..25f)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(); msg = "Template saved" }) { Text("Save") }
                OutlinedButton(onClick = { save(); prefs.defaultName = cur.name; defName = cur.name; msg = "Default template set" }) { Text("Set default") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    cur = Template.default().copy(name = "Template ${templates.size + 1}"); orig = ""
                }) { Text("New") }
                OutlinedButton(onClick = {
                    if (templates.size > 1) {
                        templates = templates.filter { it.name != orig }
                        prefs.saveTemplates(templates)
                        cur = templates.first(); orig = cur.name
                        if (prefs.defaultName == "" || templates.none { it.name == prefs.defaultName }) { prefs.defaultName = cur.name; defName = cur.name }
                    } else msg = "Keep at least one template"
                }) { Text("Delete") }
                OutlinedButton(onClick = {
                    scope.launch { msg = "Printing…"; msg = printProducts(ctx, listOf(SAMPLE), false, cur) }
                }) { Text("Print test") }
            }
            if (msg.isNotEmpty()) Text(msg)
            Spacer(Modifier.height(24.dp))
        }
    }
}
