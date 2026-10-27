package com.dhasharma.cutlab.engine

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.opengl.GLES20
import android.opengl.GLUtils
import com.dhasharma.cutlab.model.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Blocking renderer: call off the UI thread. The same GLSL composition is used for export. */
class TimelineRenderer(@Suppress("UNUSED_PARAMETER") context: Context, private val outputSurface: android.view.Surface? = null) : Closeable {
    private var egl: GlSurface? = null
    private var size = 0 to 0
    private var program = 0
    private var texture = 0
    private val retrievers = object : LinkedHashMap<String,MediaMetadataRetriever>(8,.75f,true) {}
    private val images = object : LinkedHashMap<String,Bitmap>(4,.75f,true) {}
    private val previewDecoders = LinkedHashMap<String,PreviewVideoDecoder>(4,.75f,true)
    private val previewFallback = mutableSetOf<String>()
    var previewDecodedFrames: Int = 0; private set
    var previewFallbackFrames: Int = 0; private set
    private val videoFrames = LinkedHashMap<String,Bitmap>(8,.75f,true)
    var frameCacheHits: Int = 0; private set
    val cachedFrameBytes: Int get() = videoFrames.values.sumOf { it.allocationByteCount }
    private fun videoFrame(path: String,time: Long,w: Int,h: Int): Bitmap {
        val key = "$path:$time:$w:$h"
        videoFrames[key]?.let { frameCacheHits++; return it }
        var decoded: Bitmap? = null
        if(outputSurface == null && path !in previewFallback) {
            try {
                val decoder = previewDecoders.getOrPut(path) {
                    if(previewDecoders.size>=3) { val oldest = previewDecoders.entries.first(); oldest.value.close(); previewDecoders.remove(oldest.key) }
                    PreviewVideoDecoder(path)
                }
                val before = decoder.decodedFrames
                decoded = decoder.frameAt(time,w,h)
                previewDecodedFrames += decoder.decodedFrames-before
            } catch(error: Exception) {
                previewDecoders.remove(path)?.close(); previewFallback.add(path)
                android.util.Log.w("CutLabPreview","Using platform frame extraction for this source",error)
            }
        }
        if(decoded == null && outputSurface == null) previewFallbackFrames++
        val frame = decoded ?: retriever(path).getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,w,h) ?: error("Cannot decode video frame")
        if(outputSurface == null && frame.allocationByteCount<=16*1024*1024) {
            while(videoFrames.isNotEmpty() && cachedFrameBytes+frame.allocationByteCount>16*1024*1024) {
                val oldest = videoFrames.entries.first(); oldest.value.recycle(); videoFrames.remove(oldest.key)
            }
            videoFrames[key] = frame
        }
        return frame
    }
    private val attributeBuffers = mutableListOf<java.nio.FloatBuffer>()
    private var readback = ByteBuffer.allocateDirect(0)
    private var readbackColors = IntArray(0)

    @Synchronized fun renderFrame(project: Project, timeUs: Long, width: Int, height: Int): Bitmap {
        check(outputSurface==null) { "Use renderToEncoder on an encoder-backed renderer" }
        try {
            drawScene(project,timeUs,width,height)
        if(readback.capacity()<width*height*4) readback=ByteBuffer.allocateDirect(width*height*4)
        val pixels=readback.apply { clear(); order(ByteOrder.nativeOrder()) }
        GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
        check(GLES20.glGetError()==GLES20.GL_NO_ERROR) { "OpenGL frame read failed" }
        if(readbackColors.size!=width*height) readbackColors=IntArray(width*height)
        val rgba=readbackColors
        pixels.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(rgba)
        fun argb(value:Int)=(value and 0xFF00FF00.toInt()) or ((value and 0xFF) shl 16) or ((value ushr 16) and 0xFF)
        for(y in 0 until (height+1)/2) for(x in 0 until width) {
            val top=y*width+x; val bottom=(height-y-1)*width+x
            val a=rgba[top]; val b=rgba[bottom]
            rgba[top]=argb(b); rgba[bottom]=argb(a)
        }
        return Bitmap.createBitmap(rgba,width,height,Bitmap.Config.ARGB_8888)
        } finally { egl?.detach() }
    }
    /** Identical composition directly into the codec input surface; no CPU readback or reupload. */
    @Synchronized internal fun renderToEncoder(project:Project,timeUs:Long,width:Int,height:Int,presentationNs:Long) {
        check(outputSurface!=null)
        try { drawScene(project,timeUs,width,height); check(GLES20.glGetError()==GLES20.GL_NO_ERROR) { "OpenGL encoder composition failed" }; egl!!.present(presentationNs) }
        finally { egl?.detach() }
    }
    private fun drawScene(project:Project,timeUs:Long,width:Int,height:Int) {
        require(width > 0 && height > 0)
        if(size != width to height) {
            releaseGl(); egl=GlSurface(width,height,outputSurface); size=width to height
            program=glProgram(VERTEX,FRAGMENT)
            val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); texture=ids[0]
        }
        egl!!.current(); GLES20.glViewport(0,0,width,height)
        val background = project.background.toInt()
        GLES20.glClearColor(Color.red(background)/255f,Color.green(background)/255f,Color.blue(background)/255f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        project.tracks.filter { it.kind != TrackKind.AUDIO }.forEach { track ->
            val sorted=track.clips.sortedBy { it.startUs }
            sorted.forEachIndexed { index,clip ->
                if(timeUs < clip.startUs || timeUs >= clip.startUs+clip.durationUs()) return@forEachIndexed
                val local=timeUs-clip.startUs
                val previous=sorted.getOrNull(index-1)
                val overlap=previous?.let { (it.startUs+it.durationUs()-clip.startUs).coerceAtLeast(0) }
                val transitionDuration=min(min(clip.transition.durationUs,clip.durationUs()),overlap ?: clip.durationUs())
                val transitioning=clip.transition.kind!=TransitionKind.NONE && transitionDuration>0 && local<transitionDuration
                val progress=if(transitioning) (local.toFloat()/transitionDuration).coerceIn(0f,1f) else 1f
                val following=sorted.getOrNull(index+1)
                val nextLocal=following?.let { timeUs-it.startUs } ?: -1
                val nextDuration=following?.let { min(min(it.transition.durationUs,it.durationUs()),(clip.startUs+clip.durationUs()-it.startUs).coerceAtLeast(0)) } ?: 0
                val nextTransition=if(following!=null && nextLocal>=0 && nextLocal<nextDuration) following.transition.kind else TransitionKind.NONE
                val nextProgress=if(nextDuration>0) nextLocal.toFloat()/nextDuration else 1f
                if(transitioning && (clip.transition.kind==TransitionKind.BLACK || clip.transition.kind==TransitionKind.WHITE) && progress<.5f) return@forEachIndexed
                if(nextTransition==TransitionKind.BLACK || nextTransition==TransitionKind.WHITE) {
                    if(nextProgress<.5f) drawClip(project,clip,local,width,height,1f-nextProgress*2,nextTransition)
                } else if(nextTransition in listOf(TransitionKind.SLIDE_LEFT,TransitionKind.SLIDE_RIGHT,TransitionKind.SLIDE_UP,TransitionKind.SLIDE_DOWN)) {
                    drawClip(project,clip,local,width,height,nextProgress,nextTransition,true)
                } else {
                    val entranceProgress=if(clip.transition.kind==TransitionKind.BLACK || clip.transition.kind==TransitionKind.WHITE) (progress*2-1).coerceIn(0f,1f) else progress
                    drawClip(project,clip,local,width,height,entranceProgress,if(transitioning) clip.transition.kind else TransitionKind.NONE)
                }
            }
        }
    }
    private fun drawClip(project:Project,clip:Clip,local:Long,w:Int,h:Int,progress:Float,transition:TransitionKind,outgoing:Boolean=false) {
        val asset=project.assets.find { it.id==clip.assetId }
        val bitmap=if(clip.text!=null) TextRaster.bitmap(clip.text!!, project.width) else when(asset?.kind) {
            AssetKind.IMAGE -> image(asset.path,w,h)
            AssetKind.VIDEO -> videoFrame(asset.path,clip.sourceTimeUs(local),w,h)
            else -> return
        }
        try {
            val t=clip.visualTransformAt(local)
            var scale=t.scale; var ox=t.x; var oy=t.y; var rotation=t.rotation
            if(transition==TransitionKind.ZOOM) scale*=.5f+.5f*progress
            if(transition==TransitionKind.SLIDE_LEFT) ox+=if(outgoing) -progress else 1-progress
            if(transition==TransitionKind.SLIDE_RIGHT) ox-=if(outgoing) -progress else 1-progress
            if(transition==TransitionKind.SLIDE_UP) oy+=if(outgoing) -progress else 1-progress
            if(transition==TransitionKind.SLIDE_DOWN) oy-=if(outgoing) -progress else 1-progress
            if(transition==TransitionKind.SPIN) { rotation += (1-progress)*180; scale *= .5f+.5f*progress }
            val fit=if(clip.text!=null) w.toFloat()/project.width else min(w.toFloat()/bitmap.width,h.toFloat()/bitmap.height)
            val bw=bitmap.width*(1-t.cropLeft-t.cropRight).coerceAtLeast(.01f)*fit*scale; val bh=bitmap.height*(1-t.cropTop-t.cropBottom).coerceAtLeast(.01f)*fit*scale
            val rad=rotation*PI.toFloat()/180f
            val points=floatArrayOf(-.5f,-.5f,.5f,-.5f,-.5f,.5f,.5f,.5f)
            val verts=FloatArray(8)
            for(i in 0..3) {
                val x=points[i*2]*bw; val y=points[i*2+1]*bh
                verts[i*2]=(x*cos(rad)-y*sin(rad))/w*2+ox*2
                verts[i*2+1]=-(x*sin(rad)+y*cos(rad))/h*2-oy*2
            }
            attributeBuffers.clear()
            attr("position",verts)
            val left=if(t.flipX) 1-t.cropRight else t.cropLeft
            val right=if(t.flipX) t.cropLeft else 1-t.cropRight
            val top=if(t.flipY) 1-t.cropBottom else t.cropTop
            val bottom=if(t.flipY) t.cropTop else 1-t.cropBottom
            attr("texCoord",floatArrayOf(left,top,right,top,left,bottom,right,bottom))
            attr("localCoord",floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f))
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
            GLES20.glUniform1i(location("image"),0)
            uniform("opacity",t.opacity * if(transition==TransitionKind.DISSOLVE || transition==TransitionKind.ZOOM || transition==TransitionKind.SPIN) progress else 1f)
            uniform("exposure",clip.color.exposure); uniform("contrast",clip.color.contrast); uniform("saturation",clip.color.saturation)
            uniform("temperature",clip.color.temperature); uniform("tint",clip.color.tint); uniform("sharpen",clip.color.sharpen)
            GLES20.glUniform2f(location("pixel"),1f/(asset?.width?.takeIf { it>0 } ?: bitmap.width),1f/(asset?.height?.takeIf { it>0 } ?: bitmap.height))
            uniform("effect",clip.effect.kind.ordinal.toFloat()); uniform("effectAmount",clip.effect.intensity.coerceIn(0f,1f))
            uniform("mask",clip.mask.kind.ordinal.toFloat()); uniform("feather",clip.mask.feather)
            uniform("chroma",if(clip.chromaKey.enabled) 1f else 0f)
            val key=clip.chromaKey.color.toInt()
            GLES20.glUniform3f(location("keyColor"),Color.red(key)/255f,Color.green(key)/255f,Color.blue(key)/255f)
            uniform("similarity",clip.chromaKey.similarity); uniform("smoothness",clip.chromaKey.smoothness)
            uniform("wipe",if(transition==TransitionKind.WIPE) progress else 1f)
            uniform("fade",if(transition==TransitionKind.BLACK || transition==TransitionKind.WHITE) progress else 1f)
            uniform("fadeColor",if(transition==TransitionKind.WHITE) 1f else 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        } finally { if(clip.text!=null || (asset?.kind==AssetKind.VIDEO && (outputSurface!=null || bitmap.allocationByteCount>16*1024*1024))) bitmap.recycle() }
    }
    private fun location(name:String)=GLES20.glGetUniformLocation(program,name)
    private fun uniform(name:String,value:Float)=GLES20.glUniform1f(location(name),value)
    private fun attr(name:String,values:FloatArray) {
        val b=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer(); b.put(values).position(0); attributeBuffers.add(b)
        val id=GLES20.glGetAttribLocation(program,name); GLES20.glEnableVertexAttribArray(id); GLES20.glVertexAttribPointer(id,2,GLES20.GL_FLOAT,false,0,b)
    }
    private fun retriever(path:String):MediaMetadataRetriever = retrievers.getOrPut(path) {
        if(retrievers.size>=6) { val entry=retrievers.entries.first(); entry.value.release(); retrievers.remove(entry.key) }
        MediaMetadataRetriever().apply { setDataSource(path) }
    }
    private fun image(path:String,w:Int,h:Int):Bitmap = images.getOrPut(path) {
        if(images.size>=4) { val entry=images.entries.first(); entry.value.recycle(); images.remove(entry.key) }
        val o=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(path,o)
        var sample=1; while(o.outWidth/sample>w*2 || o.outHeight/sample>h*2) sample*=2
        BitmapFactory.decodeFile(path,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Cannot read image: $path")
    }
    private fun releaseGl() { egl?.let { it.current(); GLES20.glDeleteProgram(program); GLES20.glDeleteTextures(1,intArrayOf(texture),0); it.close() }; egl=null }
    @Synchronized override fun close() { releaseGl(); previewDecoders.values.forEach { it.close() }; previewDecoders.clear(); retrievers.values.forEach { it.release() }; retrievers.clear(); images.values.forEach { it.recycle() }; images.clear(); videoFrames.values.forEach { it.recycle() }; videoFrames.clear(); size=0 to 0 }
    companion object {
        private const val VERTEX="""attribute vec2 position; attribute vec2 texCoord; attribute vec2 localCoord; varying vec2 uv; varying vec2 local; void main(){gl_Position=vec4(position,0.,1.);uv=texCoord;local=localCoord;}"""
        private const val FRAGMENT="""
            precision highp float; uniform sampler2D image; varying vec2 uv; varying vec2 local;
            uniform float effect,effectAmount;
            uniform float opacity,exposure,contrast,saturation,temperature,tint,sharpen,mask,feather,chroma,similarity,smoothness,wipe,fade,fadeColor;
            uniform vec2 pixel; uniform vec3 keyColor;
            void main(){
                vec2 coord=uv;
                if(effect>3.5 && effect<4.5) {float cells=mix(160.,12.,effectAmount);coord=(floor(uv*cells)+.5)/cells;}
                vec4 sampleColor=texture2D(image,coord);
                if(effect>4.5) {vec2 blur=pixel*(1.+effectAmount*12.);sampleColor=(sampleColor*4.+texture2D(image,uv+vec2(blur.x,0.))+texture2D(image,uv-vec2(blur.x,0.))+texture2D(image,uv+vec2(0.,blur.y))+texture2D(image,uv-vec2(0.,blur.y)))/8.;} float alpha=sampleColor.a;
                vec3 c=alpha>0.0001?sampleColor.rgb/alpha:vec3(0.);
                if(chroma>0.5) alpha*=smoothstep(similarity,similarity+max(smoothness,.001),distance(c,keyColor)/1.732);
                if(sharpen>0.) {vec3 around=(texture2D(image,uv+vec2(pixel.x,0.)).rgb+texture2D(image,uv-vec2(pixel.x,0.)).rgb+texture2D(image,uv+vec2(0.,pixel.y)).rgb+texture2D(image,uv-vec2(0.,pixel.y)).rgb)*.25;c+=(c-around)*sharpen*2.;}
                c*=pow(2.,exposure); c=(c-.5)*contrast+.5; c=mix(vec3(dot(c,vec3(.2126,.7152,.0722))),c,saturation);c+=vec3(temperature*.2,-tint*.15,-temperature*.2);
                if(effect>.5 && effect<1.5) c=mix(c,vec3(dot(c,vec3(.299,.587,.114))),effectAmount);
                if(effect>1.5 && effect<2.5) {vec3 sepia=vec3(dot(c,vec3(.393,.769,.189)),dot(c,vec3(.349,.686,.168)),dot(c,vec3(.272,.534,.131)));c=mix(c,sepia,effectAmount);}
                if(effect>2.5 && effect<3.5) c*=1.-effectAmount*smoothstep(.25,.75,length(local-.5));
                float edge=1.;
                if(mask>.5 && mask<1.5) edge=min(min(local.x,1.-local.x),min(local.y,1.-local.y))*2.;
                if(mask>1.5 && mask<2.5) edge=1.-length((local-.5)*2.);
                if(mask>2.5 && mask<3.5) edge=(.5-local.y)*2.;
                if(mask>3.5 && mask<4.5) edge=1.-abs(local.x-.5)*2.-abs(local.y-.5)*2.;
                if(mask>4.5) edge=.5-abs(local.y-.5)*2.;
                if(mask>.5) alpha*=smoothstep(0.,max(feather,.001),edge);
                alpha*=step(local.x,wipe)*opacity; c=mix(vec3(fadeColor),c,fade); gl_FragColor=vec4(clamp(c,0.,1.)*alpha,alpha);
            }
        """
    }
}
