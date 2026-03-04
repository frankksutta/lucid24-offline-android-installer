package com.lucid24.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import java.io.*;
import java.net.*;
import java.util.zip.*;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    // ── Installer version — bump this before every APK upload ───────────────
    static final String APP_VERSION = "1.0.1";

    // ── URLs (GitHub releases — direct download, no confirmation page) ──────
    static final String INFO_URL           = "https://github.com/frankksutta/lucid24-offline-releases/releases/latest/download/lucid24-offline-latest-info.txt";
    static final String UPDATE_URL         = "https://github.com/frankksutta/lucid24-offline-releases/releases/latest/download/lucid24-offline-latest.zip";
    static final String INSTALLER_INFO_URL = "https://github.com/frankksutta/lucid24-offline-releases/releases/latest/download/lucid24-offline-android-installer-info.json";
    static final String INSTALLER_DL_URL  = "https://github.com/frankksutta/lucid24-offline-releases/releases/latest/download/lucid24-offline-android-installer.apk";

    static final int COUNTDOWN_UPTODATE = 5;
    static final int COUNTDOWN_OFFLINE  = 8;
    static final String PREFS_NAME      = "lucid24";
    static final String PREF_DATE       = "installed_date";

    // ── UI ───────────────────────────────────────────────────────────────────
    TextView    tvStatus, tvInfo, tvSize, tvFooter;
    ProgressBar progressBar;
    Button      btnLaunch, btnUpdate;
    WebView     webView;
    LinearLayout mainLayout;

    // ── State ────────────────────────────────────────────────────────────────
    CountDownTimer countdown;
    String remoteDate   = "";
    float  remoteSizeMb = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus    = findViewById(R.id.tvStatus);
        tvInfo      = findViewById(R.id.tvInfo);
        tvSize      = findViewById(R.id.tvSize);
        tvFooter    = findViewById(R.id.tvFooter);
        progressBar = findViewById(R.id.progressBar);
        btnLaunch   = findViewById(R.id.btnLaunch);
        btnUpdate   = findViewById(R.id.btnUpdate);
        webView     = findViewById(R.id.webView);
        mainLayout  = findViewById(R.id.mainLayout);

        // Show installer version + content date in footer
        updateFooter();

        // Setup WebView
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setAllowFileAccess(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient());

        btnLaunch.setEnabled(false);
        btnUpdate.setOnClickListener(v -> manualCheck());
        btnLaunch.setOnClickListener(v -> {
            cancelCountdown();
            launchWebsite();
        });

        // Restore WebView state if activity was recreated (e.g. low-memory kill)
        if (savedInstanceState != null && savedInstanceState.getBoolean("in_webview", false)) {
            webView.restoreState(savedInstanceState);
            mainLayout.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            return; // skip installer flow — we're already browsing
        }

        updateSizeDisplay();
        autoCheck();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (webView.getVisibility() == View.VISIBLE) {
            webView.saveState(out);
            out.putBoolean("in_webview", true);
        }
    }

    // ── FOOTER ───────────────────────────────────────────────────────────────

    void updateFooter() {
        String contentDate = getInstalledDate();
        String content = contentDate.isEmpty() ? "not installed" : contentDate;
        tvFooter.setText("Installer v" + APP_VERSION + "  |  Content: " + content);
    }

    // ── AUTO CHECK ───────────────────────────────────────────────────────────

    void autoCheck() {
        boolean hasLocal = findIndex() != null;
        if (hasLocal) {
            btnLaunch.setEnabled(true);
            tvInfo.setText("Installed: " + getInstalledDate());
        }
        setStatus("Checking for updates\u2026");
        new Thread(() -> {
            checkForUpdates(true);
            checkInstallerVersion();  // runs after content check, non-blocking
        }).start();
    }

    void manualCheck() {
        cancelCountdown();
        btnUpdate.setEnabled(false);
        new Thread(() -> checkForUpdates(false)).start();
    }

    void checkForUpdates(boolean isAuto) {
        JSONObject remote = fetchRemoteInfo();

        if (remote == null) {
            // No internet
            runOnUiThread(() -> btnUpdate.setEnabled(true));
            File index = findIndex();
            if (index != null && isAuto) {
                setStatus("\uD83D\uDCF6 Offline — launching local copy\u2026");
                setInfo("Offline mode. Opening local website shortly.");
                runOnUiThread(() -> {
                    btnLaunch.setEnabled(true);
                    startCountdown(COUNTDOWN_OFFLINE, "\uD83D\uDCF6 Offline —");
                });
            } else if (index != null) {
                setStatus("\u26A0\uFE0F No internet connection");
                setInfo("Could not check for updates. You can still launch the local copy.");
                runOnUiThread(() -> btnLaunch.setEnabled(true));
            } else {
                setStatus("\u26A0\uFE0F No internet and no local copy installed");
                setInfo("Connect to the internet and tap 'Check Updates' to download.");
            }
            return;
        }

        try {
            remoteDate   = remote.getString("date");
            remoteSizeMb = (float) remote.getDouble("size_mb");
        } catch (Exception e) {
            setStatus("Error reading update info");
            runOnUiThread(() -> btnUpdate.setEnabled(true));
            return;
        }

        String localDate  = getInstalledDate();
        String sizeStr    = String.format("%.0f MB zip", remoteSizeMb);
        File   index      = findIndex();

        setInfo("Installed: " + (localDate.isEmpty() ? "none" : localDate)
                + "  |  Latest: " + remoteDate + "  (" + sizeStr + ")");

        if (localDate.equals(remoteDate) && findIndex() != null) {
            // Up to date
            setProgressBar(100);
            runOnUiThread(() -> btnUpdate.setEnabled(true));
            if (index != null && isAuto) {
                setStatus("\u2705 Up to date! (" + remoteDate + ")");
                runOnUiThread(() -> {
                    btnLaunch.setEnabled(true);
                    startCountdown(COUNTDOWN_UPTODATE, "\u2705 Up to date!");
                });
            } else {
                setStatus("\u2705 Up to date! (" + remoteDate + ")");
                runOnUiThread(() -> btnLaunch.setEnabled(true));
            }
            return;
        }

        // Update available
        String msg = localDate.isEmpty()
            ? "New install available: " + remoteDate + " (" + sizeStr + ")"
            : "Update: " + localDate + " \u2192 " + remoteDate + " (" + sizeStr + ")";
        setStatus("\uD83D\uDCE5 " + msg);

        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage(msg + "\n\nDownload and install now?")
                .setPositiveButton("Download", (d, w) -> {
                    new Thread(() -> downloadAndInstall()).start();
                })
                .setNegativeButton("Skip", (d, w) -> {
                    setStatus("Update skipped");
                    if (findIndex() != null) btnLaunch.setEnabled(true);
                    btnUpdate.setEnabled(true);
                })
                .setCancelable(false)
                .show();
        });
    }

    // ── INSTALLER VERSION CHECK ──────────────────────────────────────────────

    void checkInstallerVersion() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(INSTALLER_INFO_URL).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONObject info    = new JSONObject(sb.toString());
            String remoteVer   = info.optString("version", "");
            String releaseNotes = info.optString("notes", "");

            if (remoteVer.isEmpty() || remoteVer.equals(APP_VERSION)) return;

            // Simple string comparison works for semver if you keep major/minor/patch zero-padded
            // or just let any difference trigger the alert — owner controls both sides
            String msg = "A new version of the Lucid24 Android Installer is available."
                + "\n\nThis is the installer app itself — separate from the Lucid24 content."
                + "\n\nInstalled installer:  v" + APP_VERSION
                + "\nAvailable installer:  v" + remoteVer
                + (releaseNotes.isEmpty() ? "" : "\n\nWhat\u2019s new: " + releaseNotes)
                + "\n\nTap \u2018Download\u2019 to open the download page in your browser."
                + " Install the APK file it downloads, then reopen the app.";

            runOnUiThread(() ->
                new AlertDialog.Builder(this)
                    .setTitle("\uD83D\uDCF2 Installer Update Available")
                    .setMessage(msg)
                    .setPositiveButton("Download", (d, w) -> openDownloadPage())
                    .setNegativeButton("Not Now", null)
                    .show()
            );

        } catch (Exception e) {
            // Silently ignore — installer update check is best-effort
        }
    }

    void openDownloadPage() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(INSTALLER_DL_URL));
        startActivity(intent);
    }

    // ── DOWNLOAD & INSTALL ───────────────────────────────────────────────────

    void downloadAndInstall() {
        setStatus("Downloading\u2026");
        setProgressBar(0);

        File tempZip = new File(getCacheDir(), "lucid24-offline-latest.zip");

        try {
            // Download
            HttpURLConnection conn = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int total = conn.getContentLength();
            InputStream in  = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(tempZip);
            byte[] buf = new byte[256 * 1024];
            int read, downloaded = 0;

            while ((read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
                downloaded += read;
                if (total > 0) {
                    int pct = (int)(downloaded * 60L / total);
                    setProgressBar(pct);
                    float mb = downloaded / (1024f * 1024f);
                    float tbMb = total / (1024f * 1024f);
                    setStatus(String.format("Downloading\u2026 %.1f / %.1f MB", mb, tbMb));
                } else {
                    float mb = downloaded / (1024f * 1024f);
                    setStatus(String.format("Downloading\u2026 %.1f MB", mb));
                }
            }
            fos.close(); in.close();

            // Validate zip
            try { new ZipFile(tempZip).close(); }
            catch (Exception e) {
                setStatus("ERROR: Invalid zip file — try again");
                setProgressBar(0);
                tempZip.delete();
                runOnUiThread(() -> btnUpdate.setEnabled(true));
                return;
            }

            // Extract
            setStatus("Extracting\u2026");
            setProgressBar(65);
            File siteDir = getSiteDir();
            deleteDir(siteDir);
            siteDir.mkdirs();

            ZipInputStream zis = new ZipInputStream(new FileInputStream(tempZip));
            ZipEntry entry;
            int count = 0;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(siteDir, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    FileOutputStream efos = new FileOutputStream(out);
                    byte[] ebuf = new byte[8192]; int n;
                    while ((n = zis.read(ebuf)) != -1) efos.write(ebuf, 0, n);
                    efos.close();
                }
                zis.closeEntry();
                count++;
                if (count % 200 == 0) {
                    int pct = 65 + (int)(count * 30.0 / 3000);
                    setProgressBar(Math.min(95, pct));
                }
            }
            zis.close();
            tempZip.delete();

            saveInstalledDate(remoteDate);
            updateFooter();
            setProgressBar(100);
            setStatus("\u2705 Installed! (" + remoteDate + ")");
            setInfo("Installed: " + remoteDate + "  |  " + count + " files extracted");
            updateSizeDisplay();

            runOnUiThread(() -> {
                btnLaunch.setEnabled(true);
                btnUpdate.setEnabled(true);
            });

        } catch (Exception e) {
            setStatus("Download failed: " + e.getMessage());
            setProgressBar(0);
            tempZip.delete();
            runOnUiThread(() -> btnUpdate.setEnabled(true));
        }
    }

    // ── LAUNCH WEBSITE ───────────────────────────────────────────────────────

    File findIndex() {
        File siteDir = getSiteDir();
        if (!siteDir.exists()) return null;

        // Try known paths first (mirrors lucid24.py logic)
        File known = new File(siteDir, "suttas-use-local-images/index.html");
        if (known.exists()) return known;

        // Fallback: find any index.html
        return findFirstIndex(siteDir);
    }

    File findFirstIndex(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals("index.html")) return f;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFirstIndex(f);
                if (found != null) return found;
            }
        }
        return null;
    }

    void launchWebsite() {
        cancelCountdown();
        File index = findIndex();
        if (index == null) {
            setStatus("Website files not found — tap 'Check Updates'");
            return;
        }
        // Switch to WebView mode
        runOnUiThread(() -> {
            mainLayout.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl("file://" + index.getAbsolutePath());
        });
    }

    // ── COUNTDOWN ────────────────────────────────────────────────────────────

    void startCountdown(int secs, String prefix) {
        cancelCountdown();
        countdown = new CountDownTimer(secs * 1000L, 1000) {
            public void onTick(long ms) {
                setStatus(prefix + " Launching in " + (ms / 1000) + "s\u2026  (tap to cancel)");
            }
            public void onFinish() {
                launchWebsite();
            }
        }.start();
    }

    void cancelCountdown() {
        if (countdown != null) { countdown.cancel(); countdown = null; }
    }

    // ── HELPERS ──────────────────────────────────────────────────────────────

    JSONObject fetchRemoteInfo() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(INFO_URL).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) { return null; }
    }

    File getSiteDir() {
        return new File(getFilesDir(), "lucid24_site");
    }

    String getInstalledDate() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_DATE, "");
    }

    void saveInstalledDate(String date) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_DATE, date).apply();
    }

    void updateSizeDisplay() {
        new Thread(() -> {
            String s = dirSizeStr(getSiteDir());
            runOnUiThread(() -> tvSize.setText("Installed size: " + s));
        }).start();
    }

    String dirSizeStr(File dir) {
        if (!dir.exists()) return "not installed";
        long total = 0; int count = 0;
        total = dirSize(dir);
        count = dirCount(dir);
        float mb = total / (1024f * 1024f);
        if (mb >= 1024) return String.format("%.1f GB (%,d files)", mb/1024f, count);
        return String.format("%.0f MB (%,d files)", mb, count);
    }

    long dirSize(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] files = f.listFiles();
        if (files != null) for (File c : files) s += dirSize(c);
        return s;
    }

    int dirCount(File f) {
        if (f.isFile()) return 1;
        int c = 0;
        File[] files = f.listFiles();
        if (files != null) for (File ch : files) c += dirCount(ch);
        return c;
    }

    void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }

    void setStatus(String msg) {
        runOnUiThread(() -> tvStatus.setText(msg));
    }

    void setInfo(String msg) {
        runOnUiThread(() -> tvInfo.setText(msg));
    }

    void setProgressBar(int pct) {
        runOnUiThread(() -> progressBar.setProgress(pct));
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack();
            else {
                webView.setVisibility(View.GONE);
                mainLayout.setVisibility(View.VISIBLE);
            }
        } else {
            super.onBackPressed();
        }
    }
}
