package com.ripostelabs.design;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;

/**
 * v0.6.1–0.6.3 — makes an app a proper citizen of the car's audio system.
 *
 * <h3>Why this exists</h3>
 *
 * Three things were missing from every app in the suite except the radio, and all three are
 * invisible on a desk and obvious in a car:
 *
 * <ol>
 *   <li><b>Audio focus.</b> Without it our Music app plays straight over the radio, and does not
 *       duck or pause when navigation speaks. Audio focus is the only mechanism the platform has
 *       for "one thing at a time"; an app that never asks simply fights everything else.</li>
 *   <li><b>A MediaSession.</b> The launcher's now-playing card reads active media sessions
 *       (its {@code NowPlayingRepository} calls {@code MediaSessionManager.getActiveSessions}).
 *       An app with no session is <em>invisible to our own launcher</em> — the card stays empty
 *       while our own Music app is playing.</li>
 *   <li><b>Media buttons.</b> The steering-wheel play/pause is delivered to whichever app owns
 *       the active session. No session, no wheel controls.</li>
 * </ol>
 *
 * All three are the same object in practice, so they live behind one helper rather than being
 * re-derived per app — the radio's hand-rolled focus handling is what this generalises.
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * citizen = MediaCitizen.attach(this, "music", transport);
 * if (citizen.takeFocus()) { player.start(); }
 * citizen.setMetadata(title, artist, durationMs);
 * citizen.setState(true, player.getCurrentPosition());
 * ...
 * citizen.release();   // in onDestroy
 * </pre>
 */
public final class MediaCitizen {

    /** What the host app can do when the system, or the wheel, asks. */
    public interface Transport {
        void onPlay();

        void onPause();

        void onNext();

        void onPrevious();

        void onStop();

        /**
         * Lower the volume for a short interruption (a navigation prompt) and restore it after.
         * Ducking rather than pausing is what keeps a spoken direction from stopping the music.
         */
        void onDuck(boolean duck);
    }

    /** What the app wants the cabin's audio for. */
    public enum Focus {
        /** Playback that should share nicely: it ducks for prompts and yields to a call. */
        MEDIA,
        /**
         * Capture. Asks everything else to go quiet for the duration, because whatever is
         * playing would otherwise be recorded through the cabin microphone.
         */
        RECORDING,
    }

    /** Volume while ducked, as a fraction of normal. */
    private static final float DUCK_VOLUME = 0.2f;

    private final Context appContext;
    private final Transport transport;
    private final MediaSession session;

    private AudioFocusRequest focusRequest;
    private boolean pausedByFocusLoss;

    /**
     * One instance for the life of this citizen. Pre-O, {@code abandonAudioFocus} matches the
     * listener by identity, and every evaluation of {@code this::onFocusChange} is a *new*
     * object — so requesting with one and abandoning with another leaves the focus stack
     * entry in place forever, and everything else in the cabin stays ducked.
     */
    private final AudioManager.OnAudioFocusChangeListener focusListener = this::onFocusChange;

    private MediaCitizen(Context context, String tag, Transport transport) {
        this.appContext = context.getApplicationContext();
        this.transport = transport;
        this.session = new MediaSession(appContext, tag);

        this.session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                transport.onPlay();
            }

            @Override
            public void onPause() {
                transport.onPause();
            }

            @Override
            public void onSkipToNext() {
                transport.onNext();
            }

            @Override
            public void onSkipToPrevious() {
                transport.onPrevious();
            }

            @Override
            public void onStop() {
                transport.onStop();
            }
        });

        // Media buttons reach a session only through a PendingIntent, even for an app that is
        // in the foreground; without this the steering wheel does nothing.
        Intent button = new Intent(Intent.ACTION_MEDIA_BUTTON);
        button.setPackage(appContext.getPackageName());
        this.session.setMediaButtonReceiver(PendingIntent.getBroadcast(
                appContext, 0, button,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    public static MediaCitizen attach(Context context, String tag, Transport transport) {
        return new MediaCitizen(context, tag, transport);
    }

    /**
     * Ask for audio focus. Returns false if the system refused, in which case the caller must
     * NOT start playing — a refusal usually means a call is in progress.
     */
    public boolean takeFocus(Focus kind) {
        AudioManager am = appContext.getSystemService(AudioManager.class);
        if (am == null) {
            return false;
        }

        boolean recording = kind == Focus.RECORDING;

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(recording
                        ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                        : AudioAttributes.USAGE_MEDIA)
                .setContentType(recording
                        ? AudioAttributes.CONTENT_TYPE_SPEECH
                        : AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        // EXCLUSIVE tells the system nobody should duck-and-continue: a ducked radio is still
        // audible, and still ends up on the recording.
        int gain = recording
                ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                : AudioManager.AUDIOFOCUS_GAIN;

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(gain)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            result = am.requestAudioFocus(focusRequest);
        } else {
            result = am.requestAudioFocus(focusListener,
                    recording ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC, gain);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** Give focus back. Always call this when playback stops for good. */
    public void releaseFocus() {
        AudioManager am = appContext.getSystemService(AudioManager.class);
        if (am == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) {
                am.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            }
        } else {
            am.abandonAudioFocus(focusListener);
        }
    }

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Someone else owns the cabin now. Give up rather than wait to resume.
                pausedByFocusLoss = false;
                transport.onPause();
                releaseFocus();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                pausedByFocusLoss = true;
                transport.onPause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                transport.onDuck(true);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                transport.onDuck(false);
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false;
                    transport.onPlay();
                }
                break;
            default:
                break;
        }
    }

    /** The volume a ducked player should use; restore to 1f when the duck ends. */
    public static float duckVolume(boolean ducked) {
        return ducked ? DUCK_VOLUME : 1f;
    }

    /** What the launcher's now-playing card shows. Nulls are tolerated. */
    public void setMetadata(String title, String artist, long durationMs) {
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title == null ? "" : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist == null ? "" : artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, durationMs))
                .build());
    }

    /**
     * Publish transport state. The session stays {@code active} across a pause and is only torn
     * down in {@link #release}: a session deactivated on pause is no longer an *active* session,
     * so the wheel's play button has nothing to resume it with and the launcher's now-playing
     * card empties the moment the driver pauses. Pause is expressed in the {@link PlaybackState}
     * instead, which is what a card reads to draw a play glyph rather than a pause one.
     */
    public void setState(boolean playing, long positionMs) {
        session.setActive(true);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        Math.max(0, positionMs), playing ? 1f : 0f)
                .build());
    }

    /**
     * v0.8 — take this app off the launcher's card without tearing the session down. A
     * deactivated session vanishes from {@code getActiveSessions}; the next {@link #setState}
     * re-activates it. For when the source this session fronts stops being ours at all — the
     * vendor MCU handed the tuner's audio path to another source, say — where a mere
     * {@code setState(false, …)} would keep showing a pause card that lies.
     */
    public void setIdle() {
        session.setActive(false);
    }

    public void release() {
        releaseFocus();
        session.setActive(false);
        session.release();
    }
}
