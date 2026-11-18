package com.inkdrop.app;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int PORT = 8080;
    private static final int REQ_WIFI = 1;
    private static final int REQ_STORAGE = 2;
    /** GitHub repo whose latest release the Update button installs. */
    private static final String REPO = "Userfive05/Inkdrop";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WifiManager wifi;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private FileServer server;
    private SharedPreferences prefs;

    private TextView status;
    private TextView logView;
    private EditText folderInput;
    private LinearLayout qrRow, qrWifiCol, qrUrlCol;
    private ImageView qrWifiImg, qrUrlImg;
    private TextView qrWifiCaption, qrUrlCaption;
    private Button updateBtn;
    private TextView updateStatus;

    private String ssid, pass;
    private boolean sae; // hotspot uses WPA3-only security
    private boolean pendingHotspot;
    private volatile boolean updating;
    private String lastQrKey;
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

        col.addView(text("InkDrop", 30, true));
        col.addView(text("Send files from your phone's browser", 16, false));
        col.addView(spacer(16));

        status = text("", 20, false);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setBackgroundColor(Color.rgb(238, 238, 238));
        status.setTextIsSelectable(true);
        col.addView(status, matchWrap());

        // QR codes: [join Wi-Fi] [open page]
        qrRow = new LinearLayout(this);
        qrRow.setOrientation(LinearLayout.HORIZONTAL);
        qrRow.setGravity(Gravity.CENTER_HORIZONTAL);
        qrRow.setPadding(0, dp(16), 0, 0);
        qrRow.setVisibility(View.GONE);
        qrWifiCol = new LinearLayout(this);
        qrWifiImg = new ImageView(this);
        qrWifiCaption = text("", 17, true);
        buildQrColumn(qrWifiCol, qrWifiImg, qrWifiCaption);
        qrUrlCol = new LinearLayout(this);
        qrUrlImg = new ImageView(this);
        qrUrlCaption = text("", 17, true);
        buildQrColumn(qrUrlCol, qrUrlImg, qrUrlCaption);
        qrRow.addView(qrWifiCol);
        qrRow.addView(qrUrlCol);
        col.addView(qrRow, matchWrap());
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
        col.addView(spacer(28));

        col.addView(text("App version " + versionName(), 16, true));
        updateBtn = button("Check for update", v -> onUpdate());
        col.addView(updateBtn);
        updateStatus = text("Needs a Wi-Fi connection with internet.", 15, false);
        updateStatus.setPadding(0, dp(6), 0, 0);
        col.addView(updateStatus, matchWrap());

        setContentView(scroll);
    }

    private void buildQrColumn(LinearLayout c, ImageView img, TextView caption) {
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c.setLayoutParams(lp);
        img.setScaleType(ImageView.ScaleType.CENTER);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(4), 0, 0);
        c.addView(img);
        c.addView(caption);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.BLACK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
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

    private View spacer(int h) {
        View v = new View(this);
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

    // ---------------------------------------------------------------- server / hotspot actions

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
            status.setText("Hotspot already starting/active. Tap Stop, then try again.");
        }
    }

    @SuppressWarnings("deprecation")
    private void readCredentials(WifiManager.LocalOnlyHotspotReservation r) {
        ssid = "?"; pass = "?"; sae = false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                SoftApConfiguration c = r.getSoftApConfiguration();
                if (c.getSsid() != null) ssid = c.getSsid().replace("\"", "");
                if (c.getPassphrase() != null) pass = c.getPassphrase();
                sae = c.getSecurityType() == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE;
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
        status.setText("Not running.\n\nTap \"Start hotspot\", then scan the codes with your phone.");
        qrRow.setVisibility(View.GONE);
        lastQrKey = null;
    }

    private void showRunning() {
        if (server == null || !server.isRunning()) return;
        List<String> ips = localIps();
        String url = ips.isEmpty() ? null : "http://" + ips.get(0) + ":" + PORT;

        StringBuilder sb = new StringBuilder();
        if (reservation != null) {
            sb.append("Wi-Fi:      ").append(ssid).append("\n")
              .append("Password: ").append(pass).append("\n");
        } else {
            sb.append("Phone must be on the same Wi-Fi.\n");
        }
        sb.append("Page:       ").append(url != null ? url : "(waiting for an IP address…)");
        if (reservation != null) sb.append("\n\nIf the page won't load, turn off mobile data on your phone for a moment.");
        status.setText(sb.toString());

        if (url == null) { qrRow.setVisibility(View.GONE); return; }
        String key = (reservation != null ? ssid + pass : "") + url;
        if (key.equals(lastQrKey)) return; // e-ink: avoid redrawing identical codes
        lastQrKey = key;

        int avail = getResources().getDisplayMetrics().widthPixels - 2 * dp(24);
        if (reservation != null) {
            int size = Math.min((avail - dp(16)) / 2, dp(280));
            qrWifiImg.setImageBitmap(qr(wifiQrText(), size));
            qrWifiCaption.setText("1. Scan to join Wi-Fi");
            qrWifiCol.setVisibility(View.VISIBLE);
            qrUrlImg.setImageBitmap(qr(url, size));
            qrUrlCaption.setText("2. Scan to open page");
        } else {
            int size = Math.min(avail, dp(300));
            qrWifiCol.setVisibility(View.GONE);
            qrUrlImg.setImageBitmap(qr(url, size));
            qrUrlCaption.setText("Scan to open page");
        }
        qrRow.setVisibility(View.VISIBLE);
    }

    /** Standard Wi-Fi QR format understood by Android and iPhone cameras. */
    private String wifiQrText() {
        return "WIFI:T:" + (sae ? "SAE" : "WPA") + ";S:" + qrEscape(ssid) + ";P:" + qrEscape(pass) + ";;";
    }

    private static String qrEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    private static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        String n = ni.getName();
                        // Put the hotspot interface first
                        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("wlan1")) out.add(0, ip);
                        else out.add(ip);
                    }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- self-update from GitHub Releases

    private void onUpdate() {
        if (updating) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("Allow InkDrop to install apps, then tap \"Check for update\" again.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        updating = true;
        updateBtn.setEnabled(false);
        updateBtn.setText("Checking…");
        new Thread(() -> {
            try {
                runUpdate();
            } catch (Exception e) {
                String hint = reservation != null ? "\nStop the hotspot and connect to Wi-Fi with internet first." : "";
                setUpdateStatus("Update failed: " + e.getMessage() + hint);
            } finally {
                ui.post(() -> {
                    updating = false;
                    updateBtn.setEnabled(true);
                    updateBtn.setText("Check for update");
                });
            }
        }, "InkDrop-update").start();
    }

    private void runUpdate() throws Exception {
        JSONObject release = new JSONObject(httpGetString("https://api.github.com/repos/" + REPO + "/releases/latest"));
        String tag = release.optString("tag_name", "");
        int latest = parseBuildNumber(tag);
        int current = versionCode();
        if (latest <= current) {
            setUpdateStatus("You're up to date (1." + current + ").");
            return;
        }
        String apkUrl = null;
        long apkSize = -1;
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) {
                apkUrl = a.optString("browser_download_url");
                apkSize = a.optLong("size", -1);
                break;
            }
        }
        if (apkUrl == null) throw new Exception("latest release has no APK");

        setUpdateStatus("Downloading 1." + latest + "…");
        File apk = new File(getCacheDir(), "update.apk");
        HttpURLConnection c = open(apkUrl);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[65536];
            long done = 0;
            int lastPct = -1, n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (apkSize > 0) {
                    int pct = (int) (done * 100 / apkSize);
                    if (pct / 10 != lastPct / 10) { // few redraws on e-ink
                        lastPct = pct;
                        setUpdateStatus("Downloading 1." + latest + "… " + pct + "%");
                    }
                }
            }
        } finally {
            c.disconnect();
        }

        setUpdateStatus("Installing 1." + latest + "… confirm on screen.");
        ui.post(this::stopAll);
        install(apk);
    }

    private void install(File apk) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("inkdrop.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent result = PendingIntent.getBroadcast(this, sessionId, new Intent(this, InstallReceiver.class), flags);
            session.commit(result.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "InkDrop-updater");
        c.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
        int code = c.getResponseCode();
        if (code == 404) throw new Exception("no release found");
        if (code >= 400) throw new Exception("GitHub returned " + code);
        return c;
    }

    private static String httpGetString(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static int parseBuildNumber(String tag) {
        String digits = tag.replaceAll("[^0-9]", "");
        try { return digits.isEmpty() ? -1 : Integer.parseInt(digits); } catch (NumberFormatException e) { return -1; }
    }

    @SuppressWarnings("deprecation")
    private int versionCode() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void setUpdateStatus(String m) {
        ui.post(() -> updateStatus.setText(m));
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
