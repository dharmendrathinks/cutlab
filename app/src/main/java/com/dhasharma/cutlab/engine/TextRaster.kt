package com.dhasharma.cutlab.engine

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.dhasharma.cutlab.model.TextStyle
import kotlin.math.*

/** One text layout for preview bounds and the export texture, in project pixels. */
object TextRaster {
    data class Metrics(val width: Int,val height: Int,val padding: Int,val layout: StaticLayout)
    fun measure(style: TextStyle,projectWidth: Int): Metrics {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = style.fontSize.coerceIn(8f,320f)
            color = style.color.toInt()
            val faceStyle = when {
                style.bold && style.italic -> Typeface.BOLD_ITALIC
                style.bold -> Typeface.BOLD
                style.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            typeface = Typeface.create(style.fontFamily,faceStyle)
        }
        val padding = ceil(12f+style.outlineWidth.coerceIn(0f,24f)+style.shadowRadius.coerceIn(0f,32f)*2).toInt()
        val available = (projectWidth-padding*2).coerceAtLeast(1)
        val width = ceil(style.text.split('\n').maxOfOrNull { paint.measureText(it) } ?: 1f).toInt().coerceIn(1,available)
        val layout = StaticLayout.Builder.obtain(style.text,0,style.text.length,paint,width)
            .setAlignment(when(style.alignment) { "left" -> Layout.Alignment.ALIGN_NORMAL; "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER })
            .setIncludePad(true).build()
        return Metrics(width+padding*2,layout.height+padding*2,padding,layout)
    }
    fun bitmap(style: TextStyle,projectWidth: Int): Bitmap {
        val m = measure(style,projectWidth)
        val bitmap = Bitmap.createBitmap(m.width,m.height,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(style.background.toInt()); canvas.translate(m.padding.toFloat(),m.padding.toFloat())
        val paint = m.layout.paint
        if(style.shadowRadius>0) paint.setShadowLayer(style.shadowRadius,style.shadowRadius/2,style.shadowRadius/2,style.shadowColor.toInt())
        if(style.outlineWidth>0) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = style.outlineWidth*2; paint.strokeJoin = Paint.Join.ROUND; paint.color = style.outlineColor.toInt()
            m.layout.draw(canvas); paint.clearShadowLayer()
        }
        paint.style = Paint.Style.FILL; paint.color = style.color.toInt(); m.layout.draw(canvas)
        return bitmap
    }
}
