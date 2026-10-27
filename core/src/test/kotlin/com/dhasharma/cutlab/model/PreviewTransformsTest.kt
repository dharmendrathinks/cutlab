package com.dhasharma.cutlab.model

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class PreviewTransformsTest {
    @Test fun pinchKeepsAnchorAndUsesCanvasAspect() {
        val t = Transform(x = .25f).gesture(0f,0f,2f,90f,0f,0f,2f)
        assertEquals(0f,t.x,.0001f); assertEquals(1f,t.y,.0001f)
        assertEquals(2f,t.scale,.0001f)
        val centered = Transform(x = .2f,y = -.1f).gesture(.1f,.2f,3f,40f,.2f,-.1f,2f)
        assertEquals(.3f,centered.x,.0001f); assertEquals(.1f,centered.y,.0001f)
    }
    @Test fun gestureCommitPreservesKeysAndIsOneUndoStep() {
        val c = Clip(keyframes = listOf(Keyframe(1_000_000,Transform(x = .5f))))
        val p = Project(tracks = listOf(Track(clips = listOf(c))))
        val history = EditorHistory(p)
        val value = c.transformAt(500_000).gesture(.1f,0f,1.5f,20f)
        history.apply("Gesture") { EditorOps.updateClip(it,c.id) { clip -> clip.withTransformAt(500_000,value) } }
        val saved = history.project.tracks.first().clips.first()
        assertEquals(value,saved.transformAt(500_000)); assertEquals(c.keyframes.first(),saved.keyframes.last())
        history.undo(); assertEquals(p,history.project); assertFalse(history.canUndo)
        history.redo(); assertEquals(saved,history.project.tracks.first().clips.first())
    }
    @Test fun oldTextLoadsAndAnimationIsBoundedOnShortClips() {
        val old = Json.decodeFromString<TextStyle>("""{"text":"Legacy","fontSize":56.0}""")
        assertEquals("sans-serif",old.fontFamily); assertEquals(TextAnimation.NONE,old.entrance)
        val c = Clip(outUs = 200_000,text = old.copy(entrance = TextAnimation.POP,exit = TextAnimation.FADE))
        assertEquals(0f,c.visualTransformAt(0).opacity,.001f)
        assertEquals(1f,c.visualTransformAt(100_000).opacity,.001f)
        assertEquals(.5f,c.visualTransformAt(150_000).opacity,.001f)
        assertEquals(0f,c.visualTransformAt(200_000).opacity,.001f)
    }
}
