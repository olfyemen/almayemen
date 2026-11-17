package com.alma.toolbox.ledger

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.core.content.FileProvider
import com.alma.toolbox.ui.fmtDate
import com.alma.toolbox.ui.fmtNum
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object LedgerShare {

    fun lastAndBalanceText(d: LedgerData, a: LAccount): String {
        val last = d.txs.filter { it.accountId == a.id }.maxByOrNull { it.created }
        val bal = d.balance(a.id)
        return buildString {
            append("السلام عليكم ${a.name}\n")
            if (last != null) {
                append("آخر عملية بتاريخ ${fmtDate(last.date)}:\n")
                append(if (last.onHim) "عليك " else "لك ")
                append("${fmtNum(last.amount)} ${last.currency}")
                if (last.note.isNotBlank()) append(" — ${last.note}")
                append("\n")
            }
            append("\nالرصيد:\n")
            if (bal.isEmpty()) append("الحساب متزن ✓\n")
            else bal.forEach { (c, v) -> append("• ${balanceText(v, c, you = true)}\n") }
            if (d.owner.isNotBlank()) append("\n${d.owner}")
        }.trim()
    }

    // ---------------- PDF ----------------
    private const val W = 595
    private const val H = 842
    private const val M = 36f

    private fun tp(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** Draws text inside [left, left+width] with proper Arabic shaping & RTL. Returns height used. */
    private fun Canvas.cell(text: String, left: Float, top: Float, width: Float, paint: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, maxLines: Int = 2): Float {
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(align).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END)
            .setTextDirection(android.text.TextDirectionHeuristics.RTL).build()
        save(); translate(left, top); l.draw(this); restore()
        return l.height.toFloat()
    }

    fun buildPdf(ctx: Context, d: LedgerData, a: LAccount): File {
        val doc = PdfDocument()
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = 0f

        // columns from right to left: date, note, onHim, forHim, balance
        val inner = W - 2 * M
        val colW = floatArrayOf(70f, inner - 70f - 3 * 85f, 85f, 85f, 85f)
        val colX = FloatArray(5).also { var x = W - M; for (i in 0..4) { x -= colW[i]; it[i] = x } }
        val heads = listOf("التاريخ", "البيان", "عليه", "له", "الرصيد")
        val headPaint = tp(10f, true, Color.WHITE)
        val body = tp(9.5f)
        val center = Layout.Alignment.ALIGN_CENTER

        fun newPage() {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            y = M
            if (pageNo == 1) {
                c.cell("كشف حساب", M, y, inner, tp(20f, true, Color.rgb(42, 33, 96)), center); y += 30
                if (d.owner.isNotBlank()) { c.cell(d.owner, M, y, inner, tp(11f), center); y += 18 }
                c.cell("الاسم: ${a.name}", M, y, inner / 2, tp(11.5f, true))
                c.cell("الهاتف: ${a.phone.ifBlank { "—" }}", W / 2f, y, inner / 2 - 0f, tp(11f)); y += 18
                val now = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)) + " " +
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
                c.cell("تاريخ الكشف: $now", M, y, inner, tp(9.5f, color = Color.DKGRAY)); y += 22
            }
        }

        fun ensure(h: Float) { if (y + h > H - M - 20) { newPage() } }

        fun header() {
            ensure(22f)
            val bg = Paint().apply { color = Color.rgb(108, 92, 231) }
            c.drawRect(M, y, W - M, y + 20, bg)
            for (i in 0..4) c.cell(heads[i], colX[i], y + 4, colW[i], headPaint, center, 1)
            y += 20
        }

        newPage()
        val txs = d.txs.filter { it.accountId == a.id }.sortedWith(compareBy({ it.date }, { it.created }))
        val byCur = txs.groupBy { it.currency }
        if (byCur.isEmpty()) { c.cell("لا توجد عمليات", M, y + 20, inner, tp(12f), center) }
        val line = Paint().apply { color = Color.rgb(220, 222, 232); strokeWidth = 0.7f }
        val alt = Paint().apply { color = Color.rgb(246, 246, 252) }
        val red = Color.rgb(200, 30, 40); val green = Color.rgb(20, 130, 60)
        byCur.forEach { (cur, list) ->
            ensure(60f)
            y += 6
            c.cell("العملة: $cur", M, y, inner, tp(12f, true, Color.rgb(42, 33, 96))); y += 20
            header()
            var bal = 0.0; var sumOn = 0.0; var sumFor = 0.0
            list.forEachIndexed { idx, t ->
                bal += if (t.onHim) t.amount else -t.amount
                if (t.onHim) sumOn += t.amount else sumFor += t.amount
                val before = pageNo
                ensure(20f)
                if (pageNo != before) header() // repeat the table header on a new page
                if (idx % 2 == 1) c.drawRect(M, y, W - M, y + 18, alt)
                c.cell(fmtDate(t.date), colX[0], y + 4, colW[0], body, center, 1)
                c.cell(t.note.ifBlank { if (t.onHim) "عليه" else "له" }, colX[1] + 4, y + 4, colW[1] - 8, body, Layout.Alignment.ALIGN_NORMAL, 1)
                c.cell(if (t.onHim) fmtNum(t.amount) else "", colX[2], y + 4, colW[2], tp(9.5f, color = red), center, 1)
                c.cell(if (!t.onHim) fmtNum(t.amount) else "", colX[3], y + 4, colW[3], tp(9.5f, color = green), center, 1)
                c.cell(fmtNum(Math.abs(bal)) + if (bal > 0.0001) " عليه" else if (bal < -0.0001) " له" else "", colX[4], y + 4, colW[4], body, center, 1)
                y += 18
                c.drawLine(M, y, W - M, y, line)
            }
            ensure(40f)
            val tot = tp(10f, true)
            c.drawRect(M, y, W - M, y + 20, Paint().apply { color = Color.rgb(241, 239, 255) })
            c.cell("الإجمالي", colX[1], y + 4, colW[1], tot, center, 1)
            c.cell(fmtNum(sumOn), colX[2], y + 4, colW[2], tp(10f, true, red), center, 1)
            c.cell(fmtNum(sumFor), colX[3], y + 4, colW[3], tp(10f, true, green), center, 1)
            y += 24
            c.cell("الرصيد النهائي: " + balanceText(bal, cur), M, y, inner, tp(12f, true, if (bal > 0) red else if (bal < 0) green else Color.BLACK)); y += 26
        }
        // footer on last page
        c.cell("حقيبة الما 🧰", M, H - M, inner, tp(8f, color = Color.GRAY), center, 1)
        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val safe = a.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val f = File(dir, "كشف_${safe}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    // ---------------- sending ----------------
    private fun installed(ctx: Context, pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
    private fun waPackage(ctx: Context) = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(ctx, it) }

    fun sendTextWhatsApp(ctx: Context, phone: String, text: String) {
        val uri = Uri.parse("https://wa.me/$phone?text=" + Uri.encode(text))
        val i = Intent(Intent.ACTION_VIEW, uri)
        waPackage(ctx)?.let { i.setPackage(it) }
        start(ctx, i)
    }

    fun sendSms(ctx: Context, phone: String, text: String) {
        start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+$phone")).putExtra("sms_body", text))
    }

    fun shareText(ctx: Context, text: String) {
        start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "إرسال الحساب"))
    }

    fun sendPdf(ctx: Context, file: File, phone: String?, whatsapp: Boolean) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val wa = waPackage(ctx)
        if (whatsapp && wa != null) {
            i.setPackage(wa)
            if (!phone.isNullOrBlank()) i.putExtra("jid", "$phone@s.whatsapp.net")
            start(ctx, i)
        } else start(ctx, Intent.createChooser(i, "إرسال كشف الحساب"))
    }

    private fun start(ctx: Context, i: Intent) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: ActivityNotFoundException) { Toast.makeText(ctx, "لا يوجد تطبيق مناسب للإرسال", Toast.LENGTH_LONG).show() }
    }
}
