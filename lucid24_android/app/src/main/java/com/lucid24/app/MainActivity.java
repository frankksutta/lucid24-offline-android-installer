package com.lucid24.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.*;
import java.net.*;

public class MainActivity extends AppCompatActivity {

    static final String UPDATE_URL = "https://drive.google.com/uc?export=download&id=1xh0Xoo0Lx6geBpeG65FPC3VQYR5D-mdi";
    static final String INFO_URL   = "https://drive.google.com/uc?export=download&id=12os0vf6HSjscUVSNLlpfdM1WWBARmrUo";

    TextView tvStatus, tvCountdown, tvInfo;
    Button btnUpdate, btnOffline;
    ProgressBar progressBar;
    CountDownTimer countdown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus    = findViewById(R.id.tvStatus);
        tvCountdown = findViewById(R.id.tvCountdown);
        tvInfo      = findViewById(R.id.tvInfo);
        btnUpdate   = findViewById(R.id.btnUpdate);
        btnOffline  = findViewById(R.id.btnOffline);
        progressBar = findViewById(R.id.progressBar);
        fetchInfoAsync();
    }

    void fetchInfoAsync() {
        tvStatus.setText("Checking for updates…");
        new Thread(() -> {
            String info = fetchText(INFO_URL);
            runOnUiThread(() -> {
                if (info != null) {
                    tvInfo.setText(info);
                    tvStatus.setText("Update available!");
                    btnUpdate.setVisibility(View.VISIBLE);
                    startCountdown();
                } else {
                    tvStatus.setText("Offline — no update available.");
                    btnOffline.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    void startCountdown() {
        countdown = new CountDownTimer(10_000, 1_000) {
            public void onTick(long ms) {
                tvCountdown.setText("Auto-downloading in " + (ms/1000) + "s…");
            }
            public void onFinish() {
                tvCountdown.setText("");
                downloadAndInstall();
            }
        }.start();
    }

    public void onUpdateClicked(View v) {
        if (countdown != null) countdown.cancel();
        tvCountdown.setText("");
        downloadAndInstall();
    }

    public void onOfflineClicked(View v) {
        tvStatus.setText("Running in offline mode.");
        btnOffline.setVisibility(View.GONE);
    }

    void downloadAndInstall() {
        tvStatus.setText("Downloading…");
        progressBar.setVisibility(View.VISIBLE);
        btnUpdate.setEnabled(false);
        new Thread(() -> {
            try {
                File outFile = new File(getCacheDir(), "latest.zip");
                URL url = new URL(UPDATE_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                int total = conn.getContentLength();
                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buf = new byte[4096]; int read, downloaded = 0;
                while ((read = in.read(buf)) != -1) {
                    fos.write(buf, 0, read); downloaded += read;
                    if (total > 0) {
                        int pct = (int)(downloaded * 100L / total);
                        runOnUiThread(() -> progressBar.setProgress(pct));
                    }
                }
                fos.close(); in.close();
                runOnUiThread(() -> installApk(outFile));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("Download failed: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                    btnUpdate.setEnabled(true);
                });
            }
        }).start();
    }

    void installApk(File zipFile) {
        tvStatus.setText("Installing…");
        try {
            File apkFile = new File(getCacheDir(), "lucid24.apk");
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.getName().endsWith(".apk")) {
                        InputStream is = zf.getInputStream(entry);
                        FileOutputStream fos = new FileOutputStream(apkFile);
                        byte[] buf = new byte[4096]; int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                        fos.close(); is.close(); break;
                    }
                }
            }
            Uri apkUri = FileProvider.getUriForFile(this,
                "com.lucid24.app.fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            tvStatus.setText("Install failed: " + e.getMessage());
        }
    }

    String fetchText(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
            BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            return sb.toString().trim();
        } catch (Exception e) { return null; }
    }
}
