package com.dhasharma.cutlab

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhasharma.cutlab.model.*
import com.dhasharma.cutlab.ui.TimelineMediaCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class TimelineMediaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun thumbnailsContainSourcePixelsAndReuseDecodedCache() = runBlocking {
        val file = File.createTempFile("thumbnail-test-",".png",context.cacheDir)
        val original = Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG,100,it) }; original.recycle()
        val cache = TimelineMediaCache()
        try {
            val asset = MediaAsset(path=file.path,name="red.png",kind=AssetKind.IMAGE)
            val clip = Clip(assetId=asset.id)
            val frames = cache.thumbnails(asset,clip)
            assertEquals(1,frames.size)
            assertEquals(Color.RED,frames.single().getPixel(10,10))
            assertSame(frames,cache.thumbnails(asset,clip))
        } finally { cache.clear(); file.delete() }
    }
    @Test fun videoThumbnailsAdvanceAndReverseInvalidatesCache() = runBlocking {
        val file = File.createTempFile("moving-thumbnails-",".mp4",context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("reference.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val cache = TimelineMediaCache()
        try {
            val asset = MediaAsset(path = file.path,name = "reference.mp4",kind = AssetKind.VIDEO,durationUs = 3_000_000,width = 320,height = 180)
            val clip = Clip(assetId = asset.id,outUs = asset.durationUs)
            val frames = cache.thumbnails(asset,clip)
            assertEquals(3,frames.size)
            fun changed(a: Bitmap,b: Bitmap): Int = (0 until minOf(a.width,b.width)).sumOf { x -> (0 until minOf(a.height,b.height)).count { y -> a.getPixel(x,y) != b.getPixel(x,y) } }
            assertTrue("Moving fixture must not repeat the first sync frame",changed(frames.first(),frames.last()) > 100)
            val reverse = cache.thumbnails(asset,clip.copy(reverse = true))
            assertNotSame(frames,reverse)
            assertTrue("Reverse starts with a later frame",changed(frames.first(),reverse.first()) > 100)
        } finally { cache.clear(); file.delete() }
    }
    @Test fun waveformReflectsSilenceAndDecodedAudio() = runBlocking {
        val file = File.createTempFile("waveform-test-",".wav",context.cacheDir)
        val rate = 48000; val bytes = rate*2
        val buffer = ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()); buffer.putInt(36+bytes); buffer.put("WAVEfmt ".toByteArray()); buffer.putInt(16); buffer.putShort(1); buffer.putShort(1); buffer.putInt(rate); buffer.putInt(rate*2); buffer.putShort(2); buffer.putShort(16); buffer.put("data".toByteArray()); buffer.putInt(bytes)
        for(i in 0 until rate) buffer.putShort(if(i<rate/2) 0 else (sin(i*2*Math.PI*1000/rate)*18000).toInt().toShort())
        file.writeBytes(buffer.array())
        val cache = TimelineMediaCache()
        try {
            val asset = MediaAsset(path=file.path,name="tone.wav",kind=AssetKind.AUDIO,durationUs=1_000_000,hasAudio=true)
            val bins = cache.waveform(asset)
            assertEquals(256,bins.size)
            assertTrue("Silence must remain near zero",bins.take(110).maxOrNull()!! < .01f)
            assertTrue("Tone must have visible amplitude",bins.takeLast(110).average() > .8)
        } finally { cache.clear(); file.delete() }
    }
}
