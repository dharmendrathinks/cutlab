package com.dhasharma.cutlab.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.dhasharma.cutlab.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder
import kotlin.math.*

/** Small source-derived previews. All decoding happens on an IO worker, never while drawing. */
class TimelineMediaCache {
    private val thumbnails = object : LruCache<String,List<Bitmap>>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: List<Bitmap>) = value.sumOf { it.allocationByteCount }
    }
    private val waves = LruCache<String,FloatArray>(96)
    fun key(clip: Clip) = "${clip.assetId}:${clip.inUs}:${clip.outUs}:${clip.speed}:${clip.speedCurve}:${clip.reverse}:${clip.freezeUs}"
    suspend fun thumbnails(asset: MediaAsset,clip: Clip): List<Bitmap> {
        val key = key(clip)
        thumbnails.get(key)?.let { return it }
        currentCoroutineContext().ensureActive()
        val result = if(asset.kind == AssetKind.IMAGE) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(asset.path,bounds)
            var sample = 1; while(max(bounds.outWidth,bounds.outHeight) / sample > 320) sample *= 2
            listOfNotNull(BitmapFactory.decodeFile(asset.path,BitmapFactory.Options().apply { inSampleSize = sample }))
        } else if(asset.kind == AssetKind.VIDEO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(asset.path)
                (0..2).mapNotNull { i -> currentCoroutineContext().ensureActive(); retriever.getScaledFrameAtTime(clip.sourceTimeUs(clip.durationUs() * i / 3),MediaMetadataRetriever.OPTION_CLOSEST,120,80) }
            } finally { retriever.release() }
        } else emptyList()
        thumbnails.put(key,result)
        return result
    }
    suspend fun waveform(asset: MediaAsset): FloatArray {
        waves.get(asset.path)?.let { return it }
        val bins = FloatArray(256)
        if(!asset.hasAudio && asset.kind != AssetKind.AUDIO) return bins
        val extractor = MediaExtractor(); var codec: MediaCodec? = null
        try {
            extractor.setDataSource(asset.path)
            val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return bins
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec = decoder
            decoder.configure(format,null,null,0); decoder.start()
            val info = MediaCodec.BufferInfo(); var inputDone = false; var outputDone = false; var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT); var pcmFloat = false; var idle = 0
            while(!outputDone) {
                currentCoroutineContext().ensureActive()
                if(!inputDone) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if(input >= 0) {
                        val buffer = decoder.getInputBuffer(input)!!; buffer.clear(); val count = extractor.readSampleData(buffer,0)
                        if(count < 0) { decoder.queueInputBuffer(input,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(input,0,count,extractor.sampleTime,0); extractor.advance() }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info,10_000)
                if(output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat; rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if(output >= 0) {
                    idle = 0
                    val buffer = decoder.getOutputBuffer(output)!!; buffer.order(ByteOrder.LITTLE_ENDIAN); buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val bytes = if(pcmFloat) 4 else 2; var sample = 0
                    while(buffer.remaining() >= bytes) {
                        val amplitude = if(pcmFloat) abs(buffer.float) else abs(buffer.short.toFloat() / 32768f)
                        val time = info.presentationTimeUs + sample.toLong() * 1_000_000 / max(1,channels * rate)
                        val bin = (time * bins.size / max(1L,asset.durationUs)).toInt().coerceIn(bins.indices)
                        bins[bin] = max(bins[bin],amplitude)
                        sample++
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; decoder.releaseOutputBuffer(output,false)
                } else check(++idle < 1000) { "Waveform decoder timed out" }
            }
            val maximum = bins.maxOrNull()?.coerceAtLeast(.01f) ?: 1f
            for(i in bins.indices) bins[i] = sqrt(bins[i] / maximum)
            waves.put(asset.path,bins)
            return bins
        } finally { codec?.let { runCatching { it.stop() }; it.release() }; extractor.release() }
    }
    fun clear() { thumbnails.evictAll(); waves.evictAll() }
}
