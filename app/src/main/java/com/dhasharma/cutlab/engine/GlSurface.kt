package com.dhasharma.cutlab.engine

import android.opengl.*
import android.view.Surface
import java.io.Closeable

/** An application-owned GLES2 context; encoder surfaces use the recordable config. */
internal class GlSurface(width: Int, height: Int, window: Surface? = null) : Closeable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface
    init {
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val attrs = mutableListOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE, if(window == null) EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT)
        if (window != null) attrs.addAll(listOf(0x3142,1))
        attrs.add(EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display,attrs.toIntArray(),0,configs,0,1,count,0) && count[0]>0) { "No compatible OpenGL ES configuration" }
        context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        surface = if(window == null) EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,width,EGL14.EGL_HEIGHT,height,EGL14.EGL_NONE),0) else EGL14.eglCreateWindowSurface(display,configs[0],window,intArrayOf(EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
        current()
    }
    fun current() { check(EGL14.eglMakeCurrent(display,surface,surface,context)) { "Cannot bind OpenGL surface" } }
    fun detach() { check(EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)) }
    fun present(timeNs: Long) { EGLExt.eglPresentationTimeANDROID(display,surface,timeNs); check(EGL14.eglSwapBuffers(display,surface)) }
    override fun close() {
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context)
        // eglTerminate is display-global: other preview/export contexts can still be alive.
        EGL14.eglReleaseThread()
    }
}

internal fun glProgram(vertex: String, fragment: String): Int {
    fun shader(kind:Int, source:String):Int {
        val id=GLES20.glCreateShader(kind); GLES20.glShaderSource(id,source); GLES20.glCompileShader(id)
        val ok=IntArray(1); GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0)
        check(ok[0]!=0) { GLES20.glGetShaderInfoLog(id) }; return id
    }
    val v=shader(GLES20.GL_VERTEX_SHADER,vertex); val f=shader(GLES20.GL_FRAGMENT_SHADER,fragment)
    val p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p)
    GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
    val ok=IntArray(1); GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0)
    check(ok[0]!=0) { GLES20.glGetProgramInfoLog(p) }; return p
}
