package com.dhasharma.cutlab.engine

import android.content.Context
import android.media.*
import com.dhasharma.cutlab.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** PCM files keep decoded audio bounded in memory; interpolation follows the video's source map. */
internal class AudioMixer(private val context:Context) : Closeable {
    private data class Source(val file:File,val rate:Int,val samples:Long,val firstTimeUs:Long) : Closeable {
        val input=RandomAccessFile(file,"r")
        var start=-1L
        var cache=FloatArray(0)
        fun sample(frame:Long,channel:Int):Float {
            if(frame<0 || frame>=samples) return 0f
            if(start<0 || frame<start || frame>=start+cache.size/2) {
                start=max(0,frame-2048); val count=min(16_384L,samples-start).toInt()
                val bytes=ByteArray(count*8); input.seek(start*8); input.readFully(bytes)
                val floats=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); cache=FloatArray(count*2); floats.get(cache)
            }
            return cache[((frame-start)*2+channel).toInt()]
        }
        override fun close() { input.close(); file.delete() }
    }
    private val sources=mutableMapOf<String,Source>()
    private val absent=mutableSetOf<String>()
    private var project:Project?=null
    val hasAudio get()=sources.isNotEmpty()
    suspend fun prepare(project:Project) {
        this.project=project
        val ids=project.tracks.filterNot { it.muted }.flatMap { it.clips }.filter { !it.muted && it.volume>0 && it.freezeUs==null }.mapNotNull { it.assetId }.toSet()
        for(asset in project.assets.filter { it.id in ids && it.kind!=AssetKind.IMAGE }) {
            currentCoroutineContext().ensureActive()
            if(asset.path !in sources && asset.path !in absent) {
                val decoded=decode(asset.path)
                if(decoded==null) absent+=asset.path else sources[asset.path]=decoded
            }
        }
    }
    /** Interleaved stereo, 48 kHz; speed changes alter pitch intentionally. */
    fun mix(startSample:Long,frames:Int):ShortArray {
        val project=checkNotNull(project); val mixed=FloatArray(frames*2)
        for(track in project.tracks.filterNot { it.muted }) {
          val ordered=track.clips.sortedBy { it.startUs }
          for((clipIndex,clip) in ordered.withIndex()) {
            if(clip.muted || clip.volume<=0 || clip.freezeUs!=null) continue
            val asset=project.assets.find { it.id==clip.assetId } ?: continue
            val source=sources[asset.path] ?: continue
            val duration=clip.durationUs()
            val previous=ordered.getOrNull(clipIndex-1)
            val next=ordered.getOrNull(clipIndex+1)
            val entrance=if(clip.transition.kind!=TransitionKind.NONE && previous!=null) min(clip.transition.durationUs,(previous.startUs+previous.durationUs()-clip.startUs).coerceAtLeast(0)) else 0
            val exit=if(next!=null && next.transition.kind!=TransitionKind.NONE) min(next.transition.durationUs,(clip.startUs+duration-next.startUs).coerceAtLeast(0)) else 0
            var mappingStart = -1
            var sourceStart = 0.0
            var sourceStep = 0.0
            for(i in 0 until frames) {
                val time=(startSample+i)*1_000_000L/RATE
                val local=time-clip.startUs
                if(local<0 || local>=duration) continue
                // Mapping at 32-sample intervals avoids allocating curve segments at 48 kHz.
                // Linear interpolation across <0.7 ms is shared by preview and export.
                if(mappingStart<0 || i-mappingStart>=32) {
                    mappingStart=i
                    val endLocal=min(duration-1,local+32L*1_000_000/RATE)
                    sourceStart=(clip.sourceTimeUs(local)-source.firstTimeUs).toDouble()*source.rate/1_000_000.0
                    val end=(clip.sourceTimeUs(endLocal)-source.firstTimeUs).toDouble()*source.rate/1_000_000.0
                    sourceStep=(end-sourceStart)/32.0
                }
                val sourceFrame=sourceStart+(i-mappingStart)*sourceStep
                val frame=floor(sourceFrame).toLong(); val fraction=(sourceFrame-frame).toFloat()
                var gain=clip.volume
                if(entrance>0 && local<entrance) gain*=local.toFloat()/entrance
                if(exit>0 && local>duration-exit) gain*=(duration-local).toFloat()/exit
                if(clip.fadeInUs>0) gain*=min(1f,local.toFloat()/clip.fadeInUs)
                if(clip.fadeOutUs>0) gain*=min(1f,(duration-local).toFloat()/clip.fadeOutUs)
                for(ch in 0..1) mixed[i*2+ch]+=(source.sample(frame,ch)*(1-fraction)+source.sample(frame+1,ch)*fraction)*gain
            }
        }
        }
        return ShortArray(mixed.size) { (mixed[it].coerceIn(-1f,1f)*32767).roundToInt().toShort() }
    }
    private suspend fun decode(path:String):Source? {
        val extractor=MediaExtractor(); var decoder:MediaCodec?=null
        val file=File.createTempFile("cutlab-pcm-",".f32",context.cacheDir)
        try {
            extractor.setDataSource(path)
            val index=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true } ?: run { file.delete(); return null }
            extractor.selectTrack(index); val format=extractor.getTrackFormat(index)
            val firstPacketTimeUs=extractor.sampleTime
            val declaredDelay=if(format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) format.getInteger(MediaFormat.KEY_ENCODER_DELAY) else 0
            android.util.Log.d("CutLabAudio","Decode input: firstPts=$firstPacketTimeUs format=$format")
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=AudioFormat.ENCODING_PCM_16BIT
            decoder=MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder.configure(format,null,null,0); decoder.start()
            var inputEnd=false; var outputEnd=false; var frames=0L; var firstTimeUs:Long?=null
            val info=MediaCodec.BufferInfo()
            FileOutputStream(file).buffered().use { output ->
                while(!outputEnd) {
                    currentCoroutineContext().ensureActive()
                    if(!inputEnd) {
                        val id=decoder.dequeueInputBuffer(10_000)
                        if(id>=0) {
                            val buffer=checkNotNull(decoder.getInputBuffer(id)); val size=extractor.readSampleData(buffer,0)
                            if(size<0) { decoder.queueInputBuffer(id,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd=true }
                            else { decoder.queueInputBuffer(id,0,size,extractor.sampleTime,0); extractor.advance() }
                        }
                    }
                    val id=decoder.dequeueOutputBuffer(info,10_000)
                    if(id==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val out=decoder.outputFormat; val outputRate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE); require(frames==0L || rate==outputRate) { "Audio sample-rate changes inside a file are unsupported" }; rate=outputRate; channels=out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding=if(out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(channels in 1..2) { "Only mono and stereo audio are currently supported" }
                        require(encoding==AudioFormat.ENCODING_PCM_16BIT || encoding==AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported PCM encoding: $encoding" }
                    } else if(id>=0) {
                        if(info.size>0) {
                            if(firstTimeUs==null) {
                                // MP4 edit lists provide negative AAC preroll PTS plus skip-cut
                                // metadata. C2 trims PCM correctly but reports a shifted first
                                // output timestamp. Anchor trimmed PCM to the container's first
                                // packet plus the declared trim, rather than applying it twice.
                                firstTimeUs=if(firstPacketTimeUs<0 && declaredDelay>0)
                                    firstPacketTimeUs+declaredDelay.toLong()*1_000_000/rate
                                else info.presentationTimeUs
                                android.util.Log.d("CutLabAudio","Decode first output: pts=${info.presentationTimeUs} mappedOrigin=$firstTimeUs size=${info.size} format=${decoder.outputFormat}")
                            }
                            val buffer=checkNotNull(decoder.getOutputBuffer(id)).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset+info.size)
                            val stride=if(encoding==AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val count=info.size/(stride*channels)
                            val bytes=ByteBuffer.allocate(count*8).order(ByteOrder.LITTLE_ENDIAN)
                            repeat(count) {
                                fun read()=if(encoding==AudioFormat.ENCODING_PCM_FLOAT) buffer.float else buffer.short/32768f
                                val left=read(); val right=if(channels==2) read() else left
                                bytes.putFloat(left); bytes.putFloat(right)
                            }
                            output.write(bytes.array()); frames+=count
                            if(frames%480_000L<count && context.cacheDir.usableSpace<32L*1024*1024) error("Insufficient storage for audio processing")
                        }
                        outputEnd=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        decoder.releaseOutputBuffer(id,false)
                    }
                }
            }
            android.util.Log.d("CutLabAudio","Decoded frames=$frames rate=$rate firstPts=$firstTimeUs")
            return Source(file,rate,frames,firstTimeUs ?: 0)
        } catch(t:Throwable) { file.delete(); throw t }
        finally { decoder?.runCatching { stop() }; decoder?.release(); extractor.release() }
    }
    override fun close() { sources.values.forEach { it.close() }; sources.clear(); absent.clear() }
    companion object { const val RATE=48_000 }
}
