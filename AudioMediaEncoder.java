package com.uni.vr.encoder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import com.uni.pano.config.EnumElement;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by DELL on 2017/4/15.
 */

public class AudioMediaEncoder extends MediaEncoder {
    public static final String TAG = AudioMediaEncoder.class.getSimpleName();
    public static final int SAMPLE_RATE = 44100;	// 44.1[KHz] is only setting guaranteed to be available on all devices.
    public static final int BIT_RATE = 64000;
    public static final int SAMPLES_PER_FRAME = 1024;	// AAC, bytes/frame/channel
    public static final int FRAMES_PER_BUFFER = 30; 	// AAC, frame/buffer/sec
    private AudioThread mAudioThread = null;
    public AudioMediaEncoder(MediaMuxerWrapper mediaMuxerWrapper, MediaEncoderListener mediaEncoderListener){
        super(mediaMuxerWrapper, mediaEncoderListener);
    }
    public void startRecording(){
        super.startRecording();
        bufferInfo = new MediaCodec.BufferInfo();
        final MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            mediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mediaCodec.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (mAudioThread == null) {
            mAudioThread = new AudioThread();
            mAudioThread.start();
        }
    }
    public void stopRecording(){
        super.stopRecording();
    }
    @Override
    protected void release() {
        mAudioThread = null;
        super.release();
    }
    private final int[] AUDIO_SOURCES = new int[] {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
    };

    protected void encode(final ByteBuffer buffer, final int length, final long presentationTimeUs) {
        if (!mIsCapturing) return;
        if (length <= 0) return;
        int index = 0;
        int size = 0;
        while (index < length) {
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(0);
            if (inputBufferIndex >= 0) {
                final ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                size = inputBuffer.remaining();
                size = ((index + size) < length) ? size : (length - index);
                if ((size > 0) && (buffer != null)) {
                    inputBuffer.put(buffer);
                }
                index += size;
                if (length <= 0) {
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    index = length + 1;
                } else {
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeUs, 0);
                }
            }
        }
    }
    @Override
    public void handle(Message message){
        switch (message.msg){
            case AVAILABLE:{
                drainEncoder(false);
            }
            break;
            case STOP:{
                drainEncoder(false);
                release();
            }
            break;
            default:
                break;
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
        while (true) {
            int encoderStatus = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if(!endOfStream) {
                    break;
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mediaCodec.getOutputFormat();
                mTrackIndex = mediaMuxerWrapper.addTrack(newFormat);
                mediaMuxerWrapper.start();
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
                    mediaMuxerWrapper.writeSampleData(mTrackIndex, encoderOutputBuffer, bufferInfo, 0);
                    prevOutputPTSUs = bufferInfo.presentationTimeUs;
                }
                mediaCodec.releaseOutputBuffer(encoderStatus, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }
    private class AudioThread extends Thread {
        @Override
        public void run() {
            final int min_buffer_size = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buffer_size = SAMPLES_PER_FRAME * FRAMES_PER_BUFFER;
            if (buffer_size < min_buffer_size) {
                buffer_size = ((min_buffer_size / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;
            }
            AudioRecord audioRecord = null;
            for (final int source : AUDIO_SOURCES) {
                try {
                    audioRecord = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer_size);
                    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        audioRecord = null;
                    }
                } catch (final Exception e) {
                    audioRecord = null;
                }
                if (audioRecord != null) {
                    break;
                }
            }
            if (audioRecord != null) {
                try {
                    if (mIsCapturing) {
                        final ByteBuffer buf = ByteBuffer.allocateDirect(SAMPLES_PER_FRAME);
                        int readBytes;
                        audioRecord.startRecording();
                        try {
                            for (; mIsCapturing&&!mRequestStop ;) {
                                buf.clear();
                                readBytes = audioRecord.read(buf, SAMPLES_PER_FRAME);
                                if (readBytes > 0) {
                                    buf.position(readBytes);
                                    buf.flip();
                                    encode(buf, readBytes, getPTSUs());
                                    frameAvailableSoon();
                                }
                            }
                            Message message = new Message();
                            message.msg = EnumElement.ENCODE_MESSAGE.STOP;
                            addMsg(message, true);
                        } finally {
                            audioRecord.stop();
                        }
                    }
                } finally {
                    audioRecord.release();
                }
            }
        }
    }
}
