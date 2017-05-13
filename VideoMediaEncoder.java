package com.uni.vr.encoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

import com.uni.pano.config.EnumElement;

import java.nio.ByteBuffer;


/**
 * Created by DELL on 2017/4/14.
 */

public class VideoMediaEncoder extends MediaEncoder {
    public static final String TAG = VideoMediaEncoder.class.getSimpleName();
    private static final int FRAME_RATE = 30;               // 30fps
    public static final int IFRAME_INTERVAL = 1;           // 5 seconds between I-frames
    public static final int FILTER_FRAME = FRAME_RATE*IFRAME_INTERVAL;
    private volatile long encodeNumber = 0;
    private int videoWidth;
    private int videoHeight;
    private int bitRate;
    private EGLDisplay mRenderEglDisplay;
    private EGLContext mRenderEglContext;
    private EGLSurface mRenderSurface;
    private EGLDisplay mEncoderEglDisplay;
    private EGLContext mEncoderEglContext;
    private EGLSurface mEncoderSurface;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    public VideoMediaEncoder(MediaMuxerWrapper mediaMuxerWrapper, MediaEncoderListener mediaEncoderListener, int videoWidth, int videoHeight, int BIT_RATE){
        super(mediaMuxerWrapper, mediaEncoderListener);
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.bitRate = BIT_RATE;
        mRenderEglContext = EGL14.eglGetCurrentContext();
        mRenderEglDisplay = EGL14.eglGetCurrentDisplay();
        mRenderSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
    }
    public void startRecording(){
        super.startRecording();
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
            bufferInfo = new MediaCodec.BufferInfo();
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface sf = mediaCodec.createInputSurface();
            mEncoderSurface = createWindowSurface(sf);
            mediaCodec.start();
            encodeNumber = 0;
            mediaEncoderListener.onStartRecording(this);
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    public void stopRecording(){
        super.stopRecording();
        Message message = new Message();
        message.msg = EnumElement.ENCODE_MESSAGE.STOP;
        addMsg(message, true);
    }
    @Override
    public void release() {
        super.release();
        if(mEncoderSurface != null) {
            EGL14.eglDestroySurface(mEncoderEglDisplay, mEncoderSurface);
            mEncoderSurface =   null;
        }
    }
    public void drainEncoder(boolean endOfStream, boolean bH264) {
        if (mediaCodec == null || mediaMuxerWrapperWeakReference == null ) {
            return;
        }
        final MediaMuxerWrapper mediaMuxerWrapper = mediaMuxerWrapperWeakReference.get();
        if (mediaMuxerWrapper == null) {
            return;
        }
        if (endOfStream) {
            mediaCodec.signalEndOfInputStream();
        }
        while (true) {
            int encoderStatus = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if(!endOfStream) {
                    break;
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mediaCodec.getOutputFormat();
                mTrackIndex = mediaMuxerWrapper.addTrack(newFormat);
                mMuxerStarted = true;
            } else {
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size != 0 && mMuxerStarted) {
                    ByteBuffer encoderOutputBuffer = mediaCodec.getOutputBuffer(encoderStatus);
                    encoderOutputBuffer.position(bufferInfo.offset);
                    encoderOutputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    bufferInfo.presentationTimeUs = getPTSUs();
                    encodeNumber++;
                    mediaMuxerWrapper.writeSampleData(mTrackIndex, encoderOutputBuffer, bufferInfo, encodeNumber);
                    prevOutputPTSUs = bufferInfo.presentationTimeUs;
                }
                mediaCodec.releaseOutputBuffer(encoderStatus, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }
    public void drainEncoder(boolean endOfStream) {
        if (mediaCodec == null || mediaMuxerWrapperWeakReference == null ) {
            return;
        }
        final MediaMuxerWrapper mediaMuxerWrapper = mediaMuxerWrapperWeakReference.get();
        if (mediaMuxerWrapper == null) {
            return;
        }
        if (endOfStream) {
            mediaCodec.signalEndOfInputStream();
        }
        while (true) {
            int encoderStatus = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if(!endOfStream) {
                    break;
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mediaCodec.getOutputFormat();
                mTrackIndex = mediaMuxerWrapper.addTrack(newFormat);
                mMuxerStarted = true;
            } else {
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size != 0 && mMuxerStarted) {
                    ByteBuffer encoderOutputBuffer = mediaCodec.getOutputBuffer(encoderStatus);
                    encoderOutputBuffer.position(bufferInfo.offset);
                    encoderOutputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    bufferInfo.presentationTimeUs = getPTSUs();
                    encodeNumber++;
                    mediaMuxerWrapper.writeSampleData(mTrackIndex, encoderOutputBuffer, bufferInfo, encodeNumber);
                    prevOutputPTSUs = bufferInfo.presentationTimeUs;
                }
                mediaCodec.releaseOutputBuffer(encoderStatus, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }
    public EGLSurface createWindowSurface(Surface surface) {
        mEncoderEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEncoderEglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEncoderEglDisplay, version, 0, version, 1)) {
            mEncoderEglDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEncoderEglDisplay, attribList, 0, configs, 0, configs.length,
                numConfigs, 0)) {
            throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");
        }

        int[] attrib_list = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
       // Log.i("KKK", "SHARE CONTEXT: " + EGL14.eglGetCurrentContext().getHandle());
        mEncoderEglContext = EGL14.eglCreateContext(mEncoderEglDisplay, configs[0], EGL14.eglGetCurrentContext(),
                attrib_list, 0);
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };
        return EGL14.eglCreateWindowSurface(mEncoderEglDisplay, configs[0], surface, surfaceAttribs, 0);
    }

    public void makeWindowSurfaceCurrent() {
        EGL14.eglMakeCurrent(mRenderEglDisplay, mRenderSurface, mRenderSurface, mRenderEglContext);
    }

    public  void makeEncoderSurfaceCurrent() {
        EGL14.eglMakeCurrent(mEncoderEglDisplay,mEncoderSurface, mEncoderSurface, mEncoderEglContext);
    }

    public void swapEncoderSurfaceBuffer() {
        if (EGL14.eglSwapBuffers(mEncoderEglDisplay, mEncoderSurface)) {
        }
    }

}
