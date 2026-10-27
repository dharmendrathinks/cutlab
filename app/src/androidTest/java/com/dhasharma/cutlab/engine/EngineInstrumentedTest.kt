package com.dhasharma.cutlab.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import org.junit.Assume.assumeTrue
import org.json.JSONObject
import org.json.JSONArray
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhasharma.cutlab.model.*
import kotlinx.coroutines.*
import android.os.SystemClock
import android.util.Log
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture():File = File(context.cacheDir,"reference-test.mp4").also { destination ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("reference.mp4").use { input -> destination.outputStream().use { input.copyTo(it) } }
    }
    @Test fun realVideoDecodeAndAudibleExport()= runBlocking<Unit> {
        val source=fixture(); val output=File(context.cacheDir,"engine-smoke.mp4")
        val asset=MediaAsset(path=source.path,name="Fixture",kind=AssetKind.VIDEO,durationUs=3_000_000,width=320,height=180,hasAudio=true)
        val project=Project(width=320,height=180,assets=listOf(asset),tracks=listOf(Track(clips=listOf(Clip(assetId=asset.id,outUs=250_000)))),export=ExportSettings(height=720,fps=24,bitrate=2_000_000))
        TimelineRenderer(context).use { renderer ->
            val frame=renderer.renderFrame(project,100_000,320,180)
            assertTrue("Decoded video contains colored pixels",(0 until 320 step 16).any { x -> Color.red(frame.getPixel(x,90))>40 || Color.green(frame.getPixel(x,90))>40 }); frame.recycle()
        }
        AudioMixer(context).use { mixer ->
            mixer.prepare(project); assertTrue(mixer.hasAudio)
            val pcm=mixer.mix(0,4096); assertTrue("Source audio is actually decoded",pcm.any { abs(it.toInt())>500 })
        }
        var progress=0f
        VideoExporter(context).export(project,output) { progress=it }
        assertEquals(1f,progress,0f); assertTrue(output.length()>1000)
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            assertEquals(2,extractor.trackCount)
            val mimeTypes=(0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            assertTrue(mimeTypes.contains("video/avc")); assertTrue(mimeTypes.contains("audio/mp4a-latm"))
        } finally { extractor.release() }
        val exported=asset.copy(path=output.path,durationUs=250_000)
        AudioMixer(context).use { mixer ->
            mixer.prepare(project.copy(assets=listOf(exported)))
            assertTrue("Exported audio contains sound",mixer.mix(0,4096).any { abs(it.toInt())>300 })
        }
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(output.path)
            val frame=retriever.getFrameAtTime(100_000,MediaMetadataRetriever.OPTION_CLOSEST)
            assertNotNull(frame); assertEquals(1280,frame!!.width); assertEquals(720,frame.height); frame.recycle()
        }
        // Retain engine-smoke.mp4 for external inspection via adb run-as.
    }
    @Test fun referenceBaseline1080p30HasNinetyFrames()=runBlocking<Unit> {
        val source=fixture()
        val asset=MediaAsset(path=source.path,name="Reference baseline",kind=AssetKind.VIDEO,durationUs=3_000_000,width=320,height=180,hasAudio=true)
        val project=Project(width=320,height=180,assets=listOf(asset),tracks=listOf(Track(clips=listOf(Clip(assetId=asset.id,outUs=3_000_000)))),export=ExportSettings(height=1080,fps=30,bitrate=12_000_000))
        val output=File(context.cacheDir,"engine-reference-baseline.mp4")
        VideoExporter(context).export(project,output) {}
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            val index=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="video/avc" }
            val format=extractor.getTrackFormat(index)
            assertEquals(1920,format.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(1080,format.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(index)
            var count=0; var lastTime=-1L
            while(extractor.sampleTime>=0) { assertTrue(extractor.sampleTime>lastTime); lastTime=extractor.sampleTime; count++; extractor.advance() }
            assertEquals(90,count); assertTrue(abs(lastTime-2_966_666)<=1)
        } finally { extractor.release() }
    }
    @Test fun portrait1080p30ExportParityAndPreviewBenchmark()=runBlocking<Unit> {
        val source=fixture(); val output=File(context.cacheDir,"engine-portrait1080p30.mp4")
        val asset=MediaAsset(path=source.path,name="Portrait reference",kind=AssetKind.VIDEO,durationUs=3_000_000,width=320,height=180,hasAudio=true)
        val clip=Clip(assetId=asset.id,outUs=300_000,color=ColorAdjustments(saturation=.7f,contrast=1.05f))
        val project=Project(width=1080,height=1920,assets=listOf(asset),tracks=listOf(Track(clips=listOf(clip))),export=ExportSettings(height=1080,fps=30,bitrate=8_000_000))
        val start=SystemClock.elapsedRealtime()
        VideoExporter(context).export(project,output) {}
        val exportMs=SystemClock.elapsedRealtime()-start
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            val index=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="video/avc" }
            val format=extractor.getTrackFormat(index)
            assertEquals(1080,format.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(1920,format.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(index); val first=extractor.sampleTime; extractor.advance()
            assertTrue("Video timestamps are30fps",abs(extractor.sampleTime-first-33_333)<=1)
        } finally { extractor.release() }
        TimelineRenderer(context).use { renderer ->
            val expected=renderer.renderFrame(project,100_000,1080,1920)
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(output.path)
                val actual=checkNotNull(retriever.getFrameAtTime(100_000,MediaMetadataRetriever.OPTION_CLOSEST))
                var error=0L; var count=0
                // Only score the nonblack landscape image inside the portrait canvas.
                for(y in 660 until 1260 step 6) for(x in 0 until 1080 step 6) {
                    val a=actual.getPixel(x,y); val e=expected.getPixel(x,y)
                    error+=abs(Color.red(a)-Color.red(e))+abs(Color.green(a)-Color.green(e))+abs(Color.blue(a)-Color.blue(e)); count+=3
                }
                val mae=error.toDouble()/count
                Log.i("CutLabBenchmark","1080p30 preview/export MAE=$mae; exportMs=$exportMs")
                assertTrue("Preview/export agree within H264 and YUV conversion tolerance: MAE=$mae",mae<18)
                actual.recycle()
            }
            expected.recycle()
            fun timings(p:Project,w:Int,h:Int):List<Long> {
                renderer.renderFrame(p,0,w,h).recycle()
                return (0 until 12).map { frame ->
                    val begin=SystemClock.elapsedRealtimeNanos()
                    renderer.renderFrame(p,frame*16_667L,w,h).recycle()
                    (SystemClock.elapsedRealtimeNanos()-begin)/1_000_000
                }
            }
            val low=timings(project,320,180)
            val highAsset=asset.copy(path=output.path,width=1080,height=1920,durationUs=300_000)
            val high=timings(project.copy(assets=listOf(highAsset),tracks=listOf(Track(clips=listOf(clip.copy(color=ColorAdjustments()))))),360,640)
            val report="""{"export1080p30Ms":$exportMs,"source320PreviewMs":$low,"source1080PreviewMs":$high}"""
            File(context.cacheDir,"engine-benchmark.json").writeText(report)
            Log.i("CutLabBenchmark",report)
        }
    }
    @Test fun portrait1080p60WhenEncoderSupportsIt()=runBlocking<Unit> {
        val codecs=MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val report=JSONArray()
        for(codec in codecs.codecInfos.filter { it.isEncoder && it.supportedTypes.contains("video/avc") }) {
            val capabilities=codec.getCapabilitiesForType("video/avc").videoCapabilities
            report.put(JSONObject().put("name",codec.name).put("hardware",codec.isHardwareAccelerated)
                .put("widths",capabilities.supportedWidths.toString()).put("heights",capabilities.supportedHeights.toString())
                .put("frameRates",capabilities.supportedFrameRates.toString())
                .put("portrait1080p30",capabilities.areSizeAndRateSupported(1080,1920,30.0))
                .put("portrait1080p60",capabilities.areSizeAndRateSupported(1080,1920,60.0))
                .put("landscape1080p60",capabilities.areSizeAndRateSupported(1920,1080,60.0)))
        }
        File(context.cacheDir,"engine-codec-capabilities.json").writeText(report.toString(2))
        Log.i("CutLabBenchmark","Encoder capabilities: $report")
        val format=MediaFormat.createVideoFormat("video/avc",1080,1920).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,8_000_000); setInteger(MediaFormat.KEY_FRAME_RATE,60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
        }
        assumeTrue("Device has no H264 surface encoder supporting1080×1920 at60fps; capability gate remains unverified",codecs.findEncoderForFormat(format)!=null)
        val asset=MediaAsset(path=fixture().path,name="60fps",kind=AssetKind.VIDEO,durationUs=3_000_000,width=320,height=180,hasAudio=true)
        val project=Project(width=1080,height=1920,assets=listOf(asset),tracks=listOf(Track(clips=listOf(Clip(assetId=asset.id,outUs=150_000)))),export=ExportSettings(height=1080,fps=60,bitrate=8_000_000))
        val output=File(context.cacheDir,"engine-portrait1080p60.mp4")
        VideoExporter(context).export(project,output) {}
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            val index=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="video/avc" }
            val encoded=extractor.getTrackFormat(index)
            assertEquals(1080,encoded.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(1920,encoded.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(index); val first=extractor.sampleTime; extractor.advance()
            assertTrue(abs(extractor.sampleTime-first-16_667)<=1)
        } finally { extractor.release() }
    }
    @Test fun timedAudioPulsesAlignWithVideoFlashesWithinTwentyMilliseconds()=runBlocking<Unit> {
        val rate=48_000
        val wave=File(context.cacheDir,"av-sync-source.wav")
        val bytes=ByteBuffer.allocate(44+rate*4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()); bytes.putInt(36+rate*4); bytes.put("WAVEfmt ".toByteArray())
        bytes.putInt(16); bytes.putShort(1.toShort()); bytes.putShort(2.toShort()); bytes.putInt(rate)
        bytes.putInt(rate*4); bytes.putShort(4.toShort()); bytes.putShort(16.toShort())
        bytes.put("data".toByteArray()); bytes.putInt(rate*4)
        for(sample in 0 until rate) {
            val active=sample in 0 until 1920 || sample in 12_000 until 13_920 || sample in 36_000 until 37_920 || sample in 46_000 until 47_920
            val pcm=if(active) (sin(2*PI*1000*sample/rate)*16_000).toInt().toShort() else 0.toShort()
            bytes.putShort(pcm); bytes.putShort(pcm)
        }
        wave.writeBytes(bytes.array())
        val image=File(context.cacheDir,"av-sync-white.png")
        Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE); image.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) }; recycle()
        }
        val visual=MediaAsset(path=image.path,name="Flash",kind=AssetKind.IMAGE,width=160,height=90)
        val sound=MediaAsset(path=wave.path,name="Pulses",kind=AssetKind.AUDIO,durationUs=1_000_000,hasAudio=true)
        val project=Project(width=160,height=90,assets=listOf(visual,sound),tracks=listOf(
            Track(clips=listOf(Clip(assetId=visual.id,startUs=0,outUs=41_666),Clip(assetId=visual.id,startUs=250_000,outUs=41_666),Clip(assetId=visual.id,startUs=750_000,outUs=41_666),Clip(assetId=visual.id,startUs=958_333,outUs=41_666))),
            Track(kind=TrackKind.AUDIO,clips=listOf(Clip(assetId=sound.id,outUs=1_000_000)))
        ),export=ExportSettings(height=720,fps=24,bitrate=2_000_000))
        val output=File(context.cacheDir,"engine-av-sync.mp4")
        VideoExporter(context).export(project,output) {}
        val flashTimes=mutableListOf<Long>()
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(output.path)
            var previousWhite=false
            for(frame in 0 until 24) {
                val bitmap=checkNotNull(retriever.getScaledFrameAtTime(frame*1_000_000L/24,MediaMetadataRetriever.OPTION_CLOSEST,160,90))
                val white=Color.red(bitmap.getPixel(80,45))>200
                if(white && !previousWhite) flashTimes+=frame*1_000_000L/24
                previousWhite=white; bitmap.recycle()
            }
        }
        assertEquals("Beginning, middle and final flashes must survive export",listOf(0L,250_000L,750_000L,958_333L),flashTimes)
        val audioAsset=sound.copy(path=output.path)
        val decoded=AudioMixer(context).use { mixer ->
            mixer.prepare(project.copy(assets=listOf(audioAsset),tracks=listOf(Track(kind=TrackKind.AUDIO,clips=listOf(Clip(assetId=sound.id,outUs=1_000_000))))))
            mixer.mix(0,rate)
        }
        val rawPcm=ByteBuffer.allocate(decoded.size*2).order(ByteOrder.LITTLE_ENDIAN)
        decoded.forEach { rawPcm.putShort(it) }
        File(context.cacheDir,"engine-av-sync-decoded.pcm").writeBytes(rawPcm.array())
        val runs=mutableListOf<Long>(); var previous=-1000
        for(sample in 0 until rate) if(abs(decoded[sample*2].toInt())>1500) { if(sample-previous>480) runs+=sample*1_000_000L/rate; previous=sample }
        android.util.Log.i("CutLabAudio","Reimport pulse runs: $runs")
        File(context.cacheDir,"engine-av-sync-decoded-onsets.json").writeText(runs.toString())
        val measurements=JSONArray()
        for(flash in flashTimes) {
            val first=((flash-100_000)*rate/1_000_000).toInt().coerceAtLeast(0)
            val last=((flash+100_000)*rate/1_000_000).toInt().coerceAtMost(rate)
            val onset=(first until last).firstOrNull { abs(decoded[it*2].toInt())>1500 }
            val audioTime=onset?.toLong()?.times(1_000_000)?.div(rate)
            val offset=audioTime?.minus(flash)
            measurements.put(JSONObject().put("videoOnsetUs",flash).put("audioOnsetUs",audioTime ?: JSONObject.NULL).put("audioMinusVideoUs",offset ?: JSONObject.NULL))
        }
        File(context.cacheDir,"engine-av-sync.json").writeText(measurements.toString(2))
        Log.i("CutLabBenchmark","A/V onset measurements: $measurements")
        assertTrue("Final audio pulse remains audible beyond987ms",(47_400 until 47_900).any { abs(decoded[it*2].toInt())>1500 })
        for(index in 0 until measurements.length()) {
            assertFalse("Audio pulse must be present near flash ${flashTimes[index]}; actual pulse runs=$runs",measurements.getJSONObject(index).isNull("audioMinusVideoUs"))
            val offset=measurements.getJSONObject(index).getLong("audioMinusVideoUs")
            assertTrue("Audio/video onset offset must be within20ms: $offset microseconds",abs(offset)<=20_000)
        }
    }
    @Test fun cancelledExportLeavesNoPartialDestination()=runBlocking<Unit> {
        val source=fixture(); val destination=File(context.cacheDir,"cancelled-export.mp4").apply { delete() }
        val asset=MediaAsset(path=source.path,name="Cancel",kind=AssetKind.VIDEO,durationUs=3_000_000,hasAudio=true)
        val project=Project(width=320,height=180,assets=listOf(asset),tracks=listOf(Track(clips=listOf(Clip(assetId=asset.id,outUs=1_000_000)))),export=ExportSettings(height=720,fps=24))
        fun intermediates()=context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("cutlab-pcm-") || it.name.startsWith("cutlab-video-") || it.name.startsWith("cutlab-audio-") || it.name.startsWith(".cutlab-export-") }.map { it.name }.toSet()
        val before=intermediates()
        val task=async {
            val job=currentCoroutineContext().job
            VideoExporter(context).export(project,destination) { if(it>.1f) job.cancel() }
        }
        try { task.await(); fail("Export must propagate cancellation") } catch(expected:CancellationException) { }
        assertFalse(destination.exists()); assertEquals(before,intermediates())
    }
    @Test fun dissolveAdvancesBothSourceVideos() {
        val source=fixture()
        val asset=MediaAsset(path=source.path,name="Motion",kind=AssetKind.VIDEO,durationUs=3_000_000,width=320,height=180)
        val outgoing=Clip(assetId=asset.id,outUs=1_000_000)
        val incoming=Clip(assetId=asset.id,startUs=500_000,inUs=1_000_000,outUs=2_000_000,transition=Transition(TransitionKind.DISSOLVE,500_000))
        val base=Project(width=320,height=180,assets=listOf(asset),tracks=listOf(Track(clips=listOf(outgoing,incoming))))
        TimelineRenderer(context).use { renderer ->
            val composite=renderer.renderFrame(base,750_000,320,180)
            val a=renderer.renderFrame(base.copy(tracks=listOf(Track(clips=listOf(outgoing)))),750_000,320,180)
            val b=renderer.renderFrame(base.copy(tracks=listOf(Track(clips=listOf(incoming.copy(transition=Transition()))))),750_000,320,180)
            var error=0L
            for(y in 0 until 180 step 3) for(x in 0 until 320 step 3) {
                val pa=a.getPixel(x,y); val pb=b.getPixel(x,y); val pc=composite.getPixel(x,y)
                error+=abs(Color.red(pc)-(Color.red(pa)+Color.red(pb))/2)
                error+=abs(Color.green(pc)-(Color.green(pa)+Color.green(pb))/2)
                error+=abs(Color.blue(pc)-(Color.blue(pa)+Color.blue(pb))/2)
            }
            assertTrue("Dissolve is the temporal midpoint of two advancing source frames",error<320/3*180/3*3*2)
            val earlier=renderer.renderFrame(base,600_000,320,180)
            val later=renderer.renderFrame(base,900_000,320,180)
            var changed=0
            for(y in 0 until 180 step 4) for(x in 0 until 320 step 4) if(earlier.getPixel(x,y)!=later.getPixel(x,y)) changed++
            assertTrue("Transition output changes over time",changed>100)
            listOf(composite,a,b,earlier,later).forEach { it.recycle() }
        }
    }
    @Test fun glCompositingAndChromaKey() {
        val red=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val green=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        fun asset(bitmap:Bitmap,name:String):MediaAsset {
            val file=File(context.cacheDir,name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            return MediaAsset(path=file.path,name=name,kind=AssetKind.IMAGE,width=64,height=64)
        }
        val a=asset(red,"red.png"); val b=asset(green,"green.png")
        val base=Clip(assetId=a.id); val overlay=Clip(assetId=b.id,chromaKey=ChromaKey(enabled=true))
        val project=Project(width=64,height=64,assets=listOf(a,b),tracks=listOf(Track(clips=listOf(base)),Track(clips=listOf(overlay))))
        TimelineRenderer(context).use { renderer ->
            val frame=renderer.renderFrame(project,0,64,64)
            assertTrue("Green is keyed out and red underlay survives",Color.red(frame.getPixel(32,32))>240 && Color.green(frame.getPixel(32,32))<10)
            frame.recycle()
            val translucent=project.copy(tracks=listOf(Track(clips=listOf(base)),Track(clips=listOf(overlay.copy(chromaKey=ChromaKey(),transform=Transform(opacity=.5f))))))
            val mixed=renderer.renderFrame(translucent,0,64,64)
            assertTrue(abs(128-Color.red(mixed.getPixel(32,32)))<=3); assertTrue(abs(128-Color.green(mixed.getPixel(32,32)))<=3); mixed.recycle()
        }
    }
}
