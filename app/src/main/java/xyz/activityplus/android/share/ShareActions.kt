package xyz.activityplus.android.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import xyz.activityplus.android.R
import xyz.activityplus.android.core.ShareReport
import java.io.File

/**
 * Hands the report to Android's share sheet, as text or as an image card. Nothing is sent by
 * Activity+ itself: the user picks the app, and the sheet lets them edit the text first.
 */
object ShareActions {
    /** Plain text; [text] is what the user left in the editor. False when no app can take it. */
    fun shareText(context: Context, title: String, text: String): Boolean = chooser(
        context,
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        },
    )

    /**
     * The summary as a PNG, through a content:// URI of our own FileProvider so the receiving app
     * can read exactly this one file from the cache. Blocking (draws and writes); call off the main thread.
     */
    fun imageIntent(context: Context, report: ReportBuilder.Report): Intent {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        // One file that is overwritten: the cache never fills with old reports.
        val file = File(dir, "activityplus-report.png")
        val bitmap = renderCard(context, report)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, report.title)
            // The chooser grants read access through the clip, and shows the preview.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareImage(context: Context, intent: Intent): Boolean = chooser(context, intent)

    private fun chooser(context: Context, send: Intent): Boolean = try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_action)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private const val SHARE_DIR = "share"

    // The card follows the app's dark theme: the same background, card and accent colours.
    private const val BACKGROUND = 0xFF0E1024.toInt()
    private const val CARD = 0xFF171A33.toInt()
    private const val TEXT = 0xFFF2F3FA.toInt()
    private const val MUTED = 0xFF9AA0BC.toInt()
    private const val ACCENT = 0xFF26A862.toInt()
    private const val BRAND_START = 0xFF3E8BFF.toInt()
    private const val BRAND_END = 0xFF9B5CF6.toInt()

    private const val WIDTH = 1080
    private const val MARGIN = 40f
    private const val PAD = 48f
    private const val HEAD_H = 150f
    private const val SECTION_H = 100f
    private const val ROW_H = 62f

    /** The report's lines drawn on a dark card, tall enough for however many there are. */
    fun renderCard(context: Context, report: ReportBuilder.Report): Bitmap {
        val lines = ShareReport.lines(report.sections)
        val height = (2 * MARGIN + 2 * PAD + HEAD_H + lines.sumOf { (if (it.heading) SECTION_H else ROW_H).toDouble() } + 20).toInt()
        val bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BACKGROUND)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
        c.drawRoundRect(RectF(MARGIN, MARGIN, WIDTH - MARGIN, height - MARGIN), 48f, 48f, fill)

        val left = MARGIN + PAD
        val right = WIDTH - MARGIN - PAD
        val width = right - left
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val bold = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)

        var y = MARGIN + PAD + 52f
        text.typeface = bold; text.textSize = 56f; text.color = TEXT
        c.drawText(TextUtils.ellipsize(report.title, text, width, TextUtils.TruncateAt.END).toString(), left, y, text)
        y += 52f
        text.typeface = medium; text.textSize = 34f; text.color = MUTED
        c.drawText(TextUtils.ellipsize(report.subtitle, text, width, TextUtils.TruncateAt.END).toString(), left, y, text)
        y += 24f
        // The brand gradient as a thin rule under the header.
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(left, 0f, right, 0f, BRAND_START, BRAND_END, Shader.TileMode.CLAMP) }
        c.drawRoundRect(RectF(left, y, right, y + 8f), 4f, 4f, rule)
        y += 8f

        for (line in lines) {
            if (line.heading) {
                y += SECTION_H - 20f
                text.typeface = bold; text.textSize = 40f; text.color = ACCENT; text.textAlign = Paint.Align.LEFT
                c.drawText(line.left, left, y, text)
                y += 20f
                continue
            }
            y += ROW_H
            text.typeface = medium; text.textSize = 36f
            if (line.left.isBlank()) {
                text.color = TEXT; text.textAlign = Paint.Align.LEFT
                c.drawText(TextUtils.ellipsize(line.right, text, width, TextUtils.TruncateAt.END).toString(), left, y, text)
                continue
            }
            // The value is the part people read; the label gives way when both do not fit.
            text.color = TEXT; text.textAlign = Paint.Align.RIGHT
            val value = TextUtils.ellipsize(line.right, text, width * 0.62f, TextUtils.TruncateAt.END).toString()
            c.drawText(value, right, y, text)
            val room = width - text.measureText(value) - 24f
            text.color = MUTED; text.textAlign = Paint.Align.LEFT
            c.drawText(TextUtils.ellipsize(line.left, text, room.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString(), left, y, text)
        }
        return bmp
    }
}
