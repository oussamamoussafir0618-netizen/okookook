package com.kezia.pricescanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.math.roundToInt

object Zpl {
    private fun lum(c: Int) = 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)

    fun textFor(type: String, p: Product, t: Template): String = when (type) {
        "STORE" -> t.store
        "NAME" -> p.name
        "PRICE" -> fmtPrice(p.salePrice) + " " + Graph.prefs.currency
        "UNIT" -> t.unit
        else -> p.barcode
    }

    private fun esc(s: String) = s.replace('^', ' ').replace('~', ' ').replace('\n', ' ')

    fun label(p: Product, t: Template, dpi: Int, copies: Int): String {
        val dm = dpi / 25.4f
        fun d(mm: Float) = (mm * dm).roundToInt()
        val w = d(t.wMm)
        val h = d(t.hMm)
        // thermal printers are black & white: a dark background is printed as reversed (white on black)
        val dark = lum(t.bg) < lum(t.fg)
        val fr = if (dark) "^FR" else ""
        val sb = StringBuilder("^XA^CI28^PW$w^LL$h^LH0,0\n")
        if (dark) sb.append("^FO0,0^GB$w,$h,$h^FS\n")
        for (e in t.els) {
            if (!e.visible) continue
            val x = d(e.x)
            val y = d(e.y)
            when (e.type) {
                "BARCODE" -> {
                    val code = p.barcode
                    val cmd = if (code.length == 13 && code.all { it.isDigit() }) "^BEN,${d(e.size)},Y,N"
                    else "^BCN,${d(e.size)},Y,N,N"
                    sb.append("^FO$x,$y^BY2$fr$cmd^FD$code^FS\n")
                }
                else -> {
                    val fs = d(e.size)
                    val box = if (e.type == "NAME" || e.type == "STORE")
                        "^FB${maxOf(d(t.wMm - e.x - 1f), 10)},2,0,L" else ""
                    sb.append("^FO$x,$y^A0N,$fs,$fs$box$fr^FD${esc(textFor(e.type, p, t))}^FS\n")
                }
            }
        }
        sb.append("^PQ${copies.coerceAtLeast(1)}^XZ\n")
        return sb.toString()
    }
}

object Printer {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    suspend fun send(ctx: Context, zpl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val p = Graph.prefs
            if (p.printerAddr.isBlank()) error("No printer selected. Open the Printer screen first.")
            val bytes = zpl.toByteArray(Charsets.UTF_8)
            if (p.printerType == "wifi") {
                Socket().use { s ->
                    s.connect(InetSocketAddress(p.printerAddr, 9100), 5000)
                    s.getOutputStream().apply { write(bytes); flush() }
                    Thread.sleep(300)
                }
            } else {
                val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: error("Bluetooth is not available")
                if (!ad.isEnabled) error("Bluetooth is turned off")
                ad.cancelDiscovery()
                val sock = ad.getRemoteDevice(p.printerAddr).createRfcommSocketToServiceRecord(SPP)
                sock.use {
                    it.connect()
                    it.outputStream.apply {
                        var i = 0
                        while (i < bytes.size) {            // small chunks: Zebra BT buffers are small
                            val n = minOf(1024, bytes.size - i)
                            write(bytes, i, n); i += n
                        }
                        flush()
                    }
                    Thread.sleep(800)
                }
            }
        }
    }
}

/** Prints labels (duplicates removed) and returns a human readable status. */
suspend fun printProducts(
    ctx: Context, items: List<Product>, markPrinted: Boolean,
    t: Template = Graph.prefs.defaultTemplate()
): String {
    val list = items.distinctBy { it.barcode }
    if (list.isEmpty()) return "Nothing to print"
    val prefs = Graph.prefs
    val zpl = list.joinToString("") { Zpl.label(it, t, prefs.dpi, prefs.copies) }
    return Printer.send(ctx, zpl).fold({
        if (markPrinted) Graph.db.dao().markPrinted(list.map { it.barcode })
        "Printed ${list.size} label(s)"
    }, { "Print error: ${it.message}" })
}
