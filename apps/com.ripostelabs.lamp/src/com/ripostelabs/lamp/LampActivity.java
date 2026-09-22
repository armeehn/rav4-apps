package com.ripostelabs.lamp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import com.ripostelabs.design.Palette;
import com.ripostelabs.design.PermissionGate;

/**
 * Panel-side remote for a "Magic Lantern" BLE LED lamp. Three columns on the 1920x720 screen:
 * lamp picker, power/colour/brightness, effects/speed/music.
 *
 * Talks only to {@link LampLink}; the protocol bytes live in {@link Elk}. Every control writes
 * through as it moves, so the lamp follows the finger. The last lamp and the last settings are
 * kept so the app opens where it was left, and reconnects on its own.
 *
 * Pure {@code android.*}: no AndroidX, no external libraries.
 */
public class LampActivity extends Activity implements LampLink.Listener {

    private static final String PREFS = "lamp_prefs";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_NAME = "name";
    private static final String KEY_POWER = "power";
    private static final String KEY_COLOUR = "colour";
    private static final String KEY_BRIGHTNESS = "brightness";
    private static final String KEY_EFFECT = "effect";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_MUSIC = "music";
    private static final String KEY_GAIN = "gain";

    /** SCAN + CONNECT on 31+, asked through the suite's one gate; nothing below that. */
    private PermissionGate gate;
    private static final int REQ_ENABLE = 2;
    private static final int MAX_LISTED = 5;
    private static final int DEFAULT_COLOUR = 0xFF8C00;
    private static final int DEFAULT_PERCENT = 80;
    private static final int DEFAULT_SPEED = 50;

    private static final int[] SWATCHES = {
        0xFF0000, 0xFF8C00, 0xFFE000, 0x00FF40, 0x00E0FF, 0x2040FF, 0xC000FF, 0xFFFFFF,
    };

    // What each swatch announces: a bare coloured View is "button" and nothing else.
    private static final int[] SWATCH_NAMES = {
        R.string.colour_red, R.string.colour_orange, R.string.colour_yellow, R.string.colour_green,
        R.string.colour_cyan, R.string.colour_blue, R.string.colour_purple, R.string.colour_white,
    };

    private LampLink link;
    private SharedPreferences prefs;

    private TextView statusView, statusDetail, scanBtn, powerBtn, effectName;
    private TextView brightnessValue, speedValue, gainValue;
    private LinearLayout deviceList;
    private View controls, effectsPanel;
    private HueBar hue;
    private SeekBar brightness, speed, gain;
    private Switch music;

    private LampLink.State state = LampLink.State.IDLE;
    private boolean powerOn;
    private int colour, effect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        statusDetail = findViewById(R.id.statusDetail);
        scanBtn = findViewById(R.id.scanBtn);
        deviceList = findViewById(R.id.deviceList);
        controls = findViewById(R.id.controls);
        effectsPanel = findViewById(R.id.effectsPanel);
        powerBtn = findViewById(R.id.powerBtn);
        hue = findViewById(R.id.hue);
        brightness = findViewById(R.id.brightness);
        brightnessValue = findViewById(R.id.brightnessValue);
        effectName = findViewById(R.id.effectName);
        speed = findViewById(R.id.speed);
        speedValue = findViewById(R.id.speedValue);
        music = findViewById(R.id.music);
        gain = findViewById(R.id.gain);
        gainValue = findViewById(R.id.gainValue);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        link = new LampLink(this, this);

        restore();
        wire();
        buildSwatches();
        onState(link.state(), null);

        if (!requestPermissions()) {
            return;
        }
        reconnect();
    }

    @Override
    protected void onDestroy() {
        link.close();
        super.onDestroy();
    }

    // ---- setup -------------------------------------------------------------------------

    private void restore() {
        powerOn = prefs.getBoolean(KEY_POWER, false);
        colour = prefs.getInt(KEY_COLOUR, DEFAULT_COLOUR);
        effect = prefs.getInt(KEY_EFFECT, Elk.AUTO_EFFECT);
        hue.setColour(colour);
        brightness.setProgress(prefs.getInt(KEY_BRIGHTNESS, DEFAULT_PERCENT));
        speed.setProgress(prefs.getInt(KEY_SPEED, DEFAULT_SPEED));
        gain.setProgress(prefs.getInt(KEY_GAIN, DEFAULT_SPEED));
        music.setChecked(prefs.getBoolean(KEY_MUSIC, false));
        renderPower();
        renderEffect();
        renderPercent(brightnessValue, brightness.getProgress());
        renderPercent(speedValue, speed.getProgress());
        renderPercent(gainValue, gain.getProgress());
    }

    private void wire() {
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onScanTapped(); }
        });
        scanBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { forget(); return true; }
        });
        powerBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setPower(!powerOn); }
        });
        hue.setListener(new HueBar.OnHueListener() {
            @Override public void onHue(int rgb, boolean fromUser) { setColour(rgb); }
        });
        findViewById(R.id.effectPrev).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setEffect(effect - 1); }
        });
        findViewById(R.id.effectNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setEffect(effect + 1); }
        });
        music.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean(KEY_MUSIC, on).apply();
                link.send(Elk.mic(on ? Elk.Switch.ON : Elk.Switch.OFF));
            }
        });

        brightness.setOnSeekBarChangeListener(new PercentListener(brightnessValue, KEY_BRIGHTNESS) {
            @Override byte[] frame(int pct) { return Elk.brightness(pct); }
        });
        speed.setOnSeekBarChangeListener(new PercentListener(speedValue, KEY_SPEED) {
            @Override byte[] frame(int pct) { return Elk.speed(pct); }
        });
        gain.setOnSeekBarChangeListener(new PercentListener(gainValue, KEY_GAIN) {
            @Override byte[] frame(int pct) { return Elk.micGain(pct); }
        });
    }

    /** Eight fixed colours beside the hue strip: quick picks that need no aim. */
    private void buildSwatches() {
        float density = getResources().getDisplayMetrics().density;
        int gap = (int) (8 * density);
        for (int i = 0; i < SWATCHES.length; i++) {
            final int c = SWATCHES[i];
            View v = new View(this);
            v.setContentDescription(getString(SWATCH_NAMES[i]));
            GradientDrawable d = new GradientDrawable();
            d.setColor(0xFF000000 | c);
            d.setCornerRadius(14 * density * Palette.cornerScale(this));
            v.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMarginEnd(gap);
            v.setLayoutParams(lp);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { hue.setColour(c); setColour(c); }
            });
            swatchesRow().addView(v);
        }
    }

    private LinearLayout swatchesRow() {
        return findViewById(R.id.swatches);
    }

    // ---- permissions / adapter ------------------------------------------------------------

    /** API 31+ needs SCAN and CONNECT at runtime. Returns true when nothing is outstanding. */
    private boolean requestPermissions() {
        if (gate().granted()) {
            return true;
        }
        gate().request();
        return false;
    }

    /** Built on first use: the status view it reports into exists only after setContentView. */
    private PermissionGate gate() {
        if (gate == null) {
            String[] wanted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                    : new String[0];
            gate = PermissionGate.of(this, wanted, null, new PermissionGate.Listener() {
                @Override public void onGranted() { reconnect(); }
                @Override public void onDenied() { statusDetail.setText(R.string.perm_needed); }
            });
        }
        return gate;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (!gate().onResult(req, perms, results)) {
            super.onRequestPermissionsResult(req, perms, results);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_ENABLE) {
            onState(link.state(), null);
        }
    }

    // ---- lamp picker -------------------------------------------------------------------

    private void onScanTapped() {
        switch (state) {
            case NO_BLUETOOTH:
                return;
            case BLUETOOTH_OFF:
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
                return;
            case SCANNING:
                link.stopScan();
                return;
            default:
                break;
        }
        if (!requestPermissions()) {
            return;
        }
        deviceList.removeAllViews();
        link.disconnect();
        link.scan();
    }

    /** Connect to the remembered lamp, if any. Silent when there is none. */
    private void reconnect() {
        String address = prefs.getString(KEY_ADDRESS, null);
        if (address == null || state == LampLink.State.BLUETOOTH_OFF) {
            return;
        }
        statusDetail.setText(prefs.getString(KEY_NAME, address));
        link.connect(address);
    }

    private void forget() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply();
        link.disconnect();
        deviceList.removeAllViews();
        onState(link.state(), getString(R.string.forget));
    }

    @Override
    public void onFound(final BluetoothDevice device, final String name) {
        if (deviceList.getChildCount() >= MAX_LISTED) {
            return;
        }
        TextView row = new TextView(this);
        row.setText(name);
        row.setTextAppearance(R.style.Body);
        row.setTextColor(Palette.color(this, R.color.text));
        row.setBackgroundResource(R.drawable.bg_field);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (14 * density);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * density);
        row.setLayoutParams(lp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putString(KEY_ADDRESS, device.getAddress()).putString(KEY_NAME, name).apply();
                deviceList.removeAllViews();
                statusDetail.setText(name);
                link.connect(device.getAddress());
            }
        });
        deviceList.addView(row);
    }

    @Override
    public void onState(LampLink.State s, String detail) {
        state = s;
        int label;
        int btn = R.string.scan;
        switch (s) {
            case NO_BLUETOOTH: label = R.string.no_bluetooth; break;
            case BLUETOOTH_OFF: label = R.string.bt_off; btn = R.string.turn_on_bt; break;
            case SCANNING: label = R.string.scanning; btn = R.string.stop; break;
            case CONNECTING: label = R.string.connecting; break;
            case READY: label = R.string.connected; break;
            default: label = R.string.idle; break;
        }
        statusView.setText(label);
        scanBtn.setText(btn);
        if (detail != null) {
            statusDetail.setText(detail);
        } else if (s == LampLink.State.IDLE && deviceList.getChildCount() == 0
                && prefs.getString(KEY_ADDRESS, null) == null) {
            statusDetail.setText(R.string.no_lamps);
        } else if (s == LampLink.State.READY) {
            statusDetail.setText(prefs.getString(KEY_NAME, ""));
        }

        boolean ready = s == LampLink.State.READY;
        statusView.setTextColor(Palette.color(this, ready ? R.color.accent : R.color.text));
        controls.setAlpha(ready ? 1f : 0.4f);
        effectsPanel.setAlpha(ready ? 1f : 0.4f);
    }

    // ---- controls ----------------------------------------------------------------------

    private void setPower(boolean on) {
        powerOn = on;
        prefs.edit().putBoolean(KEY_POWER, on).apply();
        link.send(Elk.power(on ? Elk.Switch.ON : Elk.Switch.OFF));
        renderPower();
    }

    private void setColour(int rgb) {
        colour = rgb;
        prefs.edit().putInt(KEY_COLOUR, rgb).apply();
        link.send(Elk.colour(rgb));
        // A static colour ends whatever effect was running on the lamp.
        effect = Elk.AUTO_EFFECT;
        renderEffect();
    }

    private void setEffect(int mode) {
        effect = Math.max(Elk.AUTO_EFFECT, Math.min(Elk.MAX_EFFECT, mode));
        prefs.edit().putInt(KEY_EFFECT, effect).apply();
        link.send(Elk.effect(effect));
        renderEffect();
    }

    private void renderPower() {
        powerBtn.setText(powerOn ? R.string.on : R.string.off);
        powerBtn.setBackgroundResource(powerOn ? R.drawable.btn_accent : R.drawable.btn_ghost);
        powerBtn.setTextColor(powerOn ? Palette.onAccent(this) : Palette.color(this, R.color.text));
    }

    private void renderEffect() {
        effectName.setText(effect == Elk.AUTO_EFFECT
            ? getString(R.string.auto)
            : getString(R.string.effect) + " " + effect);
    }

    private static void renderPercent(TextView v, int pct) {
        v.setText(pct + "%");
    }

    /** Slider → percent frame, live while dragging, value label and pref kept in step. */
    private abstract class PercentListener implements SeekBar.OnSeekBarChangeListener {
        private final TextView label;
        private final String key;

        PercentListener(TextView label, String key) {
            this.label = label;
            this.key = key;
        }

        abstract byte[] frame(int pct);

        @Override public void onProgressChanged(SeekBar bar, int pct, boolean fromUser) {
            renderPercent(label, pct);
            if (!fromUser) {
                return;
            }
            link.send(frame(pct));
        }

        @Override public void onStartTrackingTouch(SeekBar bar) {}

        @Override public void onStopTrackingTouch(SeekBar bar) {
            prefs.edit().putInt(key, bar.getProgress()).apply();
        }
    }
}
