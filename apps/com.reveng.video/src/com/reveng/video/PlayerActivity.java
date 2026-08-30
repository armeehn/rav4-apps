package com.reveng.video;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import com.reveng.design.MediaCitizen;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.MediaController;
import android.widget.VideoView;

import java.util.ArrayList;
import com.reveng.design.Palette;

/**
 * Full-screen video player built on android.widget.VideoView + MediaController.
 * Tap-to-show transport controls (play/pause + seek bar). Fling/next handled by
 * MediaController's built-in prev/next when a playlist is supplied. Back returns
 * to the list.
 */
public class PlayerActivity extends Activity {

    private VideoView video;
    private MediaController controller;
    private final ArrayList<Uri> uris = new ArrayList<>();

    /**
     * v0.6.1 — video is media too: without audio focus its soundtrack plays over the radio and
     * does not duck for a navigation prompt.
     */
    private MediaCitizen citizen;
    private int index = 0;
    private int resumePos = 0;
    /** True only while a duck — not the driver — is what stopped playback. */
    private boolean pausedByDuck = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        // keep the screen on during playback + go immersive full-screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_player);
        goImmersive();

        video = findViewById(R.id.video);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        String[] arr = getIntent().getStringArrayExtra("uris");
        if (arr != null) {
            for (String s : arr) uris.add(Uri.parse(s));
            index = getIntent().getIntExtra("index", 0);
        } else if (getIntent().getData() != null) {
            uris.add(getIntent().getData());
        }
        if (uris.isEmpty()) { finish(); return; }
        if (index < 0 || index >= uris.size()) index = 0;

        controller = new MediaController(this) {
            @Override
            public boolean dispatchKeyEvent(android.view.KeyEvent event) {
                if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    finish();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        controller.setAnchorView(findViewById(R.id.root));

        // playlist prev/next wiring for MediaController's skip buttons
        controller.setPrevNextListeners(
                v -> playAt(index + 1),
                v -> playAt(index - 1));

        video.setMediaController(controller);
        video.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            if (resumePos > 0) { video.seekTo(resumePos); resumePos = 0; }
            video.start();
        publish();
            // show controls briefly on start
            controller.show(3000);
        });
        video.setOnCompletionListener(mp -> {
            if (index < uris.size() - 1) playAt(index + 1);
        });
        video.setOnErrorListener((MediaPlayer mp, int what, int extra) -> {
            // skip a broken file rather than dying
            if (index < uris.size() - 1) { playAt(index + 1); return true; }
            return false;
        });

        // tap anywhere toggles the controls
        video.setOnClickListener(v -> {
            if (controller.isShowing()) controller.hide();
            else controller.show(3000);
        });

        playAt(index);
    }

    private void playAt(int i) {
        if (i < 0 || i >= uris.size()) return;
        index = i;
        resumePos = 0;
        Uri playing = uris.get(i);
        if (!citizen().takeFocus(MediaCitizen.Focus.MEDIA)) {
            return;
        }
        citizen().setMetadata(playing.getLastPathSegment(), null, 0);
        video.setVideoURI(playing);
        video.requestFocus();
        video.start();
        publish();
    }

    private void goImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    private MediaCitizen citizen() {
        if (citizen == null) {
            citizen = MediaCitizen.attach(this, "video", new MediaCitizen.Transport() {
                @Override public void onPlay() { if (video != null) { video.start(); publish(); } }

                @Override public void onPause() { if (video != null) { video.pause(); publish(); } }

                @Override public void onNext() { }

                @Override public void onPrevious() { }

                @Override public void onStop() { if (video != null) { video.pause(); publish(); } }

                @Override public void onDuck(boolean duck) {
                    // VideoView exposes no volume control, so a duck request is honoured by
                    // pausing: a spoken direction the driver cannot hear is worse than a gap.
                    if (video == null) return;
                    if (duck) {
                        pausedByDuck = video.isPlaying();
                        video.pause();
                        return;
                    }
                    // Resume only what the duck itself stopped. MediaCitizen calls onDuck(false)
                    // on every focus gain, so an unconditional start() restarts a video the
                    // driver had paused by hand.
                    if (pausedByDuck) {
                        pausedByDuck = false;
                        video.start();
                    }
                }
            });
        }
        return citizen;
    }

    private void publish() {
        if (citizen == null || video == null) return;
        int pos = 0;
        try { pos = video.getCurrentPosition(); } catch (Exception ignored) {}
        citizen.setState(video.isPlaying(), pos);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) {
            resumePos = video.getCurrentPosition();
            video.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (video != null && resumePos > 0) {
            video.seekTo(resumePos);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (video != null) video.stopPlayback();
        if (citizen != null) {
            citizen.release();
            citizen = null;
        }
    }
}
