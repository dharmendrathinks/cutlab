package com.dhasharma.cutlab

import android.os.Bundle
import android.graphics.Rect
import android.graphics.Color
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import android.view.MotionEvent
import android.os.SystemClock
import android.net.Uri
import com.dhasharma.cutlab.data.ProjectStore
import com.dhasharma.cutlab.model.*
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual accessible UI and persistence without depending on Compose implementation. */
@RunWith(AndroidJUnit4::class)
class EditorWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun flatten(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if(node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { flatten(node.getChild(it)) }
    private fun awaitNode(description: String, timeout: Long = 20_000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) {
            val node = flatten(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull(predicate)
            if(node != null) return node
            Thread.sleep(100)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { b -> File(instrumentation.targetContext.cacheDir,"ui-failure.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; b.recycle() }
        val visible = flatten(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { it.text ?: it.contentDescription }.joinToString()
        error("Accessible state not reached: $description; visible: $visible")
    }
    private fun find(label: String) = awaitNode(label) { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun click(label: String) {
        val node = awaitNode("enabled clickable $label") { candidate ->
            if(candidate.text?.toString() != label && candidate.contentDescription?.toString() != label) false
            else { var target: AccessibilityNodeInfo? = candidate; while(target != null && !target.isClickable) target = target.parent; target?.isEnabled == true }
        }
        var target: AccessibilityNodeInfo? = node
        while(target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $label" }
        awaitDraw()
    }
    private fun assertResolutionPainted() = assertTextPainted("1080P")
    private fun assertTextPainted(label: String) {
        val bounds = Rect().also { find(label).getBoundsInScreen(it) }
        val until = System.currentTimeMillis()+5_000
        while(System.currentTimeMillis()<until) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            if(screenshot != null) {
                var white = 0
                for(y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(screenshot.height))
                    for(x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(screenshot.width)) {
                        val pixel = screenshot.getPixel(x,y)
                        if(Color.red(pixel)>220 && Color.green(pixel)>220 && Color.blue(pixel)>220) white++
                    }
                screenshot.recycle()
                if(white>80) return
            }
            Thread.sleep(100)
        }
        error("$label exists in accessibility but was not painted")
    }
    private fun assertTimelinePainted() {
        val bounds = Rect().also { find("Timeline").getBoundsInScreen(it) }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val until = System.currentTimeMillis()+5_000
        while(System.currentTimeMillis()<until) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            if(screenshot != null) {
                val center = screenshot.width/2
                val end = (bounds.top+170*density).toInt().coerceAtMost(screenshot.height)
                val whitePixels = ((bounds.top+30*density).toInt().coerceAtLeast(0) until end).count { y ->
                    (-3..3).any { offset -> val pixel = screenshot.getPixel((center+offset).coerceIn(0,screenshot.width-1),y); Color.red(pixel)>240 && Color.green(pixel)>240 && Color.blue(pixel)>240 }
                }
                screenshot.recycle()
                if(whitePixels>30*density) return
            }
            Thread.sleep(100)
        }
        error("Timeline playhead was not painted after export dialog dismissal")
    }
    @Test fun textEditUndoRedoAndSavedProjectReopen() {
        val name = "UI workflow ${System.currentTimeMillis()}"
        ActivityScenario.launch(MainActivity::class.java).use {
            click("Blank project")
            val field = awaitNode("new project name field") { it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name) }))
            find(name)
            click("Create")
            assertNotNull(find("Add media to begin"))
            click("Text")
            click("Add text")
            awaitNode("new text clip editor") { it.isEditable && it.text?.toString() == "Your text" }
            click("Undo")
            assertNotNull(find("Add media to begin"))
            click("Redo")
            val restored = awaitNode("restored text clip editor") { it.isEditable && it.text?.toString() == "Your text" }
            check(restored.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"The next scene") }))
            click("Apply text")
            click("1080P")
            find("Resolution")
            assertResolutionPainted()
            click("1080P")
            awaitNode("text editor after export dialog dismissal") { it.isEditable && it.text?.toString() == "The next scene" }
            assertTimelinePainted()
            assertResolutionPainted()
            click("Back to projects")
            find("Projects")
            val saved = ProjectStore(instrumentation.targetContext).list().first { it.name == name }
            assertEquals("The next scene",saved.tracks.flatMap { it.clips }.first { it.text != null }.text!!.text)
            click(name)
            find("Back to projects")
            click("Project options")
            find(name)
            click("Done")
            click("Back to projects")
            find("Projects")
            click("Delete $name")
            click("Delete")
        }
    }
    private fun awaitDraw() {
        instrumentation.waitForIdleSync()
        val frames = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        check(frames.await(5,java.util.concurrent.TimeUnit.SECONDS)) { "UI did not render before gesture" }
    }
    private fun gesture(x1: Float,y1: Float,x2: Float,y2: Float,holdStart: Long = 0,holdEnd: Long = 0) {
        awaitDraw()
        val start = SystemClock.uptimeMillis()
        if(kotlin.math.abs(x2-x1)<.5f && kotlin.math.abs(y2-y1)<.5f && holdStart == 0L) {
            // Inject taps with a single down/up pair.
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x1,y1,0)
                instrumentation.uiAutomation.injectInputEvent(event,true); event.recycle()
                if(action == MotionEvent.ACTION_DOWN) Thread.sleep(20)
            }
            awaitDraw(); return
        }
        for(i in 0..12) {
            val event = MotionEvent.obtain(start,SystemClock.uptimeMillis(),when(i) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },x1+(x2-x1)*i/12,y1+(y2-y1)*i/12,0)
            instrumentation.uiAutomation.injectInputEvent(event,true); event.recycle(); Thread.sleep(20)
            if(i == 0 && holdStart>0) Thread.sleep(holdStart)
            if(i == 11 && holdEnd>0) Thread.sleep(holdEnd)
        }
        awaitDraw()
    }
    @Test fun referenceLayoutTrimAndUndoPersist() {
        val context = instrumentation.targetContext
        val store = ProjectStore(context)
        val p = Project(name = "Trim workflow ${System.currentTimeMillis()}",width = 1920,height = 1080)
        val fixture = File(context.cacheDir,"ui-reference.mp4")
        instrumentation.context.assets.open("reference.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val asset = store.importMedia(p.id,Uri.fromFile(fixture))
        val original = p.copy(assets = listOf(asset),tracks = listOf(Track(clips = listOf(Clip(assetId = asset.id,outUs = asset.durationUs)))))
        store.save(original)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                click(p.name)
                val play = Rect().also { find("Play").getBoundsInScreen(it) }
                val timeline = Rect().also { find("Timeline").getBoundsInScreen(it) }
                val screen = context.resources.displayMetrics
                val density = screen.density
                assertTrue("Playback must be centered",kotlin.math.abs(play.centerX()-screen.widthPixels/2)<4*density)
                assertTrue("Reference timeline proportion",timeline.top.toFloat()/screen.heightPixels in .55f.. .62f)
                assertTimelinePainted()
                assertResolutionPainted()
                // Exercise a real first-open tap, before edits trigger any redraw.
                val resolution = Rect().also { find("1080P").getBoundsInScreen(it) }
                gesture(resolution.centerX().toFloat(),resolution.centerY().toFloat(),resolution.centerX().toFloat(),resolution.centerY().toFloat())
                find("Frame rate"); assertResolutionPainted(); click("1080P")
                val x = screen.widthPixels/2+30*density
                val y = timeline.top+85*density
                gesture(x,y,x,y)
                find("Split")
                val handle = Rect().also { find("Trim end").getBoundsInScreen(it) }
                gesture(handle.centerX().toFloat(),handle.centerY().toFloat(),handle.centerX()-25*density,handle.centerY().toFloat())
                click("Back to projects")
                find("Projects")
                val trimmed = store.load(p.id)
                android.util.Log.i("CutLabUiTest","Trim ${original.durationUs()} -> ${trimmed.durationUs()}, handle=$handle, timeline=$timeline")
                assertTrue("Handle shortened the actual saved clip: ${original.durationUs()} -> ${trimmed.durationUs()}",trimmed.durationUs() < original.durationUs()-200_000)
                assertTrue("Handle preserves positive duration",trimmed.durationUs()>1_000_000)
                click(p.name)
                click("Project options"); find(p.name); click("Done")
                // A second trim is undone before leaving; autosave must retain the first trim.
                val again = Rect().also { find("Timeline").getBoundsInScreen(it) }
                gesture(x,again.top+85*density,x,again.top+85*density)
                val second = Rect().also { find("Trim end").getBoundsInScreen(it) }
                gesture(second.centerX().toFloat(),second.centerY().toFloat(),second.centerX()-15*density,second.centerY().toFloat())
                click("Undo")
                click("1080P"); find("Frame rate"); assertResolutionPainted(); click("1080P")
                assertTimelinePainted()
            assertResolutionPainted()
                click("Back to projects")
                find("Projects")
                assertEquals(trimmed.durationUs(),store.load(p.id).durationUs())
            }
        } finally { store.delete(p.id); fixture.delete() }
    }

    @Test fun previewDragResizeStyleUndoAndReopen() {
        val context = instrumentation.targetContext
        val store = ProjectStore(context)
        val text = Clip(outUs = 1_000_000,text = TextStyle(text = "Move me",fontSize = 120f))
        val p = Project(name = "Preview workflow ${System.currentTimeMillis()}",width = 1920,height = 1080,
            tracks = listOf(Track(kind = TrackKind.TEXT,clips = listOf(text))))
        store.save(p)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                click(p.name)
                val preview = Rect().also { find("Preview transform controls").getBoundsInScreen(it) }
                val cx = preview.exactCenterX(); val cy = preview.exactCenterY()
                gesture(cx,cy,cx+100,cy+70)
                find("Edit text")
                click("Back to projects"); find("Projects")
                val moved = store.load(p.id).tracks.first().clips.first()
                assertEquals(100f/preview.width(),moved.transform.x,.035f)
                assertEquals(70f/(preview.width()*1080f/1920),moved.transform.y,.04f)
                click(p.name)
                val m = com.dhasharma.cutlab.engine.TextRaster.measure(moved.text!!,p.width)
                val fit = preview.width().toFloat()/p.width
                val mx = cx+moved.transform.x*preview.width(); val my = cy+moved.transform.y*p.height*fit
                gesture(mx,my,mx,my)
                val hx = mx+m.width*fit/2; val hy = my+m.height*fit/2
                val dx = hx-mx; val dy = hy-my; val angle = Math.PI/8
                val ex = mx+(dx*kotlin.math.cos(angle)-dy*kotlin.math.sin(angle)).toFloat()*1.35f
                val ey = my+(dx*kotlin.math.sin(angle)+dy*kotlin.math.cos(angle)).toFloat()*1.35f
                gesture(hx,hy,ex,ey)
                click("Undo"); click("Redo")
                click("Edit text"); click("Serif")
                click("Style")
                click("Outline")
                click("Outline Black")
                click("Back to projects"); find("Projects")
                val result = store.load(p.id).tracks.first().clips.first()
                assertTrue("Corner handle scales text: ${result.transform}",result.transform.scale>1.15f)
                assertTrue("Corner handle rotates text",result.transform.rotation>10f)
                assertEquals("serif",result.text!!.fontFamily)
                assertTrue(result.text!!.outlineWidth>0)
                click(p.name); find("Preview transform controls"); click("Back to projects"); find("Projects")
                assertEquals(result,store.load(p.id).tracks.first().clips.first())
                // Retain this exact UI-edited project for export correspondence verification.
                File(context.cacheDir,"preview-edited-project.json").writeText(kotlinx.serialization.json.Json.encodeToString(Project.serializer(),store.load(p.id)))
            }
            val saved = store.load(p.id)
            val output = File(context.cacheDir,"preview-gesture-export.mp4")
            kotlinx.coroutines.runBlocking { com.dhasharma.cutlab.engine.VideoExporter(context).export(saved,output) {} }
            com.dhasharma.cutlab.engine.TimelineRenderer(context).use { renderer ->
                val preview = renderer.renderFrame(saved,400_000,640,360)
                android.media.MediaMetadataRetriever().use { reader ->
                    reader.setDataSource(output.path)
                    val frame = reader.getScaledFrameAtTime(400_000,android.media.MediaMetadataRetriever.OPTION_CLOSEST,640,360)!!
                    var error = 0L; var count = 0
                    for(y in 0 until 360) for(x in 0 until 640) {
                        val a = preview.getPixel(x,y); val b = frame.getPixel(x,y)
                        if((a and 0xFFFFFF)!=0 || (b and 0xFFFFFF)!=0) {
                            error += kotlin.math.abs(Color.red(a)-Color.red(b))+kotlin.math.abs(Color.green(a)-Color.green(b))+kotlin.math.abs(Color.blue(a)-Color.blue(b)); count += 3
                        }
                    }
                    assertTrue("UI-edited text is present in export",count>100)
                    assertTrue("UI gesture/font/outline edits survive export",error.toFloat()/count<18)
                    frame.recycle()
                }
                preview.recycle()
            }
        } finally { store.delete(p.id) }
    }

    @Test fun overlayTwoFingerScaleAndRotationPersist() {
        val context = instrumentation.targetContext
        val store = ProjectStore(context)
        val p = Project(name = "Overlay workflow ${System.currentTimeMillis()}",width = 1920,height = 1080)
        val source = File(context.cacheDir,"gesture-overlay.png")
        android.graphics.Bitmap.createBitmap(200,200,android.graphics.Bitmap.Config.ARGB_8888).let { b ->
            b.eraseColor(Color.RED); source.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; b.recycle()
        }
        val asset = store.importMedia(p.id,Uri.fromFile(source))
        val clip = Clip(assetId = asset.id,outUs = 1_000_000,transform = Transform(scale = .5f))
        store.save(p.copy(assets = listOf(asset),tracks = listOf(Track(),Track(name = "Overlay",clips = listOf(clip)))))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                click(p.name)
                val bounds = Rect().also { find("Preview transform controls").getBoundsInScreen(it) }
                val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
                awaitDraw()
                val start = SystemClock.uptimeMillis()
                val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                fun send(action: Int,count: Int,radius: Float,angle: Float) {
                    val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                        val sign = if(i == 0) -1 else 1
                        x = cx+sign*radius*kotlin.math.cos(angle); y = cy+sign*radius*kotlin.math.sin(angle); pressure = 1f; size = 1f
                    } }
                    val event = MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                    instrumentation.uiAutomation.injectInputEvent(event,true); event.recycle(); Thread.sleep(30)
                }
                send(MotionEvent.ACTION_DOWN,1,50f,0f)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,50f,0f)
                for(i in 1..16) send(MotionEvent.ACTION_MOVE,2,50f+40*i/16,0.4f*i/16)
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,90f,.4f)
                send(MotionEvent.ACTION_UP,1,90f,.4f)
                awaitDraw(); click("Undo"); click("Redo")
                click("Back to projects"); find("Projects")
                val saved = store.load(p.id).tracks.last().clips.first()
                assertTrue("Pinch scales the overlay",saved.transform.scale>.65f)
                assertTrue("Two-finger rotation persists",saved.transform.rotation>10f)
                assertEquals(0f,saved.transform.x,.03f); assertEquals(0f,saved.transform.y,.03f)
            }
        } finally { store.delete(p.id); source.delete() }
    }

    @Test fun coverPrecisionAndExportCancelThenPublish() {
        val context = instrumentation.targetContext
        val store = ProjectStore(context)
        val p = Project(name = "Completion workflow ${System.currentTimeMillis()}",width = 1280,height = 720,
            export = ExportSettings(height = 720,fps = 30),tracks = listOf(Track(kind = TrackKind.TEXT,
                clips = listOf(Clip(outUs = 30_000_000,text = TextStyle(text = "Export test",fontSize = 100f))))))
        store.save(p)
        fun shot(name: String) {
            awaitDraw()
            instrumentation.uiAutomation.takeScreenshot()?.let { b ->
                File(context.cacheDir,name).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; b.recycle()
            }
        }
        fun published(): Set<Long> = context.contentResolver.query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Video.Media._ID),"${android.provider.MediaStore.Video.Media.OWNER_PACKAGE_NAME}=? AND ${android.provider.MediaStore.Video.Media.IS_PENDING}=0",arrayOf(context.packageName),null)?.use { c ->
                buildSet { while(c.moveToNext()) add(c.getLong(0)) }
            }.orEmpty()
        val before = published()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                click(p.name)
                click("Cover"); find("Choose a frame for the project cover")
                click("Playhead position")
                repeat(30) { index -> click("Next frame"); awaitNode("frame ${index+1}") { it.text?.toString()?.contains("frame ${index+1} / 900") == true } }
                awaitNode("exact one-second frame position") { it.text?.toString()?.contains("frame 30 / 900") == true }
                click("Done"); click("Set cover")
                click("Back to projects"); find("Projects")
                assertEquals(1_000_000L,store.load(p.id).coverUs)
                click(p.name)
                click("Export")
                find("Cancel export"); assertTextPainted("Exporting…"); shot("completion-export-progress.png")
                click("Cancel export"); find("Cancel export?"); click("Stop export")
                find("Back to projects")
                assertEquals("Cancelled export must not publish a video",before,published())
                assertFalse(context.cacheDir.listFiles().orEmpty().any { f -> f.name.startsWith("cutlab-export-working-") })
                click("Back to projects"); find("Projects")
            }
            val saved = store.load(p.id)
            store.save(saved.copy(tracks = saved.tracks.map { t -> t.copy(clips = t.clips.map { c -> c.copy(outUs = 1_200_000) }) }))
            ActivityScenario.launch(MainActivity::class.java).use {
                click(p.name); click("Export")
                awaitNode("successful export",90_000) { it.text?.toString() == "Ready to share" }
                find("Saved to Movies/CutLab"); find("Share video"); find("Play exported video")
                assertTextPainted("Ready to share"); assertTextPainted("Done"); shot("completion-export-saved.png")
                val added = published()-before
                assertEquals(1,added.size)
                val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,added.first())
                android.media.MediaMetadataRetriever().use { reader -> reader.setDataSource(context,uri)
                    assertEquals("720",reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                    assertTrue(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 1150..1300)
                }
                click("Done"); find("Back to projects"); shot("completion-editor.png")
                click("Back to projects"); find("Projects")
            }
        } finally {
            (published()-before).forEach { context.contentResolver.delete(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,it),null,null) }
            store.delete(p.id)
        }
    }

    @Test fun primaryLongDragReordersAndEdgeScrolls() {
        val context = instrumentation.targetContext
        val store = ProjectStore(context)
        val p = Project(name = "Reorder workflow ${System.currentTimeMillis()}",width = 1920,height = 1080)
        val fixture = File(context.cacheDir,"reorder-reference.mp4")
        instrumentation.context.assets.open("reference.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val asset = store.importMedia(p.id,Uri.fromFile(fixture))
        val clips = (0..3).map { Clip(assetId = asset.id,startUs = it*1_500_000L,outUs = 1_500_000) }
        store.save(p.copy(assets = listOf(asset),tracks = listOf(Track(clips = clips))))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                click(p.name)
                val timeline = Rect().also { find("Timeline").getBoundsInScreen(it) }
                val metrics = context.resources.displayMetrics
                val y = timeline.top+85*metrics.density
                gesture(metrics.widthPixels/2f+10*metrics.density,y,metrics.widthPixels-8f,y,700,750)
                click("Back to projects"); find("Projects")
                val reordered = store.load(p.id).tracks.first().clips
                assertTrue("Long-press drag moves the primary clip past another clip",reordered.indexOfFirst { c -> c.id == clips.first().id }>0)
                assertEquals("Reorder retains all clips",clips.map { c -> c.id }.toSet(),reordered.map { c -> c.id }.toSet())
                assertEquals(listOf(0L,1_500_000L,3_000_000L,4_500_000L),reordered.map { c -> c.startUs })
            }
        } finally { store.delete(p.id); fixture.delete() }
    }

    @Test fun livePlaybackAdvancesPausesAndFinishes() {
        val context = instrumentation.targetContext
        val store = ProjectStore(context)
        val p = Project(name = "Playback workflow ${System.currentTimeMillis()}",width = 1920,height = 1080)
        val fixture = File(context.cacheDir,"playback-reference.mp4")
        instrumentation.context.assets.open("reference.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val asset = store.importMedia(p.id,Uri.fromFile(fixture))
        store.save(p.copy(assets = listOf(asset),tracks = listOf(Track(clips = listOf(Clip(assetId = asset.id,outUs = 3_000_000))))))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                click(p.name); click("Play"); find("Pause")
                awaitNode("playback advances past one second",15_000) { it.text?.toString() == "00:01" }
                click("Pause"); find("Play")
                click("Playhead position")
                val counter = awaitNode("paused frame counter") { it.text?.toString()?.contains(" / 90") == true }.text.toString()
                val frame = Regex("frame ([0-9]+)").find(counter)!!.groupValues[1].toInt()
                assertTrue("Pause preserves elapsed time",frame in 25..75)
                click("Done"); click("Play")
                awaitNode("playback finishes",15_000) { it.text?.toString() == "00:03" }
                find("Play"); click("Back to projects"); find("Projects")
            }
        } finally { store.delete(p.id); fixture.delete() }
    }

}
