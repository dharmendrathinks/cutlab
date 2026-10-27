package com.dhasharma.cutlab.engine

import android.content.Context
import android.os.Build
import com.dhasharma.cutlab.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** AAC delay is encoder-specific and Android's C2 AAC encoder does not expose it.
 * Prefer reported metadata; otherwise measure a deterministic impulse through the same codec.
 * Probe results are scoped to codec/configuration/OS identity and cached only in this process.
 */
internal object AacPriming {
    private val mutex=Mutex()
    private val cache=mutableMapOf<String,Int>()
    suspend fun delay(context:Context,codecName:String,encode:suspend(AudioMixer,File,Long)->Int?):Int = mutex.withLock {
        val key="${Build.FINGERPRINT}|$codecName|48000|2|AAC-LC|192000"
        cache[key]?.let { return@withLock it }
        val samples=24_000; val impulse=4096
        val wav=File.createTempFile("cutlab-aac-probe-",".wav",context.cacheDir)
        val encoded=File.createTempFile("cutlab-aac-probe-",".m4a",context.cacheDir)
        try {
            val bytes=ByteBuffer.allocate(44+samples*4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()); bytes.putInt(36+samples*4); bytes.put("WAVEfmt ".toByteArray())
            bytes.putInt(16); bytes.putShort(1.toShort()); bytes.putShort(2.toShort()); bytes.putInt(48_000)
            bytes.putInt(192_000); bytes.putShort(4.toShort()); bytes.putShort(16.toShort()); bytes.put("data".toByteArray()); bytes.putInt(samples*4)
            repeat(samples) { val value=if(it==impulse) 28_000.toShort() else 0.toShort(); bytes.putShort(value); bytes.putShort(value) }
            wav.writeBytes(bytes.array())
            val asset=MediaAsset(path=wav.path,name="AAC delay probe",kind=AssetKind.AUDIO,durationUs=500_000,hasAudio=true)
            val project=Project(assets=listOf(asset),tracks=listOf(Track(kind=TrackKind.AUDIO,clips=listOf(Clip(assetId=asset.id,outUs=500_000)))))
            val reported=AudioMixer(context).use { mixer -> mixer.prepare(project); encode(mixer,encoded,samples.toLong()) }
            val measured=AudioMixer(context).use { mixer ->
                mixer.prepare(project.copy(assets=listOf(asset.copy(path=encoded.path))))
                val pcm=mixer.mix(0,samples)
                val peak=(0 until samples).maxBy { abs(pcm[it*2].toInt()) }
                check(abs(pcm[peak*2].toInt())>1000) { "Cannot determine AAC encoder delay" }
                peak-impulse
            }
            val delay=reported?.takeIf { it>0 } ?: measured
            check(delay in 0..8192) { "Unsupported AAC encoder delay: $delay samples" }
            // Metadata must describe the observed decoder output to prevent double trimming.
            check(reported==null || reported==0 || abs(reported-measured)<=16) { "AAC delay metadata disagrees with codec output" }
            cache[key]=delay
            android.util.Log.i("CutLabAudio","AAC priming: codec=$codecName measured=$measured reported=$reported using=$delay")
            delay
        } finally { wav.delete(); encoded.delete() }
    }
}
