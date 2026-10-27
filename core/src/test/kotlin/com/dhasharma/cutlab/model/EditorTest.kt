package com.dhasharma.cutlab.model

import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.*
import org.junit.Test

class EditorTest {
    private fun project(clip: Clip) = Project(tracks = listOf(Track(clips = listOf(clip))))
    @Test fun constantSpeedReverseAndFreeze() {
        val clip = Clip(inUs = 2_000_000,outUs = 8_000_000,speed = 2f)
        assertEquals(3_000_000,clip.durationUs())
        assertEquals(4_000_000,clip.sourceTimeUs(1_000_000))
        assertEquals(6_000_000,clip.copy(reverse = true).sourceTimeUs(1_000_000))
        assertEquals(7_999_999,clip.sourceTimeUs(Long.MAX_VALUE))
        assertEquals(2_000_000,clip.sourceTimeUs(-1))
        assertEquals(3_500_000,clip.copy(freezeUs = 3_500_000).sourceTimeUs(2_900_000))
    }
    @Test fun speedRampUsesIntegralAndInverse() {
        val clip = Clip(outUs = 10_000_000,speedCurve = listOf(SpeedPoint(0f,1f),SpeedPoint(1f,3f)))
        val expected = 10_000_000*ln(3.0)/2
        assertTrue(abs(clip.durationUs()-expected) < 1)
        val timeAtHalfSource = (10_000_000*ln(2.0)/2).toLong()
        assertTrue(abs(clip.sourceTimeUs(timeAtHalfSource)-5_000_000) <= 3)
        val reverse = clip.copy(reverse = true)
        assertTrue(abs(reverse.sourceTimeUs(clip.durationUs()-timeAtHalfSource)-5_000_000) <= 4)
    }
    @Test fun splitPreservesRampAndTransformsForwardAndReverse() {
        for (reverse in listOf(false,true)) {
            val clip = Clip(inUs = 1_000_000,outUs = 11_000_000,reverse = reverse,
                speedCurve = listOf(SpeedPoint(0f,.5f),SpeedPoint(.3f,2f),SpeedPoint(1f,1f)),
                keyframes = listOf(Keyframe(4_000_000,Transform(x = .8f,scale = 2f))))
            val splitAt = clip.durationUs()/3
            val split = EditorOps.split(project(clip),clip.id,splitAt).tracks.single().clips
            assertEquals(2,split.size)
            assertTrue(abs(split.sumOf { it.durationUs() }-clip.durationUs()) <= 5)
            for (time in listOf(0L,splitAt/2,splitAt,splitAt+100_000,clip.durationUs()-1000)) {
                val part = if (time < splitAt) split[0] else split[1]
                assertTrue(abs(part.sourceTimeUs(time-part.startUs)-clip.sourceTimeUs(time)) <= 6,"reverse=$reverse time=$time")
                assertEquals(clip.transformAt(time).x,part.transformAt(time-part.startUs).x,.00001f)
            }
            assertNotEquals(split[0].id,split[1].id)
        }
    }
    @Test fun frozenSplitKeepsTheSameAssetFrame() {
        val clip = Clip(outUs = 8_000_000,freezeUs = 7_000_000)
        val parts = EditorOps.split(project(clip),clip.id,2_000_000).tracks.single().clips
        assertEquals(clip.durationUs(),parts.sumOf { it.durationUs() })
        parts.forEach { assertEquals(7_000_000,it.sourceTimeUs(0)) }
    }
    @Test fun trimPreservesRampWithinSourceRange() {
        val clip = Clip(inUs = 0,outUs = 10_000_000,speedCurve = listOf(SpeedPoint(0f,1f),SpeedPoint(1f,2f)))
        val trimmed = EditorOps.trim(project(clip),clip.id,2_000_000,8_000_000).tracks.single().clips.single()
        assertEquals(2_000_000,trimmed.sourceTimeUs(0))
        val offset = clip.elapsedAtSourceFraction(.2).toLong()
        assertTrue(abs(clip.sourceTimeUs(offset+1_000_000)-trimmed.sourceTimeUs(1_000_000)) < 3)
        assertFailsWith<IllegalArgumentException> { EditorOps.trim(project(clip),clip.id,-1,10) }
    }
    @Test fun historyBranchesAndIgnoresNoops() {
        val initial = Project(name = "A")
        val history = EditorHistory(initial)
        history.apply("noop") { it }
        assertFalse(history.canUndo)
        history.apply("rename") { it.copy(name = "B") }
        history.undo(); assertEquals("A",history.project.name); assertTrue(history.canRedo)
        history.redo(); assertEquals("B",history.project.name)
        history.undo(); history.apply("branch") { it.copy(name = "C") }
        assertFalse(history.canRedo)
    }
    @Test fun trimCanRestoreBoundsAndExtendStillImages() {
        val asset = MediaAsset(path = "video.mp4",name = "source",kind = AssetKind.VIDEO,durationUs = 10_000_000)
        val clip = Clip(assetId = asset.id,inUs = 2_000_000,outUs = 8_000_000,speedCurve = listOf(SpeedPoint(0f,1f),SpeedPoint(1f,2f)))
        val p = project(clip).copy(assets = listOf(asset))
        val restored = EditorOps.trim(p,clip.id,0,10_000_000).tracks.single().clips.single()
        assertEquals(0,restored.sourceTimeUs(0))
        assertTrue(restored.durationUs() > clip.durationUs())
        val offset = restored.elapsedAtSourceFraction(.2).toLong()
        assertTrue(abs(restored.sourceTimeUs(offset+1_000_000)-clip.sourceTimeUs(1_000_000)) < 3)
        assertFailsWith<IllegalArgumentException> { EditorOps.trim(p,clip.id,0,11_000_000) }
        val image = asset.copy(kind = AssetKind.IMAGE)
        val extended = EditorOps.trim(p.copy(assets = listOf(image)),clip.id,0,30_000_000)
        assertEquals(30_000_000,extended.tracks.single().clips.single().outUs)
    }
    @Test fun movingDuplicationRemovalAndReorder() {
        val a = Clip(outUs = 1_000_000)
        val b = Clip(startUs = 1_000_000,outUs = 2_000_000)
        val track = Track(clips = listOf(a,b)); val other = Track(name = "Overlay")
        val p = Project(tracks = listOf(track,other))
        val duplicated = EditorOps.duplicate(p,a.id)
        assertEquals(3,duplicated.tracks.first().clips.size)
        assertEquals(2,EditorOps.remove(duplicated,a.id).tracks.first().clips.size)
        val moved = EditorOps.move(p,a.id,3_000_000,other.id)
        assertEquals(1,moved.tracks.first().clips.size)
        assertEquals(3_000_000,moved.tracks.last().clips.single().startUs)
        val reordered = EditorOps.reorder(p,track.id,b.id,0).tracks.first().clips
        assertEquals(b.id,reordered.first().id)
        assertEquals(2_000_000,reordered.last().startUs)
    }
    @Test fun recoveryRoundTripAndTraversalProtection() {
        val directory = Files.createTempDirectory("cutlab-test").toFile()
        try {
            val original = Project(name = "first",assets = listOf(MediaAsset(path = "/missing/media.mp4",name = "missing",kind = AssetKind.VIDEO)))
            ProjectFiles.save(directory,original)
            assertEquals(original,ProjectFiles.load(directory))
            ProjectFiles.save(directory,original.copy(name = "second"))
            directory.resolve("project.json").writeText("incomplete write")
            assertEquals(original,ProjectFiles.load(directory))
            ProjectFiles.save(directory,original.copy(name = "third"))
            directory.resolve("project.json").writeText("bad again")
            assertEquals(original,ProjectFiles.load(directory))
            assertFailsWith<IllegalArgumentException> { ProjectFiles.safeChild(directory,"../outside") }
            assertFailsWith<IllegalArgumentException> { ProjectFiles.safeChild(directory,"/tmp/outside") }
            assertEquals(directory.resolve("media/a.png").canonicalFile,ProjectFiles.safeChild(directory,"media/a.png"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun boundsAndDuplicateCurvePositionsAreSafe() {
        val clip = Clip(speed = Float.NaN,speedCurve = listOf(SpeedPoint(0f,0f),SpeedPoint(0f,1f),SpeedPoint(1f,Float.NaN)))
        assertEquals(5_000_000,clip.durationUs())
        assertTrue(clip.sourceTimeUs(Long.MAX_VALUE) < clip.outUs)
        assertEquals(0,Project(tracks = emptyList()).durationUs())
    }
    @Test fun transitionReflowOverlapsMovingSourcesWithoutAffectingOverlays() {
        val a = Clip(outUs = 3_000_000)
        val b = Clip(startUs = 3_000_000,outUs = 3_000_000,transition = Transition(TransitionKind.DISSOLVE,500_000))
        val main = Track(clips = listOf(a,b))
        val overlay = Track(clips = listOf(Clip(startUs = 500_000,outUs = 1_000_000)))
        val result = EditorOps.reflow(Project(tracks = listOf(main,overlay)),main.id)
        val second = result.tracks.first().clips[1]
        assertEquals(2_500_000,second.startUs)
        assertEquals(5_500_000,result.durationUs())
        assertEquals(overlay,result.tracks[1])
        assertEquals(2_750_000,a.sourceTimeUs(2_750_000))
        assertEquals(250_000,second.sourceTimeUs(2_750_000-second.startUs))
        assertFailsWith<IllegalArgumentException> { EditorOps.split(result,b.id,2_750_000) }
        val split = EditorOps.split(result,b.id,4_000_000)
        assertEquals(result.durationUs(),split.durationUs())
        assertEquals(TransitionKind.NONE,split.tracks.first().clips.last().transition.kind)
        val reordered = EditorOps.reorder(result,main.id,b.id,0).tracks.first().clips
        assertEquals(0,reordered.first().startUs)
        assertEquals(3_000_000,reordered.last().startUs)
    }
}
