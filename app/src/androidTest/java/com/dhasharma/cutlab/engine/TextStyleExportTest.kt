package com.dhasharma.cutlab.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhasharma.cutlab.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TextStyleExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun energy(b: Bitmap): Long {
        var sum = 0L
        for(y in 0 until b.height step 2) for(x in 0 until b.width step 2) { val c = b.getPixel(x,y); sum += Color.red(c)+Color.green(c)+Color.blue(c) }
        return sum
    }
    @Test fun styledAnimatedTextMatchesDecodedExport() = runBlocking<Unit> {
        val style = TextStyle(text = "A new scene\nMake it yours",fontSize = 90f,fontFamily = "serif",italic = true,
            color = 0xFFFFD54F,outlineWidth = 5f,outlineColor = 0xFFFF5252,shadowRadius = 12f,
            entrance = TextAnimation.FADE,exit = TextAnimation.FADE,animationDurationUs = 200_000)
        val clip = Clip(outUs = 600_000,text = style,transform = Transform(x = .12f,y = -.15f,scale = .85f,rotation = 15f))
        val project = Project(width = 1280,height = 720,tracks = listOf(Track(kind = TrackKind.TEXT,clips = listOf(clip))),export = ExportSettings(height = 720,fps = 30,bitrate = 8_000_000))
        val output = File(context.cacheDir,"styled-text-export.mp4")
        TimelineRenderer(context).use { renderer ->
            val dark = renderer.renderFrame(project,0,640,360)
            val visible = renderer.renderFrame(project,300_000,640,360)
            assertTrue("Text becomes visible",energy(visible)>100_000)
            assertTrue("Entrance fades from black",energy(dark)<energy(visible)/20)
            dark.recycle()
            val plain = clip.copy(text = style.copy(fontFamily = "monospace",outlineWidth = 0f,shadowRadius = 0f))
            val alternateProject = project.copy(tracks = listOf(Track(kind = TrackKind.TEXT,clips = listOf(plain))))
            val alternate = renderer.renderFrame(alternateProject,300_000,640,360)
            assertTrue("Style changes alter actual pixels",abs(energy(visible)-energy(alternate))>10_000)
            alternate.recycle()
            VideoExporter(context).export(project,output) {}
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(output.path)
                val decoded = reader.getScaledFrameAtTime(300_000,MediaMetadataRetriever.OPTION_CLOSEST,640,360)!!
                var error = 0L; var count = 0
                for(y in 0 until 360) for(x in 0 until 640) {
                    val a = visible.getPixel(x,y); val b = decoded.getPixel(x,y)
                    if((a and 0xFFFFFF)!=0 || (b and 0xFFFFFF)!=0) { error += abs(Color.red(a)-Color.red(b))+abs(Color.green(a)-Color.green(b))+abs(Color.blue(a)-Color.blue(b)); count+=3 }
                }
                assertTrue("Styled export agrees with preview: ${error.toFloat()/count}",error.toFloat()/count<18)
                decoded.recycle()
            }
            visible.recycle()
        }
    }
}
