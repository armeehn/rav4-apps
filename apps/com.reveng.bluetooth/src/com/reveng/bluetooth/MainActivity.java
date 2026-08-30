package com.reveng.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Clean-room standalone Bluetooth manager. Replaces the GT6 OEM
 * com.szchoiceway.btsuite. Pure android.* framework only (no AndroidX).
 *
 * Shows adapter on/off state with a toggle, the set of paired (bonded)
 * devices with live A2DP/HEADSET connection state, and a live scan for
 * nearby devices that can be bonded with a tap.
 *
 * As a normal (non-privileged) app it cannot programmatically issue a
 * profile connect/disconnect (those APIs require system / BLUETOOTH_PRIVILEGED),
 * nor silently disable the adapter, so those actions defer to the system
 * Bluetooth settings screen. Everything else runs in-app.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;
    private static final int REQ_ENABLE = 2;

    private BluetoothAdapter adapter;

    // views
    private TextView subtitle, emptyText, emptyHint, pairedEmpty, scanEmpty, btnPair;
    private Switch toggle;
    private ImageButton btnScan, btnSettings;
    private ImageView hdrIcon;
    private LinearLayout pairedList, scanList, empty;
    private ScrollView content;
    private ProgressBar scanSpinner;
    private Button grant;

    // profile proxies for live connection state
    private BluetoothA2dp a2dp;
    private BluetoothHeadset headset;

    // discovered (non-bonded) devices, keyed by address to dedupe
    private final LinkedHashMap<String, BluetoothDevice> discovered = new LinkedHashMap<>();
    // addresses currently mid-bond (for a "Pairing…" hint)
    private final ArrayList<String> bonding = new ArrayList<>();

    private boolean scanning = false;
    private boolean syncingToggle = false;

    // resolved palette
    private int cAccent, cAccentDim, cSurface, cSurface2, cStroke, cText, cText2, cText3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cAccent = getColor(R.color.accent);
        cAccentDim = getColor(R.color.accent_dim);
        cSurface = getColor(R.color.surface);
        cSurface2 = getColor(R.color.surface2);
        cStroke = getColor(R.color.stroke);
        cText = getColor(R.color.text);
        cText2 = getColor(R.color.text2);
        cText3 = getColor(R.color.text3);

        hdrIcon = findViewById(R.id.hdr_icon);
        subtitle = findViewById(R.id.subtitle);
        toggle = findViewById(R.id.toggle);
        btnScan = findViewById(R.id.btn_scan);
        btnSettings = findViewById(R.id.btn_settings);
        content = findViewById(R.id.content);
        pairedList = findViewById(R.id.paired_list);
        scanList = findViewById(R.id.scan_list);
        pairedEmpty = findViewById(R.id.paired_empty);
        scanEmpty = findViewById(R.id.scan_empty);
        btnPair = findViewById(R.id.btn_pair);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.empty_text);
        emptyHint = findViewById(R.id.empty_hint);
        scanSpinner = findViewById(R.id.scan_spinner);
        grant = findViewById(R.id.grant);

        adapter = BluetoothAdapter.getDefaultAdapter();

        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (syncingToggle) return;
                onToggle(checked);
            }
        });
        btnScan.setOnClickListener(v -> onScanPressed());
        btnSettings.setOnClickListener(v -> openBtSettings());
        btnPair.setOnClickListener(v -> openBtSettings());
        grant.setOnClickListener(v -> requestPerms());
    }

    // ---- lifecycle: receivers + proxies -------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(receiver, f);

        if (adapter == null) {
            showUnavailable();
            return;
        }
        if (!hasAllPerms()) {
            requestPerms();
            return;
        }
        acquireProxies();
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        stopScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseProxies();
    }

    private void acquireProxies() {
        if (adapter == null) return;
        try {
            if (a2dp == null) adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
            if (headset == null) adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET);
        } catch (Exception ignored) {}
    }

    private void releaseProxies() {
        if (adapter == null) return;
        try { if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp); } catch (Exception ignored) {}
        try { if (headset != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset); } catch (Exception ignored) {}
        a2dp = null;
        headset = null;
    }

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) a2dp = (BluetoothA2dp) proxy;
            else if (profile == BluetoothProfile.HEADSET) headset = (BluetoothHeadset) proxy;
            render();
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) a2dp = null;
            else if (profile == BluetoothProfile.HEADSET) headset = null;
        }
    };

    // ---- permissions --------------------------------------------------------

    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN };
        } else if (Build.VERSION.SDK_INT >= 29) {
            return new String[]{ Manifest.permission.ACCESS_FINE_LOCATION };
        }
        return new String[0];
    }

    private boolean hasAllPerms() {
        for (String p : neededPerms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void requestPerms() {
        String[] p = neededPerms();
        if (p.length == 0) { render(); return; }
        requestPermissions(p, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req != REQ_PERM) return;
        if (hasAllPerms()) {
            grant.setVisibility(View.GONE);
            acquireProxies();
            render();
        } else {
            // graceful degradation: explain, offer a retry
            content.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.app_name);
            emptyHint.setText(R.string.need_permission);
            grant.setVisibility(View.VISIBLE);
        }
    }

    // ---- toggle / settings --------------------------------------------------

    private void onToggle(boolean wantOn) {
        if (adapter == null) return;
        if (wantOn) {
            if (!adapter.isEnabled()) {
                // Use the system enable prompt rather than the deprecated silent enable().
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            }
        } else {
            if (adapter.isEnabled()) {
                // A normal app cannot programmatically disable the adapter
                // (disable() is deprecated and a no-op for non-system apps),
                // so defer to system Bluetooth settings.
                Toast.makeText(this, R.string.settings, Toast.LENGTH_SHORT).show();
                openBtSettings();
                syncToggle(); // revert until the OS actually changes state
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ENABLE) {
            syncToggle();
            render();
        }
    }

    private void openBtSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, R.string.state_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- scanning -----------------------------------------------------------

    private void onScanPressed() {
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, R.string.state_off, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasAllPerms()) { requestPerms(); return; }
        if (scanning) { stopScan(); return; }
        discovered.clear();
        renderScan();
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            adapter.startDiscovery();
        } catch (SecurityException e) {
            requestPerms();
        }
    }

    private void stopScan() {
        if (adapter == null) return;
        try { if (adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (Exception ignored) {}
    }

    // ---- broadcast receiver -------------------------------------------------

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    if (adapter != null && adapter.isEnabled()) acquireProxies();
                    else discovered.clear();
                    render();
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    scanning = true;
                    renderScanChrome();
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    scanning = false;
                    renderScanChrome();
                    renderScan();
                    break;
                case BluetoothDevice.ACTION_FOUND: {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) break;
                    if (d.getBondState() == BluetoothDevice.BOND_BONDED) break;
                    discovered.put(d.getAddress(), d);
                    renderScan();
                    break;
                }
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE);
                    if (d != null) {
                        if (state == BluetoothDevice.BOND_BONDING) {
                            if (!bonding.contains(d.getAddress())) bonding.add(d.getAddress());
                        } else {
                            bonding.remove(d.getAddress());
                            if (state == BluetoothDevice.BOND_BONDED) {
                                discovered.remove(d.getAddress());
                            }
                        }
                    }
                    render();
                    break;
                }
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                    renderPaired();
                    break;
                default:
                    break;
            }
        }
    };

    // ---- rendering ----------------------------------------------------------

    private void render() {
        if (adapter == null) { showUnavailable(); return; }

        syncToggle();

        boolean on = adapter.isEnabled();
        int st = adapter.getState();
        if (st == BluetoothAdapter.STATE_TURNING_ON) subtitle.setText(R.string.state_turning_on);
        else if (st == BluetoothAdapter.STATE_TURNING_OFF) subtitle.setText(R.string.state_turning_off);
        else subtitle.setText(on ? R.string.state_on : R.string.state_off);

        hdrIcon.setColorFilter(on ? cAccent : cText3);
        setOval(hdrIcon, on ? cAccentDim : cSurface2);

        if (!on) {
            content.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.empty_off_title);
            emptyHint.setText(R.string.empty_off_hint);
            grant.setVisibility(View.GONE);
            return;
        }

        empty.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
        renderPaired();
        renderScan();
        renderScanChrome();
    }

    private void showUnavailable() {
        syncingToggle = true; toggle.setEnabled(false); syncingToggle = false;
        subtitle.setText(R.string.state_unavailable);
        content.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
        emptyText.setText(R.string.state_unavailable);
        emptyHint.setText("");
        grant.setVisibility(View.GONE);
    }

    private void syncToggle() {
        if (adapter == null) return;
        syncingToggle = true;
        toggle.setChecked(adapter.isEnabled());
        syncingToggle = false;
    }

    private void renderPaired() {
        pairedList.removeAllViews();
        ArrayList<BluetoothDevice> bonded = new ArrayList<>();
        try {
            if (adapter != null) bonded.addAll(adapter.getBondedDevices());
        } catch (SecurityException e) {
            requestPerms();
            return;
        }
        if (bonded.isEmpty()) {
            pairedEmpty.setVisibility(View.VISIBLE);
        } else {
            pairedEmpty.setVisibility(View.GONE);
            for (BluetoothDevice d : bonded) {
                int cs = connState(d);
                String status;
                boolean connected = cs == BluetoothProfile.STATE_CONNECTED;
                if (connected) status = getString(R.string.st_connected);
                else if (cs == BluetoothProfile.STATE_CONNECTING) status = getString(R.string.st_connecting);
                else if (cs == BluetoothProfile.STATE_DISCONNECTING) status = getString(R.string.st_disconnecting);
                else if (bonding.contains(d.getAddress())) status = getString(R.string.st_bonding);
                else status = typeLabel(d) + " · " + getString(R.string.st_paired);
                pairedList.addView(deviceRow(d, status, connected, false));
            }
        }
    }

    private void renderScan() {
        scanList.removeAllViews();
        ArrayList<BluetoothDevice> devs = new ArrayList<>(discovered.values());
        if (devs.isEmpty()) {
            scanEmpty.setVisibility(View.VISIBLE);
            scanEmpty.setText(scanning ? R.string.scanning : R.string.empty_scan_hint);
        } else {
            scanEmpty.setVisibility(View.GONE);
            for (BluetoothDevice d : devs) {
                String status = bonding.contains(d.getAddress())
                        ? getString(R.string.st_bonding)
                        : typeLabel(d) + " · " + getString(R.string.st_available);
                scanList.addView(deviceRow(d, status, false, true));
            }
        }
    }

    private void renderScanChrome() {
        scanSpinner.setVisibility(scanning ? View.VISIBLE : View.GONE);
        btnScan.setColorFilter(scanning ? cAccent : cText);
        if (scanning && discovered.isEmpty()) {
            scanEmpty.setVisibility(View.VISIBLE);
            scanEmpty.setText(R.string.scanning);
        }
    }

    // ---- a device card row --------------------------------------------------

    private View deviceRow(final BluetoothDevice d, String status,
                           boolean connected, final boolean discoverable) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(16);
        row.setPadding(padH, dp(12), padH, dp(12));
        row.setMinimumHeight(dp(68));
        row.setBackground(cardBg());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(10);
        row.setLayoutParams(rlp);

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_bluetooth);
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ap = dp(11);
        avatar.setPadding(ap, ap, ap, ap);
        setOval(avatar, connected ? cAccentDim : cSurface2);
        avatar.setColorFilter(connected ? cAccent : cText2);
        row.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(14);
        row.addView(col, clp);

        TextView name = new TextView(this);
        name.setTextColor(cText);
        name.setTextSize(17);
        name.setTypeface(Typeface.create("sans-serif-medium", 0));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(deviceName(d));
        col.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setTextColor(connected ? cAccent : cText2);
        sub.setTextSize(13);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setText(status);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        col.addView(sub, slp);

        // trailing affordance icon
        ImageView trail = new ImageView(this);
        trail.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int tp = dp(6);
        trail.setPadding(tp, tp, tp, tp);
        if (discoverable) {
            trail.setImageResource(R.drawable.ic_add);
            trail.setColorFilter(cAccent);
        } else {
            trail.setImageResource(R.drawable.ic_settings);
            trail.setColorFilter(cText3);
        }
        row.addView(trail, new LinearLayout.LayoutParams(dp(30), dp(30)));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (discoverable) bondDevice(d);
                else openBtSettings(); // connect/disconnect needs system perms → defer
            }
        });
        return row;
    }

    private void bondDevice(BluetoothDevice d) {
        stopScan();
        try {
            if (d.getBondState() == BluetoothDevice.BOND_NONE) {
                if (!d.createBond()) {
                    Toast.makeText(this, R.string.state_unavailable, Toast.LENGTH_SHORT).show();
                }
            }
        } catch (SecurityException e) {
            requestPerms();
        }
    }

    // ---- helpers ------------------------------------------------------------

    private int connState(BluetoothDevice d) {
        int best = BluetoothProfile.STATE_DISCONNECTED;
        try {
            if (a2dp != null) {
                int cs = a2dp.getConnectionState(d);
                if (cs == BluetoothProfile.STATE_CONNECTED) return cs;
                if (cs == BluetoothProfile.STATE_CONNECTING) best = cs;
            }
        } catch (Exception ignored) {}
        try {
            if (headset != null) {
                int cs = headset.getConnectionState(d);
                if (cs == BluetoothProfile.STATE_CONNECTED) return cs;
                if (cs == BluetoothProfile.STATE_CONNECTING && best == BluetoothProfile.STATE_DISCONNECTED)
                    best = cs;
            }
        } catch (Exception ignored) {}
        return best;
    }

    private String deviceName(BluetoothDevice d) {
        String n = null;
        try { n = d.getName(); } catch (Exception ignored) {}
        if (n == null || n.isEmpty()) n = d.getAddress();
        return n;
    }

    private String typeLabel(BluetoothDevice d) {
        BluetoothClass bc = null;
        try { bc = d.getBluetoothClass(); } catch (Exception ignored) {}
        if (bc == null) return getString(R.string.type_device);
        switch (bc.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.PHONE:
                return getString(R.string.type_phone);
            case BluetoothClass.Device.Major.AUDIO_VIDEO:
                int dc = bc.getDeviceClass();
                if (dc == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
                        || dc == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET
                        || dc == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES) {
                    return getString(R.string.type_headset);
                }
                return getString(R.string.type_audio);
            case BluetoothClass.Device.Major.COMPUTER:
                return getString(R.string.type_computer);
            case BluetoothClass.Device.Major.WEARABLE:
                return getString(R.string.type_wearable);
            case BluetoothClass.Device.Major.IMAGING:
                return getString(R.string.type_imaging);
            case BluetoothClass.Device.Major.PERIPHERAL:
                return getString(R.string.type_peripheral);
            default:
                return getString(R.string.type_device);
        }
    }

    private GradientDrawable cardBg() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(18));
        g.setColor(cSurface);
        g.setStroke(dp(1), cStroke);
        return g;
    }

    private void setOval(View v, int color) {
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(color);
        v.setBackground(oval);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
