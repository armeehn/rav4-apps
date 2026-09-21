package com.ripostelabs.projection;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.ripostelabs.projection.aa.Ids;
import com.ripostelabs.projection.aa.Messages;

/**
 * One PCM sink per Android Auto audio channel: media (48 kHz stereo), speech and system
 * (16 kHz mono). Writes are non-blocking so a full track can never stall the USB reader that
 * also carries the video; what does not fit is counted and dropped.
 *
 * <p>Focus is not taken here. {@link Projector} holds the one {@code MediaCitizen} for the app
 * and takes focus when the phone asks for it, which is how the radio gets ducked properly.
 */
final class AudioSink {

    private static final String TAG = "Projection";
    private static final int BUFFER_MULTIPLIER = 4;

    private final int channel;
    private AudioTrack track;
    private int dropped;
    private int written;
    private static final int REPORT_EVERY = 100;

    AudioSink(int channel) {
        this.channel = channel;
    }

    synchronized void start(Messages.AudioConfig config) {
        stop();
        int channelMask = config.channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int min = AudioTrack.getMinBufferSize(config.sampleRate, channelMask, encoding);
        if (min <= 0) {
            Log.w(TAG, "audio " + channel + ": unsupported config " + config.sampleRate + "/" + config.channels);
            return;
        }

        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(config.sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(min * BUFFER_MULTIPLIER)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            Log.e(TAG, "audio " + channel + ": track failed: " + e);
            track = null;
            return;
        }
        Log.i(TAG, "audio " + channel + ": playing " + config.sampleRate + " Hz x" + config.channels);
    }

    synchronized void write(byte[] data, int off, int len) {
        if (track == null) {
            return;
        }
        int n = track.write(data, off, len, AudioTrack.WRITE_NON_BLOCKING);
        if (n < len) {
            dropped += len - Math.max(n, 0);
        }
        // The video counter's twin: says whether the phone is sending sound at all.
        if (++written % REPORT_EVERY == 0) {
            Log.i(TAG, "audio " + channel + ": written " + written + " dropped " + dropped + " bytes");
        }
    }

    synchronized void setVolume(float v) {
        if (track != null) {
            track.setVolume(v);
        }
    }

    synchronized void stop() {
        if (track == null) {
            return;
        }
        try {
            track.stop();
        } catch (IllegalStateException ignored) {
            // Never started; release is what matters.
        }
        track.release();
        track = null;
    }

    int dropped() {
        return dropped;
    }

    /** Media is music; the other two are prompts, which the car mixes differently. */
    private AudioAttributes attributes() {
        AudioAttributes.Builder b = new AudioAttributes.Builder();
        switch (channel) {
            case Ids.CH_SPEECH_AUDIO:
                b.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
                break;
            case Ids.CH_SYSTEM_AUDIO:
                b.setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
                break;
            default:
                b.setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        }
        return b.build();
    }
}
