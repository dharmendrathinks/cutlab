package com.dhasharma.cutlab.model

import org.junit.Assert.*
import org.junit.Test

class CompletionEditingTest {
    @Test fun dropReordersOnlyPrimaryAndSnapsEitherEdge() {
        val a = Clip(outUs = 1_000_000); val b = Clip(startUs = 1_000_000,outUs = 2_000_000)
        val overlay = Clip(outUs = 3_000_000)
        val p = Project(tracks = listOf(Track(clips = listOf(a,b)),Track(clips = listOf(overlay))))
        val moved = EditorOps.drop(p,a.id,3_000_000)
        assertEquals(listOf(b.id,a.id),moved.tracks.first().clips.map { it.id })
        assertEquals(listOf(0L,2_000_000L),moved.tracks.first().clips.map { it.startUs })
        assertEquals(1_250_000,EditorOps.drop(p,overlay.id,1_250_000).tracks.last().clips.first().startUs)
        assertEquals(2_000_000,snapClipStart(1_950_000,1_000_000,listOf(3_000_000),100_000))
        assertEquals(3_000_000,snapClipStart(2_950_000,1_000_000,listOf(3_000_000),100_000))
    }
    @Test fun freezeInsertsSilentStillWithoutLosingSourceOrOverlayTiming() {
        val asset = MediaAsset(path = "source.mp4",name = "Video",kind = AssetKind.VIDEO,durationUs = 8_000_000)
        val c = Clip(assetId = asset.id,inUs = 2_000_000,outUs = 6_000_000,speed = 2f)
        val next = Clip(assetId = asset.id,startUs = 2_000_000,outUs = 1_000_000)
        val overlay = Track(kind = TrackKind.TEXT,clips = listOf(Clip(startUs = 500_000,text = TextStyle())))
        val p = Project(assets = listOf(asset),tracks = listOf(Track(clips = listOf(c,next)),overlay))
        val result = EditorOps.insertFreeze(p,c.id,1_000_000)
        val clips = result.tracks.first().clips
        assertEquals(4,clips.size); assertEquals(6_000_000,result.tracks.first().clips.last().startUs+clips.last().durationUs())
        assertEquals(4_000_000L,clips[1].freezeUs); assertEquals(3_000_000,clips[1].durationUs()); assertTrue(clips[1].muted)
        assertEquals(clips[0].outUs,clips[2].inUs); assertEquals(overlay,result.tracks.last())
        assertEquals(listOf(0L,1_000_000L,4_000_000L,5_000_000L),clips.map { it.startUs })
    }
    @Test fun framePrecisionAndCatalogDefaultsRoundTrip() {
        assertEquals(33_333,frameTime(20_000,30)); assertEquals(0,frameTime(-1000,30))
        val p = Project(coverUs = 300_000,background = 0xFF173354,tracks = listOf(Track(clips = listOf(Clip(effect = VideoEffect(EffectKind.MOSAIC,.6f),mask = Mask(MaskKind.DIAMOND))))))
        assertEquals(p,ProjectFiles.decode(ProjectFiles.encode(p)))
        assertTrue(localFilters.size>=8)
    }
    @Test fun freezePreservesFollowingTransitionOverlap() {
        val asset = MediaAsset(path = "v",name = "v",kind = AssetKind.VIDEO,durationUs = 5_000_000)
        val first = Clip(assetId = asset.id,outUs = 2_000_000)
        val second = Clip(assetId = asset.id,startUs = 1_500_000,outUs = 2_000_000,transition = Transition(TransitionKind.DISSOLVE,500_000))
        val p = Project(assets = listOf(asset),tracks = listOf(Track(clips = listOf(first,second))))
        val result = EditorOps.insertFreeze(p,first.id,1_000_000)
        assertEquals(4_500_000L,result.tracks.first().clips.last().startUs)
        assertEquals(second.transition,result.tracks.first().clips.last().transition)
        val incoming = EditorOps.insertFreeze(p,second.id,second.startUs).tracks.first().clips
        assertEquals(second.transition,incoming[1].transition)
        assertEquals(TransitionKind.NONE,incoming[2].transition.kind)
        assertEquals(4_500_000L,incoming[2].startUs)
    }

}
