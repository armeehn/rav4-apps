package com.ripostelabs.design;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebView;

import java.util.List;

/**
 * v0.6.4 — audio citizenship for a {@link WebView}.
 *
 * <h3>Why a WebView needs its own helper</h3>
 *
 * A page's soundtrack is media like any other, but a WebView announces nothing: there is no
 * "started playing" callback to hang {@link MediaCitizen#takeFocus} on, and the app cannot see
 * inside the page. What the platform does hand an app is a view of its <em>own</em> playback —
 * an app without MODIFY_AUDIO_ROUTING is shown only its own configurations — so a non-empty
 * list means this WebView is the thing making noise. Focus is taken at that moment and given
 * back when it stops.
 *
 * <p>Two apps in the suite put arbitrary web pages on screen (Browser, News reader) and both
 * need exactly this, so it lives here rather than being written twice.
 *
 * <h3>What it does not do</h3>
 *
 * On API &lt; 26 the playback signal does not exist. There, autoplay being gated on a user
 * gesture (the framework default, which {@link #attach} enforces) plus suspending the page in
 * {@link #onPause} is the whole protection.
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * audio = WebAudio.attach(web, "browser");   // end of onCreate
 * audio.onPause();                           // from Activity.onPause
 * audio.onResume();                          // from Activity.onResume
 * audio.release();                           // from onDestroy; destroys the WebView too
 * </pre>
 */
public final class WebAudio {

    private final WebView web;
    private final Context appContext;
    private final MediaCitizen citizen;

    private AudioManager.AudioPlaybackCallback playbackWatch;
    private boolean audible;

    private WebAudio(WebView web, String tag) {
        this.web = web;
        this.appContext = web.getContext().getApplicationContext();
        this.citizen = MediaCitizen.attach(appContext, tag, new MediaCitizen.Transport() {
            @Override public void onPlay() { web.onResume(); }

            @Override public void onPause() { web.onPause(); }

            @Override public void onNext() { }

            @Override public void onPrevious() { }

            @Override public void onStop() { web.onPause(); }

            @Override public void onDuck(boolean duck) {
                // A WebView exposes no volume control, so a prompt is honoured by suspending
                // the page: a spoken direction the driver cannot hear is worse than a gap.
                if (duck) { web.onPause(); } else { web.onResume(); }
            }
        });
    }

    /**
     * Start watching {@code web}. Autoplay is turned off as part of attaching: a page that
     * starts talking the moment it loads takes the cabin from the radio without the driver
     * having asked for anything.
     */
    public static WebAudio attach(WebView web, String tag) {
        WebAudio a = new WebAudio(web, tag);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        a.watchPlayback();
        return a;
    }

    private void watchPlayback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        AudioManager am = appContext.getSystemService(AudioManager.class);
        if (am == null) {
            return;
        }
        playbackWatch = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                onAudible(!configs.isEmpty());
            }
        };
        am.registerAudioPlaybackCallback(playbackWatch, new Handler(Looper.getMainLooper()));
    }

    /** Claim the cabin when the page starts making noise; hand it back when it stops. */
    private void onAudible(boolean nowAudible) {
        if (nowAudible == audible) {
            return;
        }
        audible = nowAudible;

        if (!nowAudible) {
            citizen.releaseFocus();
            citizen.setState(false, 0);
            return;
        }
        if (citizen.takeFocus(MediaCitizen.Focus.MEDIA)) {
            citizen.setMetadata(web.getTitle(), web.getUrl(), 0);
            citizen.setState(true, 0);
            return;
        }
        // Refused means something else owns the cabin, usually a call. Suspend the page
        // rather than talk over it.
        audible = false;
        web.onPause();
    }

    /**
     * Suspends the page's media and its timers. Leaving the screen must not leave a video
     * playing over the radio from a page nobody is looking at.
     */
    public void onPause() {
        web.onPause();
        web.pauseTimers();
    }

    public void onResume() {
        web.resumeTimers();
        web.onResume();
    }

    /** Stops watching, gives the cabin back, and destroys the WebView. */
    public void release() {
        if (playbackWatch != null) {
            AudioManager am = appContext.getSystemService(AudioManager.class);
            if (am != null) {
                am.unregisterAudioPlaybackCallback(playbackWatch);
            }
            playbackWatch = null;
        }
        citizen.release();

        // A WebView still attached to the tree cannot be destroyed, and one that is never
        // destroyed keeps its renderer — and the activity — alive.
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) {
            parent.removeView(web);
        }
        web.destroy();
    }
}
