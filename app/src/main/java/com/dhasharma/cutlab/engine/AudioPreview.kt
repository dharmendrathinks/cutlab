package com.dhasharma.cutlab.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.dhasharma.cutlab.model.Project
import com.dhasharma.cutlab.model.durationUs
import kotlinx.coroutines.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Preview uses the exact export mixer. play() prepares off-main and returns when playback begins. */
class AudioPreview(private val context:Context,private val onError:(Throwable)->Unit = {}):Closeable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var playback:Job?=null
    @Volatile private var track:AudioTrack?=null
    @Volatile private var generation=0
    @Volatile private var beganAtUs=0L
    fun positionUs():Long? = track?.let { audio -> runCatching { beganAtUs+(audio.playbackHeadPosition.toLong() and 0xffffffffL)*1_000_000/AudioMixer.RATE }.getOrNull() }
    suspend fun play(project:Project,startUs:Long=0) {
        check(scope.isActive) { "Audio preview is closed" }
        stop()
        val token=generation
        withContext(Dispatchers.IO) {
            val mixer=AudioMixer(context)
            var resource:AudioTrack?=null
            var ownedByPlayback=false
            val released=AtomicBoolean(false)
            fun release() {
                if(!released.compareAndSet(false,true)) return
                val audio=resource
                if(track===audio) track=null
                audio?.let { runCatching { it.stop() }; runCatching { it.release() } }
                runCatching { mixer.close() }
            }
            try {
                mixer.prepare(project)
                if(token!=generation || !mixer.hasAudio) return@withContext
                val bufferSize=maxOf(8192,AudioTrack.getMinBufferSize(AudioMixer.RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT))
                val audio=AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AudioMixer.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build()
                resource=audio
                check(audio.state==AudioTrack.STATE_INITIALIZED) { "Unable to initialize audio preview" }
                beganAtUs=startUs; track=audio; audio.play()
                val job=scope.launch {
                    try {
                        var sample=startUs.coerceAtLeast(0)*AudioMixer.RATE/1_000_000
                        val total=project.durationUs()*AudioMixer.RATE/1_000_000
                        while(isActive && sample<total && token==generation) {
                            val count=min(1024L,total-sample).toInt(); val pcm=mixer.mix(sample,count)
                            var offset=0
                            while(offset<pcm.size && isActive) {
                                val written=audio.write(pcm,offset,pcm.size-offset,AudioTrack.WRITE_BLOCKING)
                                check(written>0) { "Audio preview output failed: $written" }; offset+=written
                            }
                            sample+=count
                        }
                        if(isActive && token==generation) delay(bufferSize*1000L/(AudioMixer.RATE*4))
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(error:Throwable) {
                        withContext(NonCancellable+Dispatchers.Main.immediate) {
                            if(token==generation) onError(error)
                        }
                    } finally { release() }
                }
                // Completion also runs if cancellation prevents the coroutine body from starting.
                job.invokeOnCompletion { release() }
                ownedByPlayback=true; playback=job
            } finally { if(!ownedByPlayback) release() }
        }
    }
    fun stop() { generation++; playback?.cancel(); playback=null; track?.let { runCatching { it.pause(); it.flush() } } }
    override fun close() { stop(); scope.cancel() }
}
