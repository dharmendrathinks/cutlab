package com.dhasharma.cutlab.engine

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.max

/** Persistent, seekable preview decoder. Returned bitmaps belong to the caller. */
internal class PreviewVideoDecoder(path: String) : Closeable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var format: MediaFormat
    private val rotation: Int
    private var inputEnded = false
    private var outputEnded = false
    private var previous: Pair<Long,Bitmap>? = null
    private var current: Pair<Long,Bitmap>? = null
    private var targetSize = 0 to 0
    private val planeBytes = Array(3) { ByteArray(0) }
    var decodedFrames = 0; private set
    init {
        try {
            extractor.setDataSource(path)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(index)
            format = extractor.getTrackFormat(index)
            rotation = if(format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            // Rotation is applied to RGB explicitly, consistently across decoders.
            format.setInteger(MediaFormat.KEY_ROTATION,0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))).also { it.configure(format,null,null,0); it.start() }
        } catch(error: Throwable) { close(); throw error }
    }
    fun frameAt(timeUs: Long,width: Int,height: Int): Bitmap {
        val time = timeUs.coerceAtLeast(0)
        val size = width to height
        val now = current
        if(targetSize == (0 to 0)) targetSize = size
        else if(size != targetSize || (now != null && (time<(previous?.first ?: now.first) || time>now.first+1_000_000))) {
            codec!!.flush(); extractor.seekTo(time,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            previous?.second?.recycle(); current?.second?.recycle(); previous = null; current = null
            inputEnded = false; outputEnded = false; targetSize = size
        }
        val deadline = SystemClock.elapsedRealtime()+10_000
        while(!outputEnded && (current == null || current!!.first<time)) {
            check(SystemClock.elapsedRealtime()<deadline) { "Preview decoder timed out at $time (current=${current?.first}, inputEnd=$inputEnded)" }
            decodeNext(width,height)
        }
        val a = current ?: error("No decoded preview frame")
        val b = previous
        val chosen = if(b != null && abs(time-b.first)<abs(a.first-time)) b.second else a.second
        return requireNotNull(chosen.copy(Bitmap.Config.ARGB_8888,false))
    }
    private fun decodeNext(width: Int,height: Int) {
        val decoder = codec!!
        if(!inputEnded) {
            val id = decoder.dequeueInputBuffer(1_000)
            if(id>=0) {
                val buffer = requireNotNull(decoder.getInputBuffer(id)); buffer.clear()
                val size = extractor.readSampleData(buffer,0)
                if(size<0) { decoder.queueInputBuffer(id,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                else { decoder.queueInputBuffer(id,0,size,extractor.sampleTime,0); extractor.advance() }
            }
        }
        val info = MediaCodec.BufferInfo()
        val id = decoder.dequeueOutputBuffer(info,1_000)
        if(id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = decoder.outputFormat
        if(id>=0) {
            try {
                if(info.size>0) {
                    val image = requireNotNull(decoder.getOutputImage(id)) { "Decoder does not expose YUV preview frames" }
                    val frame = image.use { rgb(it,width,height) }
                    previous?.second?.recycle(); previous = current; current = info.presentationTimeUs to frame
                    decodedFrames++
                }
                if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
            } finally { decoder.releaseOutputBuffer(id,false) }
        }
    }
    private fun rgb(image: Image,targetWidth: Int,targetHeight: Int): Bitmap {
        check(image.format == ImageFormat.YUV_420_888)
        val crop = image.cropRect
        val targetLong = max(targetWidth,targetHeight).coerceAtLeast(2)
        val sample = max(1,max(crop.width(),crop.height())/targetLong)
        val w = max(1,crop.width()/sample); val h = max(1,crop.height()/sample)
        val planes = image.planes
        for(i in planes.indices) {
            val buffer = planes[i].buffer.duplicate()
            val count = buffer.remaining()
            if(planeBytes[i].size<count) planeBytes[i] = ByteArray(count)
            buffer.get(planeBytes[i],0,count)
        }
        val full = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE)==MediaFormat.COLOR_RANGE_FULL
        val hd = format.containsKey(MediaFormat.KEY_COLOR_STANDARD) && format.getInteger(MediaFormat.KEY_COLOR_STANDARD)==MediaFormat.COLOR_STANDARD_BT709
        val kr = if(hd) .2126f else .299f; val kb = if(hd) .0722f else .114f; val kg = 1-kr-kb
        val scale = if(full) 1f else 255f/224f
        val rv = 2*(1-kr)*scale; val bu = 2*(1-kb)*scale
        val gu = -2*kb*(1-kb)/kg*scale; val gv = -2*kr*(1-kr)/kg*scale
        val pixels = IntArray(w*h)
        for(y in 0 until h) {
            val sy = crop.top+y*sample
            val yy = sy*planes[0].rowStride
            val uy = (sy/2)*planes[1].rowStride
            val vy = (sy/2)*planes[2].rowStride
            for(x in 0 until w) {
                val sx = crop.left+x*sample
                val luma = planeBytes[0][yy+sx*planes[0].pixelStride].toInt() and 255
                val u = (planeBytes[1][uy+(sx/2)*planes[1].pixelStride].toInt() and 255)-128
                val v = (planeBytes[2][vy+(sx/2)*planes[2].pixelStride].toInt() and 255)-128
                val level = if(full) luma.toFloat() else (luma-16)*255f/219f
                val r = (level+rv*v+.5f).toInt().coerceIn(0,255)
                val g = (level+gu*u+gv*v+.5f).toInt().coerceIn(0,255)
                val b = (level+bu*u+.5f).toInt().coerceIn(0,255)
                pixels[y*w+x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        if(rotation%360 == 0) return bitmap
        return Bitmap.createBitmap(bitmap,0,0,w,h,Matrix().apply { postRotate(rotation.toFloat()) },true).also { if(it !== bitmap) bitmap.recycle() }
    }
    override fun close() {
        previous?.second?.recycle(); current?.second?.recycle(); previous = null; current = null
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }; codec = null
        runCatching { extractor.release() }
    }
}
