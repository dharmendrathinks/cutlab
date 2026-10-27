package com.dhasharma.cutlab.engine

import android.content.Context
import android.media.*
import com.dhasharma.cutlab.model.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.*

class VideoExporter(private val context:Context) {
    /** Atomic destination replacement. Cancelling removes all intermediate files. */
    suspend fun export(project:Project,destination:File,onProgress:(Float)->Unit):File {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try { return withContext(dispatcher) {
            require(project.durationUs()>0) { "Add media before exporting" }
            require(project.export.fps in listOf(24,25,30,50,60)) { "Unsupported frame rate" }
            require(project.export.height in listOf(720,1080)) { "Only 720p and 1080p SDR export are supported" }
            destination.parentFile?.mkdirs()
            val video=File.createTempFile("cutlab-video-",".mp4",context.cacheDir)
            val audio=File.createTempFile("cutlab-audio-",".m4a",context.cacheDir)
            val complete=File.createTempFile(".cutlab-export-",".mp4",destination.parentFile ?: context.cacheDir)
            try {
                AudioMixer(context).use { mixer ->
                    onProgress(0f); mixer.prepare(project); onProgress(.04f)
                    encodeVideo(project,video) { onProgress(.04f+it*.83f) }
                    if(mixer.hasAudio) {
                        val priming=encodeAudio(project,mixer,audio) { onProgress(.87f+it*.1f) }
                        mux(listOf(video,audio),complete)
                        Mp4AudioEdits.trim(complete,priming,AudioMixer.RATE,project.durationUs())
                    } else video.copyTo(complete,overwrite=true)
                }
                currentCoroutineContext().ensureActive()
                check(complete.renameTo(destination)) { "Cannot save exported video to ${destination.path}" }
                onProgress(1f); destination
            } finally { video.delete(); audio.delete(); complete.delete() }
        } } finally { dispatcher.close() }
    }
    private suspend fun encodeVideo(project:Project,file:File,progress:(Float)->Unit) {
        val shortEdge=project.export.height
        val h=if(project.height>project.width) max(2,((shortEdge.toDouble()*project.height/project.width)/2).roundToInt()*2) else shortEdge
        val w=if(project.height>project.width) shortEdge else max(2,((h.toDouble()*project.width/project.height)/2).roundToInt()*2)
        val fps=project.export.fps
        val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,project.export.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE,fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
        }
        val name=MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("This device cannot encode ${w}×${h} at $fps fps using H.264")
        val codec=MediaCodec.createByCodecName(name)
        var surface:android.view.Surface?=null
        val muxer=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started=false; var track=-1; var finished=false; val info=MediaCodec.BufferInfo()
        suspend fun drain(end:Boolean=false) {
            var idle=0
            while(true) {
                currentCoroutineContext().ensureActive()
                val id=codec.dequeueOutputBuffer(info,if(end) 10_000 else 0)
                when {
                    id==MediaCodec.INFO_TRY_AGAIN_LATER -> { if(!end) return; check(++idle<1000) { "Video encoder timed out" } }
                    id==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { check(!started); track=muxer.addTrack(codec.outputFormat); muxer.start(); started=true }
                    id>=0 -> {
                        if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0) info.size=0
                        if(info.size>0) { check(started); val buffer=checkNotNull(codec.getOutputBuffer(id)); buffer.position(info.offset); buffer.limit(info.offset+info.size); muxer.writeSampleData(track,buffer,info) }
                        val eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0; codec.releaseOutputBuffer(id,false)
                        if(eos) { finished=true; return }
                    }
                }
            }
        }
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); surface=codec.createInputSurface(); codec.start()
            TimelineRenderer(context,surface).use { renderer ->
                val count=ceil(project.durationUs().toDouble()*fps/1_000_000).toLong()
                for(frame in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    val time=frame*1_000_000L/fps
                    renderer.renderToEncoder(project,time,w,h,frame*1_000_000_000L/fps)
                    drain(); progress((frame+1).toFloat()/count)
                }
                codec.signalEndOfInputStream(); drain(true)
            }
            check(finished)
        } finally {
            surface?.release()
            runCatching { codec.stop() }; codec.release()
            if(started) runCatching { muxer.stop() }; muxer.release()
        }
    }
    private fun audioFormat()=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,AudioMixer.RATE,2).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE,192_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16_384)
    }
    private suspend fun encodeAudio(project:Project,mixer:AudioMixer,file:File,progress:(Float)->Unit):Int {
        val encoder=MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(audioFormat())
            ?: error("This device has no compatible AAC-LC encoder")
        val delay=AacPriming.delay(context,encoder) { probe,destination,samples ->
            encodeAudioPackets(probe,destination,encoder,samples,8192) {}
        }
        val samples=ceil(project.durationUs().toDouble()*AudioMixer.RATE/1_000_000).toLong()
        encodeAudioPackets(mixer,file,encoder,samples,delay+2048,progress)
        return delay
    }
    private suspend fun encodeAudioPackets(mixer:AudioMixer,file:File,encoderName:String,total:Long,tail:Int,progress:(Float)->Unit):Int? {
        val codec=MediaCodec.createByCodecName(encoderName)
        val muxer=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started=false; var track=-1; var sample=0L; var inputEnd=false; var outputEnd=false
        // Whole AAC-LC frames plus explicit zero tail: C2's single EOS flush does not
        // reliably drain all delayed PCM. The final MP4 edit trims priming and padding.
        val supplied=((total+tail+1023)/1024)*1024
        var reportedDelay:Int?=null
        val info=MediaCodec.BufferInfo()
        try {
            codec.configure(audioFormat(),null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            while(!outputEnd) {
                currentCoroutineContext().ensureActive()
                if(!inputEnd) {
                    val id=codec.dequeueInputBuffer(10_000)
                    if(id>=0) {
                        val input=checkNotNull(codec.getInputBuffer(id)).order(ByteOrder.LITTLE_ENDIAN); input.clear()
                        val count=min(min(input.capacity()/4,1024).toLong(),supplied-sample).toInt()
                        if(count==0) { codec.queueInputBuffer(id,0,0,sample*1_000_000/AudioMixer.RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd=true }
                        else {
                            val available=min(count.toLong(),(total-sample).coerceAtLeast(0)).toInt()
                            val shorts=if(available>0) mixer.mix(sample,available) else ShortArray(0)
                            shorts.forEach { input.putShort(it) }; repeat((count-available)*2) { input.putShort(0.toShort()) }
                            codec.queueInputBuffer(id,0,count*4,sample*1_000_000/AudioMixer.RATE,0); sample+=count
                            progress(min(1f,sample.toFloat()/total))
                        }
                    }
                }
                val id=codec.dequeueOutputBuffer(info,10_000)
                if(id==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output=codec.outputFormat
                    if(output.containsKey(MediaFormat.KEY_ENCODER_DELAY)) reportedDelay=output.getInteger(MediaFormat.KEY_ENCODER_DELAY)
                    track=muxer.addTrack(output); muxer.start(); started=true
                } else if(id>=0) {
                    if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0) info.size=0
                    if(info.size>0) { check(started); val output=checkNotNull(codec.getOutputBuffer(id)); output.position(info.offset); output.limit(info.offset+info.size); muxer.writeSampleData(track,output,info) }
                    outputEnd=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0; codec.releaseOutputBuffer(id,false)
                }
            }
            return reportedDelay
        } finally { runCatching { codec.stop() }; codec.release(); if(started) runCatching { muxer.stop() }; muxer.release() }
    }
    private suspend fun mux(files:List<File>,destination:File) {
        val muxer=MediaMuxer(destination.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources=mutableListOf<Pair<MediaExtractor,Int>>()
        var started=false
        try {
            for(file in files) {
                val extractor=MediaExtractor(); extractor.setDataSource(file.path)
                check(extractor.trackCount==1); extractor.selectTrack(0)
                sources+=extractor to muxer.addTrack(extractor.getTrackFormat(0))
            }
            muxer.start(); started=true; var buffer=ByteBuffer.allocateDirect(4*1024*1024)
            val info=MediaCodec.BufferInfo()
            for((source,track) in sources) while(source.sampleTime>=0) {
                currentCoroutineContext().ensureActive()
                if(source.sampleSize>buffer.capacity()) buffer=ByteBuffer.allocateDirect(source.sampleSize.toInt())
                buffer.clear(); val size=source.readSampleData(buffer,0); if(size<0) break
                info.set(0,size,source.sampleTime,if(source.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0); muxer.writeSampleData(track,buffer,info); source.advance()
            }
        } finally { sources.forEach { it.first.release() }; if(started) runCatching { muxer.stop() }; muxer.release() }
    }
}
