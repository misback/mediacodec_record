package com.uni.vr.encoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import com.uni.pano.config.EnumElement;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Created by DELL on 2017/4/14.
 */

public abstract class MediaEncoder implements Runnable {
    private Semaphore semaphoreDataAvailable = new Semaphore(0);  //创建一个信号量
    private Semaphore semaphoreWriteProtect = new Semaphore(1);  //创建一个信号量
    protected MediaCodec mediaCodec;
    protected MediaCodec.BufferInfo bufferInfo;
    protected final WeakReference<MediaMuxerWrapper> mediaMuxerWrapperWeakReference;
    protected int mTrackIndex;
    protected volatile boolean mIsCapturing = false;
    protected volatile boolean mRequestStop;
    protected boolean mIsEOS;
    protected boolean mMuxerStarted;
    ExecutorService singleThreadExecutor= Executors.newSingleThreadExecutor();
    public static class Message{
        public EnumElement.ENCODE_MESSAGE msg;
        public String filePath = "";
    };
    public interface MediaEncoderListener {
        public void onStartRecording(MediaEncoder encoder);
        public void onStopRecording(MediaEncoder encoder);
    }
    MediaEncoderListener mediaEncoderListener;
    private List<Message> messageArrayList = new LinkedList<>();
    public void addMsg(Message message, boolean flush) {
        try {
            semaphoreWriteProtect.acquire();
            if (flush){
                messageArrayList.clear();
            }
            messageArrayList.add(message);
        }catch (InterruptedException e){
        }finally {
            semaphoreWriteProtect.release();
            semaphoreDataAvailable.release();
        }
    }
    @Override
    public void run() {
        while(true) {
            try {
                semaphoreDataAvailable.acquire();
                semaphoreWriteProtect.acquire();
                if (messageArrayList.isEmpty()){
                    continue;
                }
                Message message = messageArrayList.remove(0);
                if (message.msg == EnumElement.ENCODE_MESSAGE.QUIT){
                    release();
                    break;
                }else{
                    handle(message);
                }
            }catch (InterruptedException e){

            }finally {
                semaphoreWriteProtect.release();
            }
        }
    }


    public void startRecording(){
        mTrackIndex = -1;
        mMuxerStarted = mIsEOS = false;
        mIsCapturing = true;
        mRequestStop = false;
    }
    public void stopRecording(){
        //mIsCapturing = false;
        mRequestStop = true;
    }

    protected void release() {
        singleThreadExecutor.shutdown();
        mIsCapturing = false;
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
        MediaMuxerWrapper mediaMuxerWrapper = mediaMuxerWrapperWeakReference != null ? mediaMuxerWrapperWeakReference.get() : null;
        if (mediaMuxerWrapper != null) {
            mediaMuxerWrapper.stop();
        }
        bufferInfo = null;
        if (mediaEncoderListener!=null){
            mediaEncoderListener.onStopRecording(this);
            mediaEncoderListener = null;
        }
    }

    public MediaEncoder(MediaMuxerWrapper mediaMuxerWrapper, MediaEncoderListener mediaEncoderListener) {
        mediaMuxerWrapperWeakReference = new WeakReference<MediaMuxerWrapper>(mediaMuxerWrapper);
        singleThreadExecutor.execute(this);
        this.mediaEncoderListener = mediaEncoderListener;
    }
    public void frameAvailableSoon(){
        if (mIsCapturing || !mRequestStop) {
            Message message = new Message();
            message.msg = EnumElement.ENCODE_MESSAGE.AVAILABLE;
            addMsg(message, false);
        }
    }
    public void handle(Message message){
        switch (message.msg){
            case AVAILABLE:{
                drainEncoder(false);
            }
            break;
            case STOP:{
                drainEncoder(true);
                release();
            }
            break;
            default:
                break;
        }
    }
    public abstract void drainEncoder(boolean endOfStream);

    protected long prevOutputPTSUs = 0;
    protected long getPTSUs() {
        long result = System.nanoTime() / 1000L;
        if (result < prevOutputPTSUs)
            result = (prevOutputPTSUs - result) + result;
        return result;
    }
}
