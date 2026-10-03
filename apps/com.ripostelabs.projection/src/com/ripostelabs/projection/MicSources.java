package com.ripostelabs.projection;

import android.media.MediaRecorder;

import com.ripostelabs.projection.ns.Pickup;

/** The Android capture source behind each {@link Pickup}: one table for calls and the mic source test. */
final class MicSources {

    private MicSources() {
    }

    static int audioSource(Pickup pickup) {
        switch (pickup) {
            case RECOGNITION:
                return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case UNPROCESSED:
                return MediaRecorder.AudioSource.UNPROCESSED;
            case PLATFORM:
                return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            default:
                return MediaRecorder.AudioSource.MIC;
        }
    }
}
