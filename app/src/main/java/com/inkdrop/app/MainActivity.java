package com.inkdrop.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PORT = 8080;
    private static final int REQ_WIFI = 1;
    private static final int REQ_STORAGE = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WifiManager wifi;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private FileServer server;
    private SharedPreferences prefs;

    private TextView status;
    private TextView logView;
    private EditText folderInput;
    private String ssid, pass;
    private boolean pendingHotspot;
    private final StringBuilder logText = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        prefs = getSharedPreferences("inkdrop", MODE_PRIVATE);
        buildUi();
        showIdle();
    }

    // ---------------------------------------------------------------- UI (built in code, e-ink friendly)

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        TextView title = text("InkDrop", 30, true);
        col.addView(title);
        col.addView(text("Send files from your phone's browser", 16, false));
        col.addView(spacer(16));

        status = text("", 22, false);
        status.setTextColor(Color.BLACK);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setBackgroundColor(Color.rgb(238, 238, 238));
        status.setTextIsSelectable(true);
        col.addView(status, matchWrap());
        col.addView(spacer(16));

        col.addView(button("Start hotspot", v -> onStartHotspot()));
        col.addView(button("Use current Wi-Fi instead", v -> onUseWifi()));
        col.addView(button("Stop", v -> stopAll()));
        col.addView(spacer(20));

        col.addView(text("Save folder (inside internal storage):", 16, false));
        folderInput = new EditText(this);
        folderInput.setInputType(InputType.TYPE_CLASS_TEXT);
        folderInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        folderInput.setTextColor(Color.BLACK);
        folderInput.setSingleLine(true);
        folderInput.setText(prefs.getString("folder", "Books"));
        col.addView(folderInput, matchWrap());
        col.addView(spacer(20));

        col.addView(text("Activity", 18, true));
        logView = text("", 15, false);
        logView.setTypeface(Typeface.MONOSPACE);
        col.addView(logView, matchWrap());

        setContentView(scroll);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.BLACK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private android.view.View spacer(int h) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void log(String m) {
        ui.post(() -> {
            String ts = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
            logText.insert(0, ts + "  " + m + "\n");
            if (logText.length() > 4000) logText.setLength(4000);
            logView.setText(logText);
        });
    }

    // ---------------------------------------------------------------- actions

    private void onStartHotspot() {
        if (!ensureStorage()) return;
        if (!ensureWifiPermission()) { pendingHotspot = true; return; }
        startHotspot();
    }

    private void onUseWifi() {
        if (!ensureStorage()) return;
        closeHotspot();
        if (startServer()) {
            ssid = null; pass = null;
            showRunning();
        }
    }

    private void startHotspot() {
        if (reservation != null) { showRunning(); return; }
        if (Build.VERSION.SDK_INT < 33 && Build.VERSION.SDK_INT >= 28) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null && !lm.isLocationEnabled()) {
                toast("Turn on Location in quick settings — Android needs it to start a hotspot.");
            }
        }
        status.setText("Starting hotspot…");
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    reservation = r;
                    readCredentials(r);
                    log("Hotspot on: " + ssid);
                    if (startServer()) {
                        showRunning();
                        // The hotspot interface can take a moment to get its IP.
                        ui.postDelayed(MainActivity.this::showRunning, 1500);
                        ui.postDelayed(MainActivity.this::showRunning, 4000);
                    }
                }

                @Override
                public void onStopped() {
                    reservation = null;
                    log("Hotspot stopped by system");
                    if (server != null && server.isRunning()) showRunning(); else showIdle();
                }

                @Override
                public void onFailed(int reason) {
                    reservation = null;
                    String why;
                    switch (reason) {
                        case ERROR_NO_CHANNEL: why = "no free Wi-Fi channel"; break;
                        case ERROR_INCOMPATIBLE_MODE: why = "another hotspot/tethering is on, or this device can't host and use Wi-Fi together"; break;
                        case ERROR_TETHERING_DISALLOWED: why = "hotspot is disabled on this device"; break;
                        default: why = "generic error";
                    }
                    log("Hotspot failed: " + why);
                    status.setText("Hotspot failed (" + why + ").\n\nTry turning Wi-Fi on, or use \"Use current Wi-Fi instead\".");
                }
            }, ui);
        } catch (SecurityException e) {
            status.setText("Permission denied: " + e.getMessage());
        } catch (IllegalStateException e) {
            // Thrown if a hotspot request from this app is already active
            status.setText("Hotspot already starting/active. Tap Stop, then try again.");
        }
    }

    @SuppressWarnings("deprecation")
    private void readCredentials(WifiManager.LocalOnlyHotspotReservation r) {
        ssid = "?"; pass = "?";
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                SoftApConfiguration c = r.getSoftApConfiguration();
                if (c.getSsid() != null) ssid = c.getSsid().replace("\"", "");
                if (c.getPassphrase() != null) pass = c.getPassphrase();
            } else {
                WifiConfiguration c = r.getWifiConfiguration();
                if (c != null) {
                    if (c.SSID != null) ssid = c.SSID.replace("\"", "");
                    if (c.preSharedKey != null) pass = c.preSharedKey.replace("\"", "");
                }
            }
        } catch (Exception e) {
            log("Couldn't read hotspot details: " + e.getMessage());
        }
    }

    private boolean startServer() {
        String folderName = folderInput.getText().toString().trim();
        if (folderName.isEmpty()) folderName = "Books";
        prefs.edit().putString("folder", folderName).apply();
        File root = new File(Environment.getExternalStorageDirectory(), folderName);
        if (!root.exists() && !root.mkdirs()) {
            toast("Can't create folder " + root);
            return false;
        }
        try {
            if (server == null) {
                server = new FileServer(PORT, root, loadAsset("index.html"), this::log);
            } else {
                server.setRoot(root);
            }
            if (!server.isRunning()) {
                server.start();
                log("Server listening on port " + PORT + ", saving to " + root.getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            status.setText("Couldn't start server: " + e.getMessage());
            return false;
        }
    }

    private void stopAll() {
        if (server != null) { server.stop(); server = null; log("Server stopped"); }
        closeHotspot();
        showIdle();
    }

    private void closeHotspot() {
        if (reservation != null) {
            reservation.close();
            reservation = null;
            log("Hotspot off");
        }
    }

    private void showIdle() {
        status.setText("Not running.\n\nTap \"Start hotspot\", then join that network on your phone.");
    }

    private void showRunning() {
        if (server == null || !server.isRunning()) return;
        StringBuilder sb = new StringBuilder();
        if (reservation != null) {
            sb.append("1. On your phone, join Wi-Fi:\n")
              .append("   Network:  ").append(ssid).append("\n")
              .append("   Password: ").append(pass).append("\n\n")
              .append("2. Open in the browser:\n");
        } else {
            sb.append("Phone must be on the same Wi-Fi.\nOpen in the browser:\n");
        }
        List<String> ips = localIps();
        if (ips.isEmpty()) sb.append("   (waiting for an IP address…)\n");
        for (String ip : ips) sb.append("   http://").append(ip).append(":").append(PORT).append("\n");
        sb.append("\nKeep this screen open while transferring.");
        status.setText(sb.toString());
    }

    private static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        // Put the hotspot interface first
                        String ip = a.getHostAddress();
                        if (ni.getName().startsWith("ap") || ni.getName().startsWith("swlan") || ni.getName().startsWith("wlan1"))
                            out.add(0, ip);
                        else out.add(ip);
                    }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- permissions

    private boolean ensureWifiPermission() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{perm}, REQ_WIFI);
        return false;
    }

    private boolean ensureStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) return true;
            toast("Allow \"All files access\" for InkDrop, then come back and tap again.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            return false;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_WIFI) {
            if (granted && pendingHotspot) startHotspot();
            else if (!granted) status.setText("Wi-Fi permission is needed to start the hotspot.");
            pendingHotspot = false;
        } else if (requestCode == REQ_STORAGE && !granted) {
            status.setText("Storage permission is needed to save files.");
        }
    }

    // ---------------------------------------------------------------- misc

    private String loadAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private void toast(String m) {
        Toast t = Toast.makeText(this, m, Toast.LENGTH_LONG);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) stopAll();
        super.onDestroy();
    }
}
