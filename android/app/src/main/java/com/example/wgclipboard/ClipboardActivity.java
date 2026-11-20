package com.example.wgclipboard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class ClipboardActivity extends Activity {
    private String action;
    private File path;
    private File resultFile;
    private TextView message;
    private boolean waitingForStorageSettings = false;
    private boolean actionStarted = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        message = new TextView(this);
        message.setText("WG Clipboard Helper\nWorking...");
        message.setPadding(32, 48, 32, 32);
        setContentView(message);

        Intent intent = getIntent();
        action = intent.getStringExtra("action");
        String p = intent.getStringExtra("path");
        String r = intent.getStringExtra("result");
        if (p == null || r == null || action == null) { finishWith("ERROR: missing arguments"); return; }
        path = new File(p);
        resultFile = new File(r);
        if (!isAllowedPath(path) || !isAllowedPath(resultFile)) { finishWith("ERROR: path not allowed"); return; }

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            waitingForStorageSettings = true;
            message.setText("Please allow All files access, then return here.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        requestImagePermissionOrRun();
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForStorageSettings && Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            waitingForStorageSettings = false;
            requestImagePermissionOrRun();
        }
    }

    private void requestImagePermissionOrRun() {
        // Reading a copied content:// URI should rely on the temporary URI grant
        // attached to the clipboard item, not broad READ_MEDIA_IMAGES access.
        // This also avoids Android 14+ selected-photo permission edge cases.
        if (!actionStarted) runAction();
    }

    private boolean isAllowedPath(File f) {
        try {
            String base = new File(getExternalFilesDir(null), "__never_match__").getCanonicalPath();
            String allowed = new File("/storage/emulated/0/Pictures/wg-clipboard").getCanonicalPath() + File.separator;
            String candidate = f.getCanonicalPath();
            return candidate.startsWith(allowed) && !candidate.equals(base);
        } catch (Exception e) { return false; }
    }

    private void runAction() {
        if (actionStarted) return;
        actionStarted = true;
        try {
            if ("get_image".equals(action)) getImage();
            else if ("set_image".equals(action)) setImage();
            else throw new IllegalArgumentException("Unknown action");
        } catch (Exception e) {
            finishWith("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void getImage() throws Exception {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) throw new Exception("clipboard empty");
        Uri uri = null;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getUri() != null) { uri = item.getUri(); break; }
        }
        if (uri == null) throw new Exception("clipboard has no image URI");
        ContentResolver resolver = getContentResolver();
        String mime = resolver.getType(uri);
        if (mime == null || !mime.startsWith("image/")) throw new Exception("clipboard item is not an image");
        try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(path)) {
            if (in == null) throw new Exception("cannot open clipboard image");
            copyBounded(in, out);
        }
        finishWith("OK");
    }

    private void setImage() throws Exception {
        if (!path.isFile() || path.length() == 0) throw new Exception("image file missing");
        String mime = getContentResolver().getType(Uri.fromFile(path));
        if (mime == null || !mime.startsWith("image/")) {
            String name = path.getName().toLowerCase();
            if (name.endsWith(".png")) mime = "image/png";
            else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) mime = "image/jpeg";
            else if (name.endsWith(".webp")) mime = "image/webp";
            else if (name.endsWith(".gif")) mime = "image/gif";
            else throw new Exception("unsupported image extension");
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", path);
        ClipData clip = ClipData.newUri(getContentResolver(), "WG Clipboard image", uri);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(clip);
        finishWith("OK");
    }

    private void copyBounded(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[32768];
        int n; long total = 0;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > 20L * 1024 * 1024) throw new Exception("image exceeds 20 MB");
            out.write(buffer, 0, n);
        }
    }

    private void finishWith(String status) {
        try {
            if (resultFile != null) {
                File parent = resultFile.getParentFile();
                if (parent != null) parent.mkdirs();
                try (FileOutputStream out = new FileOutputStream(resultFile)) {
                    out.write(status.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) { }
        if (message != null) message.setText(status);
        finish();
    }
}
