package com.foxdebug.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import org.json.JSONObject

object Ui {

    object Icons {
        const val LOGO = "\uE922"
        const val TUNE = "\uE927"
        const val EXIT = "\uE902"
        const val REFRESH = "\uE91B"
        const val TERMINAL = "\uE923"
        const val NO_CACHE = "\uE901"
        const val MORE_VERT = "\uE91A"
        const val OPEN_IN_BROWSER = "\uE91f"

        const val PHONE_APPLE = "\uE928"
        const val PHONE_ANDROID = "\uE90E"
        const val TABLET_ANDROID = "\uE90F"
        const val TABLET_APPLE = "\uE92A"
        const val DESKTOP = "\uE90A"
        const val DEVICES = "\uE907"
        const val LAPTOP = "\uE90D"
        const val TV = "\uE929"

        private const val FONT_PATH = "font/icon.ttf"

        @JvmStatic var size = 24
        @JvmStatic var color = Color.parseColor("#FFFFFF")

        private var paint: Paint? = null

        @JvmStatic
        @JvmOverloads
        fun get(
            context: Context,
            code: String,
            size: Int = Icons.size,
            color: Int = Icons.color
        ): Bitmap {
            val localPaint = paint ?: Paint().apply {
                isAntiAlias = true
                typeface = Typeface.createFromAsset(context.assets, FONT_PATH)
                textAlign = Paint.Align.CENTER
            }.also { paint = it }

            localPaint.textSize = size.toFloat()
            localPaint.color = color

            val baseline = -localPaint.ascent()
            val width = localPaint.measureText(code).toInt().coerceAtLeast(1)
            val height = (baseline + localPaint.descent()).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            canvas.drawText(code, width / 2f, baseline, localPaint)
            return bitmap
        }

        @JvmStatic
        fun get(context: Context, code: String, size: Int, colorHex: String): Bitmap {
            return get(context, code, size, Color.parseColor(colorHex))
        }

        @JvmStatic
        fun get(context: Context, code: String, colorHex: String): Bitmap {
            return get(context, code, size, Color.parseColor(colorHex))
        }
    }

    class Theme(private val theme: JSONObject) {

        @JvmOverloads
        fun get(colorKey: String, fallback: String = "#000000"): Int {
            val hex = theme.optString(colorKey, fallback)
            return Color.parseColor(hex)
        }

        val type: String
            get() = theme.optString("type", "light")
    }

    @JvmStatic
    fun dpToPixels(context: Context, dipValue: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dipValue.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
