package com.kezia.pricescanner

import android.content.Context
import android.net.Uri
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.Locale
import java.util.zip.ZipInputStream

// ───────────────────────── Database ─────────────────────────

@Entity(tableName = "products")
data class Product(
    @PrimaryKey val barcode: String,
    val name: String,
    val purchasePrice: Double?,
    val salePrice: Double,
    val category: String = "",
    val oldPrice: Double? = null,
    val changed: Boolean = false,   // sale price changed since last import / edit
    val printed: Boolean = false    // label for the new price already printed
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE barcode IN (:codes) LIMIT 1")
    suspend fun get(codes: List<String>): Product?

    @Query("SELECT * FROM products WHERE name LIKE '%' || :q || '%' OR barcode LIKE '%' || :q || '%' ORDER BY name LIMIT 500")
    fun search(q: String): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE changed = 1 AND printed = 0 ORDER BY name")
    fun pending(): Flow<List<Product>>

    @Query("SELECT COUNT(*) FROM products")
    fun count(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: Product)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(l: List<Product>)

    @Delete
    suspend fun delete(p: Product)

    @Query("UPDATE products SET printed = 1 WHERE barcode IN (:codes)")
    suspend fun markPrinted(codes: List<String>)

    @Query("DELETE FROM products")
    suspend fun clear()
}

@Database(entities = [Product::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ProductDao
}

object Graph {
    lateinit var db: AppDb
    lateinit var prefs: Prefs
    fun init(c: Context) {
        db = Room.databaseBuilder(c.applicationContext, AppDb::class.java, "kezia.db").build()
        prefs = Prefs(c.applicationContext)
    }
}

val SAMPLE = Product("3017620422003", "Nutella 400 g", null, 2.99, "Epicerie")

/** EAN-13 / UPC-A / leading-zero variants so that a scanned code matches what Excel stored. */
fun barcodeVariants(code: String): List<String> {
    val c = code.trim()
    val s = c.trimStart('0')
    return listOf(c, s, s.padStart(8, '0'), s.padStart(12, '0'), s.padStart(13, '0')).distinct()
}

fun parsePrice(s: String): Double? {
    var t = s.replace(Regex("[^0-9,.-]"), "")
    t = if (t.contains(',') && t.contains('.')) t.replace(",", "") else t.replace(',', '.')
    return t.toDoubleOrNull()
}

fun fmtPrice(v: Double, comma: Boolean = Graph.prefs.comma): String {
    val s = String.format(Locale.US, "%.2f", v)
    return if (comma) s.replace('.', ',') else s
}

object Repo {
    /** Keeps price-change tracking consistent for manual edits and imports. */
    fun merge(old: Product?, n: Product): Product {
        if (old == null) return n.copy(oldPrice = null, changed = false, printed = false)
        return if (old.salePrice != n.salePrice)
            n.copy(barcode = old.barcode, oldPrice = old.salePrice, changed = true, printed = false)
        else
            n.copy(barcode = old.barcode, oldPrice = old.oldPrice, changed = old.changed, printed = old.printed)
    }

    suspend fun save(n: Product) {
        val dao = Graph.db.dao()
        dao.upsert(merge(dao.get(barcodeVariants(n.barcode)), n))
    }
}

// ───────────────────────── Preferences & label templates ─────────────────────────

data class El(val type: String, val x: Float, val y: Float, val size: Float, val visible: Boolean = true)

data class Template(
    val name: String, val wMm: Float, val hMm: Float,
    val bg: Int, val fg: Int, val store: String, val unit: String, val els: List<El>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("w", wMm.toDouble()).put("h", hMm.toDouble())
        .put("bg", bg).put("fg", fg).put("store", store).put("unit", unit)
        .put("els", JSONArray(els.map {
            JSONObject().put("t", it.type).put("x", it.x.toDouble()).put("y", it.y.toDouble())
                .put("s", it.size.toDouble()).put("v", it.visible)
        }))

    companion object {
        fun default() = Template(
            "Standard 50x30", 50f, 30f, -1, 0xFF000000.toInt(), "MY STORE", "€/pièce",
            listOf(
                El("STORE", 2f, 1.5f, 3.5f),
                El("NAME", 2f, 6f, 4f),
                El("PRICE", 2f, 11f, 9f),
                El("UNIT", 34f, 20f, 3.5f),
                El("BARCODE", 2f, 21f, 7f)
            )
        )

        fun fromJson(o: JSONObject): Template {
            val a = o.getJSONArray("els")
            return Template(
                o.getString("name"), o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
                o.getInt("bg"), o.getInt("fg"), o.optString("store"), o.optString("unit"),
                (0 until a.length()).map {
                    val e = a.getJSONObject(it)
                    El(e.getString("t"), e.getDouble("x").toFloat(), e.getDouble("y").toFloat(),
                        e.getDouble("s").toFloat(), e.optBoolean("v", true))
                }
            )
        }
    }
}

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("kezia", Context.MODE_PRIVATE)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d

    var currency: String get() = s("currency", "€"); set(v) { sp.edit().putString("currency", v).apply() }
    var comma: Boolean get() = sp.getBoolean("comma", true); set(v) { sp.edit().putBoolean("comma", v).apply() }
    var dpi: Int get() = sp.getInt("dpi", 203); set(v) { sp.edit().putInt("dpi", v).apply() }
    var copies: Int get() = sp.getInt("copies", 1); set(v) { sp.edit().putInt("copies", v).apply() }
    var printerType: String get() = s("ptype", "bt"); set(v) { sp.edit().putString("ptype", v).apply() }
    var printerAddr: String get() = s("paddr", ""); set(v) { sp.edit().putString("paddr", v).apply() }
    var printerName: String get() = s("pname", ""); set(v) { sp.edit().putString("pname", v).apply() }
    var defaultName: String get() = s("defTpl", ""); set(v) { sp.edit().putString("defTpl", v).apply() }

    fun templates(): List<Template> {
        val raw = sp.getString("templates", null) ?: return listOf(Template.default())
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { Template.fromJson(a.getJSONObject(it)) }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(Template.default())
    }

    fun saveTemplates(l: List<Template>) {
        sp.edit().putString("templates", JSONArray(l.map { it.toJson() }).toString()).apply()
    }

    fun defaultTemplate(): Template {
        val l = templates()
        return l.firstOrNull { it.name == defaultName } ?: l.first()
    }
}

// ───────────────────────── XLSX / CSV reading (no external library) ─────────────────────────

object XlsxReader {
    private fun parser(b: ByteArray): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply { setInput(ByteArrayInputStream(b), null) }

    fun read(bytes: ByteArray): List<List<String>> {
        val shared = ArrayList<String>()
        val sheets = sortedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name
                if (n == "xl/sharedStrings.xml") parseShared(z.readBytes(), shared)
                else if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) sheets[n] = z.readBytes()
                e = z.nextEntry
            }
        }
        val sheet = sheets.entries.firstOrNull()?.value ?: return emptyList()
        return parseSheet(sheet, shared)
    }

    private fun parseShared(b: ByteArray, out: MutableList<String>) {
        val p = parser(b)
        var ev = p.eventType
        var sb = StringBuilder()
        var inT = false
        var inPh = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> inPh = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT && !inPh) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> inPh = false
                    "si" -> out.add(sb.toString())
                }
            }
            ev = p.next()
        }
    }

    private fun colIndex(ref: String?): Int {
        if (ref == null) return -1
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return n - 1
    }

    private fun num(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val bd = BigDecimal(raw.trim())
            if (bd.scale() <= 0) bd.toBigInteger().toString() else bd.stripTrailingZeros().toPlainString()
        }.getOrDefault(raw)
    }

    private fun parseSheet(b: ByteArray, shared: List<String>): List<List<String>> {
        val p = parser(b)
        val rows = ArrayList<List<String>>()
        var row: MutableList<String>? = null
        var col = 0
        var type = ""
        var inV = false
        var inT = false
        var cur = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        type = p.getAttributeValue(null, "t") ?: ""
                        val ci = colIndex(p.getAttributeValue(null, "r"))
                        col = if (ci >= 0) ci else (row?.size ?: 0)
                        cur = StringBuilder()
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) cur.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = cur.toString()
                        val value = when (type) {
                            "s" -> shared.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: ""
                            "inlineStr", "str", "b" -> raw
                            "e" -> ""
                            else -> num(raw)
                        }
                        val r = row
                        if (r != null) {
                            while (r.size < col) r.add("")
                            if (r.size == col) r.add(value) else r[col] = value
                        }
                    }
                    "row" -> { row?.let { rows.add(it) }; row = null }
                }
            }
            ev = p.next()
        }
        return rows
    }
}

object CsvReader {
    fun read(bytes: ByteArray): List<List<String>> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val first = text.lineSequence().firstOrNull() ?: ""
        val d = listOf(';', ',', '\t').maxByOrNull { c -> first.count { it == c } } ?: ';'
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (q) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else q = false
                } else sb.append(ch)
            } else when (ch) {
                '"' -> q = true
                d -> { row.add(sb.toString()); sb.setLength(0) }
                '\n' -> { row.add(sb.toString()); sb.setLength(0); rows.add(row); row = ArrayList() }
                '\r' -> {}
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows
    }
}

data class ImportResult(val rows: Int, val added: Int, val updated: Int, val changed: Int, val skipped: Int)

object Importer {
    suspend fun import(ctx: Context, uri: Uri): ImportResult {
        val bytes = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open file")
        }
        return importBytes(bytes)
    }

    suspend fun importAsset(ctx: Context, name: String): ImportResult {
        val bytes = withContext(Dispatchers.IO) { ctx.assets.open(name).use { it.readBytes() } }
        return importBytes(bytes)
    }

    suspend fun importBytes(bytes: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        val isZip = bytes.size > 3 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        if (bytes.size > 3 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte())
            error("Old .xls format is not supported. Save the file as .xlsx or .csv")
        val rows = if (isZip) XlsxReader.read(bytes) else CsvReader.read(bytes)
        if (rows.isEmpty()) return@withContext ImportResult(0, 0, 0, 0, 0)

        val head = rows[0].map { it.trim().lowercase() }
        fun find(vararg k: String) = head.indexOfFirst { h -> k.any { h == it } }
        var iB = find("barcode", "code", "ean", "code-barres", "code barre", "codebarre")
        val hasHeader = iB >= 0
        var iN = find("product name", "name", "produit", "designation", "désignation", "nom")
        var iP = find("purchase price", "purchase", "cost", "prix achat", "prix d'achat")
        var iS = find("sale price", "price", "prix vente", "prix de vente", "prix")
        var iC = find("category", "catégorie", "categorie")
        if (!hasHeader) { iB = 0; iN = 1; iP = 2; iS = 3; iC = 4 }

        val dao = Graph.db.dao()
        var added = 0; var updated = 0; var changed = 0; var skipped = 0
        val batch = ArrayList<Product>()
        for (r in rows.drop(if (hasHeader) 1 else 0)) {
            fun cell(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
            val code = cell(iB)
            val sale = parsePrice(cell(iS))
            if (code.isEmpty() || sale == null) { skipped++; continue }
            val n = Product(code, cell(iN).ifEmpty { code }, parsePrice(cell(iP)), sale, cell(iC))
            val old = dao.get(barcodeVariants(code))
            if (old == null) added++ else { updated++; if (old.salePrice != sale) changed++ }
            batch.add(Repo.merge(old, n))
            if (batch.size >= 500) { dao.upsertAll(batch); batch.clear() }
        }
        if (batch.isNotEmpty()) dao.upsertAll(batch)
        ImportResult(rows.size - (if (hasHeader) 1 else 0), added, updated, changed, skipped)
    }
}
