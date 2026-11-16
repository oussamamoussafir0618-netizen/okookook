@file:OptIn(ExperimentalMaterial3Api::class)

package com.kezia.pricescanner

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() { super.onCreate(); Graph.init(this) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0B57D0))) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) { Root() }
            }
        }
    }
}

@Composable
fun Root() {
    var screen by remember { mutableStateOf("home") }
    BackHandler(screen != "home") { screen = "home" }
    val back = { screen = "home" }
    when (screen) {
        "home" -> Home { screen = it }
        "scanner" -> ScannerScreen(back)
        "products" -> ProductsScreen(back)
        "database" -> DatabaseScreen(back)
        "designer" -> DesignerScreen(back)
        "printer" -> PrinterScreen(back)
        "settings" -> SettingsScreen(back)
    }
}

@Composable
fun Screen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) },
            navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 24.sp) } })
    }) { pad -> Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), content = content) }
}

@Composable
fun Home(nav: (String) -> Unit) {
    val dao = Graph.db.dao()
    val count by dao.count().collectAsState(0)
    val pending by dao.pending().collectAsState(emptyList())
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Kezia Price Scanner", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("$count products  •  ${pending.size} price changes to print", color = Color.Gray)
        Button(onClick = { nav("scanner") }, modifier = Modifier.fillMaxWidth().height(170.dp)) {
            Text("📷  SCANNER", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        @Composable fun Tile(label: String, to: String, m: Modifier) =
            OutlinedButton(onClick = { nav(to) }, modifier = m.height(84.dp)) { Text(label, fontSize = 16.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Products" + if (pending.isNotEmpty()) " (${pending.size})" else "", "products", Modifier.weight(1f))
            Tile("Database", "database", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Label Designer", "designer", Modifier.weight(1f))
            Tile("Printer", "printer", Modifier.weight(1f))
        }
        Tile("Settings", "settings", Modifier.fillMaxWidth())
    }
}

// ───────────────────────── Database screen ─────────────────────────

@Composable
fun DatabaseScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = Graph.db.dao()
    val count by dao.count().collectAsState(0)
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun report(block: suspend () -> ImportResult) {
        busy = true
        scope.launch {
            status = runCatching { block() }.fold(
                { "Rows read: ${it.rows}\nAdded: ${it.added}\nUpdated: ${it.updated}\nPrice changed: ${it.changed}\nSkipped (no barcode/price): ${it.skipped}" },
                { "Error: ${it.message}" })
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) report { Importer.import(ctx, uri) }
    }

    Screen("Database", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Products stored on this phone: $count", style = MaterialTheme.typography.titleMedium)
            Text("Expected columns:\nBarcode | Product Name | Purchase Price | Sale Price | Category", color = Color.Gray)
            Text("Importing again updates existing products by barcode. Products whose Sale Price changed are flagged for label printing.")
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Import Excel (.xlsx) or CSV")
            }
            OutlinedButton(onClick = { report { Importer.importAsset(ctx, "sample_products.xlsx") } }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Load built-in sample (30 products)") }
            OutlinedButton(onClick = { report { Importer.importAsset(ctx, "sample_products_v2.xlsx") } }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Load sample update (changed prices)") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Card(Modifier.fillMaxWidth()) { Text(status, Modifier.padding(12.dp)) }
            TextButton(onClick = { confirmClear = true }) { Text("Delete all products", color = Color(0xFFB3261E)) }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete all products?") },
        confirmButton = { TextButton(onClick = { scope.launch { dao.clear() }; confirmClear = false; status = "" }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

// ───────────────────────── Products screen ─────────────────────────

@Composable
fun ProductDialog(initial: Product?, barcode: String?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var b by remember { mutableStateOf(initial?.barcode ?: barcode ?: "") }
    var n by remember { mutableStateOf(initial?.name ?: "") }
    var pp by remember { mutableStateOf(initial?.purchasePrice?.let { fmtPrice(it, false) } ?: "") }
    var sp by remember { mutableStateOf(initial?.salePrice?.let { fmtPrice(it, false) } ?: "") }
    var c by remember { mutableStateOf(initial?.category ?: "") }
    var err by remember { mutableStateOf("") }
    val kb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add product" else "Edit product") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(b, { b = it }, label = { Text("Barcode") }, singleLine = true, enabled = initial == null)
                OutlinedTextField(n, { n = it }, label = { Text("Product Name") })
                OutlinedTextField(sp, { sp = it }, label = { Text("Sale Price") }, singleLine = true, keyboardOptions = kb)
                OutlinedTextField(pp, { pp = it }, label = { Text("Purchase Price") }, singleLine = true, keyboardOptions = kb)
                OutlinedTextField(c, { c = it }, label = { Text("Category") }, singleLine = true)
                if (err.isNotEmpty()) Text(err, color = Color(0xFFB3261E))
            }
        },
        confirmButton = {
            Button(onClick = {
                val sale = parsePrice(sp)
                if (b.isBlank() || n.isBlank() || sale == null) {
                    err = "Barcode, name and sale price are required"
                } else scope.launch {
                    Repo.save(Product(b.trim(), n.trim(), parsePrice(pp), sale, c.trim()))
                    onSaved()
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = {
                    scope.launch { Graph.db.dao().delete(initial); onSaved() }
                }) { Text("Delete", color = Color(0xFFB3261E)) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        })
}

@Composable
fun ProductsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = Graph.db.dao()
    var q by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    val all by remember(q) { dao.search(q) }.collectAsState(emptyList())
    val pending by dao.pending().collectAsState(emptyList())
    val list = if (tab == 0) all else pending
    var selected by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmPrint by remember { mutableStateOf(false) }
    val cur = Graph.prefs.currency

    LaunchedEffect(tab, pending) { selected = if (tab == 1) pending.map { it.barcode }.toSet() else emptySet() }
    val toPrint = list.filter { it.barcode in selected }

    Screen("Products", onBack) {
        OutlinedTextField(q, { q = it }, label = { Text("Search by name or barcode") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("All") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Price changes (${pending.size})") })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) { Text("+ Add") }
            if (tab == 1) OutlinedButton(onClick = {
                selected = if (selected.size == pending.size) emptySet() else pending.map { it.barcode }.toSet()
            }) { Text("All / none") }
            Button(onClick = { confirmPrint = true }, enabled = toPrint.isNotEmpty()) { Text("Print (${toPrint.size})") }
        }
        if (tab == 1 && pending.isEmpty()) Text("No price changes waiting to be printed.", color = Color.Gray)
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.barcode }) { p ->
                Row(Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.barcode in selected, { on -> selected = if (on) selected + p.barcode else selected - p.barcode })
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Medium, maxLines = 2)
                        Text(p.barcode + if (p.category.isNotEmpty()) "  •  ${p.category}" else "", color = Color.Gray, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${fmtPrice(p.salePrice)} $cur", fontWeight = FontWeight.Bold,
                            color = if (p.changed && !p.printed) Color(0xFFD84315) else Color.Unspecified)
                        if (p.changed && p.oldPrice != null)
                            Text(fmtPrice(p.oldPrice), color = Color.Gray, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (adding) ProductDialog(null, null, { adding = false }, { adding = false })
    editing?.let { ProductDialog(it, null, { editing = null }, { editing = null }) }
    if (confirmPrint) AlertDialog(
        onDismissRequest = { confirmPrint = false },
        title = { Text("Print ${toPrint.size} label(s)?") },
        text = { Text(toPrint.take(8).joinToString("\n") { "• ${it.name}  ${fmtPrice(it.salePrice)} $cur" } +
                if (toPrint.size > 8) "\n… +${toPrint.size - 8}" else "") },
        confirmButton = {
            Button(onClick = {
                confirmPrint = false
                scope.launch {
                    val m = printProducts(ctx, toPrint, markPrinted = true)
                    android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text("Print") }
        },
        dismissButton = { TextButton(onClick = { confirmPrint = false }) { Text("Cancel") } })
}

// ───────────────────────── Settings ─────────────────────────

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val p = Graph.prefs
    var cur by remember { mutableStateOf(p.currency) }
    var comma by remember { mutableStateOf(p.comma) }
    var dpi by remember { mutableIntStateOf(p.dpi) }
    var copies by remember { mutableStateOf(p.copies.toString()) }
    Screen("Settings", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(cur, { cur = it; p.currency = it }, label = { Text("Currency symbol") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Decimal comma (1,99)", Modifier.weight(1f))
                Switch(comma, { comma = it; p.comma = it })
            }
            Text("Printer resolution (Zebra ZQ630 = 203 dpi)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(203, 300).forEach { d ->
                    FilterChip(dpi == d, { dpi = d; p.dpi = d }, label = { Text("$d dpi") })
                }
            }
            OutlinedTextField(copies, { copies = it; it.toIntOrNull()?.let { n -> p.copies = n.coerceIn(1, 99) } },
                label = { Text("Copies per label") }, singleLine = true)
            Text("Kezia Price Scanner 1.0\nWorks fully offline.", color = Color.Gray)
        }
    }
}
