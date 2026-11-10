package com.lumina.hdr

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Experimental inverse tone mapping. Never mark output validated without independent bitstream checks. */
class HdrTranscoder(private val context:Context) {
 fun convert(
    uri: Uri,
    output: FileDescriptor,
    audioUri: Uri?,
    progress: (String, Int) -> Unit
) {
  val extractor=MediaExtractor(); var decoder:MediaCodec?=null; var encoder:MediaCodec?=null
  var muxer:MediaMuxer?=null; var gl:GlPipe?=null; var texture:SurfaceTexture?=null; var decodeSurface:Surface?=null
  val thread=HandlerThread("lumina-frame").apply { start() }
  try {
   context.contentResolver.openFileDescriptor(uri,"r")!!.use { extractor.setDataSource(it.fileDescriptor) }
   var track=-1
   for(i in 0 until extractor.trackCount) if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true) { track=i;break }
   require(track>=0) { "Tiada trek video" }
   val source=extractor.getTrackFormat(track); val mime=source.getString(MediaFormat.KEY_MIME)!!
   val width=source.getInteger(MediaFormat.KEY_WIDTH); val height=source.getInteger(MediaFormat.KEY_HEIGHT)
   require(width>0 && height>0) { "Resolusi tidak sah" }
   val color=source.getInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
   require(color!=MediaFormat.COLOR_TRANSFER_ST2084 && color!=MediaFormat.COLOR_TRANSFER_HLG) { "Input sudah HDR; pilih video SDR" }
   val fps=source.getInteger(MediaFormat.KEY_FRAME_RATE,30).coerceIn(1,60)
   val outW=if(width>=height) 1920 else 1080; val outH=if(width>=height) 1080 else 1920
   val info=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { c -> !c.isEncoder && c.supportedTypes.any { it.equals(mime,true) } }
   require(info!=null) { "Decoder tidak tersedia" }
   val encoderInfo=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { c -> c.isEncoder && c.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC,true) } && runCatching { c.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any { it.profile==MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 } }.getOrDefault(false) }
   require(encoderInfo!=null) { "Encoder HEVC Main10 HDR10 tidak diiklankan oleh peranti" }
   val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC,outW,outH).apply {
    setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
    setInteger(MediaFormat.KEY_BIT_RATE,12_000_000);setInteger(MediaFormat.KEY_FRAME_RATE,fps);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2)
    setInteger(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.COLOR_STANDARD_BT2020)
    setInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_ST2084)
    setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
    setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, staticHdrInfo())
   }
   encoder=MediaCodec.createByCodecName(encoderInfo.name)
   encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
   val encodeSurface=encoder.createInputSurface()
   gl=GlPipe(encodeSurface,outW,outH)
   val glPipe=gl
   texture=SurfaceTexture(glPipe.textureId).apply { setDefaultBufferSize(width,height) }
   val frameReady=AtomicBoolean(false)
   texture.setOnFrameAvailableListener({ frameReady.set(true) },Handler(thread.looper))
   decodeSurface=Surface(texture)
   decoder=MediaCodec.createDecoderByType(mime)
   decoder.configure(source,decodeSurface,null,0)
   muxer=MediaMuxer(output,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
   val audioCopier = audioUri?.let {
    AudioTrackCopier(context, it)
}

var muxAudioTrack = -1

val durationUs = if (
    source.containsKey(MediaFormat.KEY_DURATION)
) {
    source.getLong(MediaFormat.KEY_DURATION)
} else {
    0L
}
   extractor.selectTrack(track)
   encoder.start();decoder.start()
   var inputDone=false;var decodeDone=false;var encodeDone=false;var muxStarted=false;var muxTrack=-1
   var frames = 0
var lastPts = -1L
   val decInfo=MediaCodec.BufferInfo();val encInfo=MediaCodec.BufferInfo()
   while(!encodeDone) {
    if(!inputDone) {
     val index=decoder.dequeueInputBuffer(10_000)
     if(index>=0) { val buffer=decoder.getInputBuffer(index)!!;val size=extractor.readSampleData(buffer,0)
      if(size<0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true }
      else { decoder.queueInputBuffer(index,0,size,extractor.sampleTime,0);extractor.advance() }
     }
    }
    if(!decodeDone) {
     val index=decoder.dequeueOutputBuffer(decInfo,10_000)
     if(index>=0) {
      val render=decInfo.size>0
      decoder.releaseOutputBuffer(index,render)
      if(render) {
       val deadline=System.nanoTime()+3_000_000_000L
       while(!frameReady.getAndSet(false) && System.nanoTime()<deadline) Thread.sleep(2)
       require(System.nanoTime()<deadline) { "Timeout menunggu frame decoder" }
       texture.updateTexImage()
       val pts=decInfo.presentationTimeUs.coerceAtLeast(lastPts+1);lastPts=pts
       glPipe.render(texture,pts*1000)
       frames++
      if (frames % 15 == 0 && durationUs > 0L) {
    val percent = (
        pts * 95L / durationUs
    ).toInt().coerceIn(0, 95)

    progress("Memproses HDR10: $percent%", percent)
}
      }
      if(decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !=0) { decodeDone=true;encoder.signalEndOfInputStream() }
     }
    }
    while(true) {
     val index=encoder.dequeueOutputBuffer(encInfo,if(decodeDone) 10_000 else 0)
     
if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
    require(!muxStarted)

    muxTrack = muxer.addTrack(encoder.outputFormat)

    if (audioCopier != null) {
        muxAudioTrack = muxer.addTrack(
            audioCopier.format
        )
    }

    muxer.start()
    muxStarted = true
}
     else if(index>=0) {
      if(encInfo.size>0) { require(muxStarted); val buffer=encoder.getOutputBuffer(index)!!;buffer.position(encInfo.offset);buffer.limit(encInfo.offset+encInfo.size);muxer.writeSampleData(muxTrack,buffer,encInfo) }
      encodeDone=encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !=0
      encoder.releaseOutputBuffer(index,false)
     } else break
    }
   }
   require(frames>0 && muxStarted) { "Tiada output video" }
   
if (audioCopier != null && muxAudioTrack >= 0) {
    progress("Memasukkan audio...", 96)

    audioCopier.copyTo(
        muxer,
        muxAudioTrack,
        lastPts + 1L
    ) { message ->
        progress(message, 98)
    }
}

audioCopier?.close()
progress("HDR10 dan audio siap.", 99)

  } finally {
   runCatching { decoder?.stop() };runCatching { encoder?.stop() }
   runCatching { decoder?.release() };runCatching { encoder?.release() }
   runCatching { muxer?.stop() };runCatching { muxer?.release() }
   runCatching { decodeSurface?.release() };runCatching { texture?.release() };runCatching { gl?.release() }
   extractor.release();thread.quitSafely()
  }
 }
 private fun staticHdrInfo():ByteBuffer {
  // CTA-861.3 type 1 descriptor, D65 / BT.2020 mastering primaries, 1000 nit maximum, 0.005 nit minimum.
  val b=ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
  b.put(0); listOf(35400,14600,8500,39850,6550,2300,15635,16450).forEach { b.putShort(it.toShort()) }
  b.putShort(1000);b.putShort(1);b.putShort(1000);b.putShort(400)
  b.flip();return b
 }
}

private class GlPipe(private val surface:Surface,private val width:Int,private val height:Int) {
 private val display:EGLDisplay=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
 private val context:EGLContext
 private val eglSurface:EGLSurface
 val textureId:Int
 private val program:Int

private val vertices: FloatBuffer =
    ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    // X, Y, U, V

                    -1f, -1f, 0f, 0f, // Bawah kiri
                     1f, -1f, 1f, 0f, // Bawah kanan

                    -1f,  1f, 0f, 1f, // Atas kiri
                     1f,  1f, 1f, 1f  // Atas kanan
                )
            )
            position(0)
        }
 init {
  require(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
  val configAttrs=intArrayOf(EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_RED_SIZE,10,EGL14.EGL_GREEN_SIZE,10,EGL14.EGL_BLUE_SIZE,10,EGL14.EGL_ALPHA_SIZE,2,EGL14.EGL_NONE)
  val configs=arrayOfNulls<EGLConfig>(1);val count=IntArray(1)
  require(EGL14.eglChooseConfig(display,configAttrs,0,configs,0,1,count,0) && count[0]>0) { "EGL RGB10_A2 surface tidak tersedia" }
  context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
  require(context!=EGL14.EGL_NO_CONTEXT)
  eglSurface=EGL14.eglCreateWindowSurface(display,configs[0],surface,intArrayOf(EGL14.EGL_NONE),0)
  require(eglSurface!=EGL14.EGL_NO_SURFACE) { "Tidak dapat mencipta EGL 10-bit encoder surface" }
  require(EGL14.eglMakeCurrent(display,eglSurface,eglSurface,context))
  val textures=IntArray(1);GLES20.glGenTextures(1,textures,0);textureId=textures[0]
  GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
  program=link(VERT,FRAG)
 }
 fun render(texture:SurfaceTexture,ptsNs:Long) {
  GLES20.glViewport(0,0,width,height);GLES20.glUseProgram(program)
  val transform=FloatArray(16);texture.getTransformMatrix(transform)
  GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uTexMatrix"),1,false,transform,0)
  GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
  GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uTexture"),0)
  vertices.position(0);val pos=GLES20.glGetAttribLocation(program,"aPosition")
  GLES20.glEnableVertexAttribArray(pos);GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,vertices)
  vertices.position(2);val uv=GLES20.glGetAttribLocation(program,"aTexCoord")
  GLES20.glEnableVertexAttribArray(uv);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices)
  GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
  require(GLES20.glGetError()==GLES20.GL_NO_ERROR) { "OpenGL draw gagal" }
  EGLExt.eglPresentationTimeANDROID(display,eglSurface,ptsNs)
  require(EGL14.eglSwapBuffers(display,eglSurface)) { "EGL swap gagal" }
 }
 fun release() { GLES20.glDeleteProgram(program);EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);EGL14.eglDestroySurface(display,eglSurface);EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);surface.release() }
 private fun shader(type:Int,code:String):Int { val id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,code);GLES20.glCompileShader(id);val ok=IntArray(1);GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);require(ok[0]!=0) { GLES20.glGetShaderInfoLog(id) };return id }
 private fun link(v:String,f:String):Int { val a=shader(GLES20.GL_VERTEX_SHADER,v);val b=shader(GLES20.GL_FRAGMENT_SHADER,f);val id=GLES20.glCreateProgram();GLES20.glAttachShader(id,a);GLES20.glAttachShader(id,b);GLES20.glLinkProgram(id);val ok=IntArray(1);GLES20.glGetProgramiv(id,GLES20.GL_LINK_STATUS,ok,0);require(ok[0]!=0) { GLES20.glGetProgramInfoLog(id) };GLES20.glDeleteShader(a);GLES20.glDeleteShader(b);return id }
 companion object {
  private const val VERT="attribute vec2 aPosition; attribute vec2 aTexCoord; uniform mat4 uTexMatrix; varying vec2 vUV; void main(){gl_Position=vec4(aPosition,0.0,1.0);vUV=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy;}"
  // SDR BT.709 EOTF -> inverse tone mapping -> BT.2020 linear matrix -> PQ ST2084.
  private const val FRAG="""#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTexture;
varying vec2 vUV;
vec3 decode709(vec3 c){return mix(c/4.5,pow((c+0.099)/1.099,vec3(1.0/0.45)),step(vec3(0.081),c));}
vec3 pq(vec3 l){vec3 y=pow(clamp(l/10000.0,0.0,1.0),vec3(0.1593017578125));return pow((0.8359375+18.8515625*y)/(1.0+18.6875*y),vec3(78.84375));}
void main(){vec3 s=decode709(texture2D(uTexture,vUV).rgb);
// SDR reference 100 nits; smooth expansion into 400-nit range (not recovered scene detail).
vec3 expanded=100.0*s+300.0*pow(s,vec3(2.2));
mat3 m=mat3(0.627404,0.069097,0.016391, 0.329283,0.919540,0.088013, 0.043313,0.011362,0.895595);
vec3 bt2020=max(m*expanded,vec3(0.0));gl_FragColor=vec4(pq(bt2020),1.0);}
"""
 }
}
