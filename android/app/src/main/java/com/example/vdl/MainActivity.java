package com.example.vdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.URLUtil;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * আপনার নিজের সার্ভার (পিসিতে চলা python app.py) এর ওয়েব পেজ দেখায়,
 * Share মেনু থেকে লিংক নেয়, আর ডাউনলোড করা ভিডিও Movies/VideoDownloader ফোল্ডারে রাখে।
 */
public class MainActivity extends Activity {

    private static final String PREFS = "vdl";
    private static final String KEY_SERVER = "server";
    private static final Pattern URL_RE = Pattern.compile("https?://\\S+");
    private static final Pattern FILENAME_STAR =
            Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)");
    private static final Pattern FILENAME_PLAIN =
            Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?");

    private WebView web;
    private SharedPreferences prefs;
    private String pendingShareText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);
        configureWebView();

        handleIntent(getIntent());
        openServer();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        openServer();
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, getString(R.string.error_html),
                            "text/html", "UTF-8", null);
                }
            }
        });

        // ওয়েব পেজ থেকে ডাউনলোড শুরু হলে এখানে আসে
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, contentDisposition, mimeType));
    }

    // ---------- Share করা লিংক ----------
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && URL_RE.matcher(text).find()) {
                pendingShareText = text;
            }
        }
    }

    // ---------- সার্ভার খোলা ----------
    private void openServer() {
        String base = prefs.getString(KEY_SERVER, "");
        if (base.isEmpty()) {
            askServer(false);
            return;
        }
        try {
            if (pendingShareText != null) {
                String text = pendingShareText;
                pendingShareText = null;
                web.loadUrl(base + "/share?text=" + URLEncoder.encode(text, "UTF-8"));
            } else {
                web.loadUrl(base + "/");
            }
        } catch (Exception e) {
            web.loadUrl(base + "/");
        }
    }

    private void askServer(boolean cancelable) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.dialog_hint);
        input.setText(prefs.getString(KEY_SERVER, ""));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title)
                .setView(input)
                .setCancelable(cancelable)
                .setPositiveButton(R.string.dialog_save, (d, w) -> {
                    String url = normalize(input.getText().toString());
                    if (url.isEmpty()) {
                        askServer(cancelable);
                        return;
                    }
                    prefs.edit().putString(KEY_SERVER, url).apply();
                    openServer();
                });
        if (cancelable) b.setNegativeButton(R.string.dialog_cancel, null);
        b.show();
    }

    /** "pc.tail1234.ts.net" → "https://pc.tail1234.ts.net", "192.168.0.5:5000" → "http://192.168.0.5:5000" */
    private String normalize(String in) {
        String s = in.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("(?i)^https?://.*")) {
            boolean local = s.matches("^[\\d.]+(:\\d+)?(/.*)?$") || s.contains(":");
            s = (local ? "http://" : "https://") + s;
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ---------- ডাউনলোড ----------
    private void startDownload(String url, String contentDisposition, String mimeType) {
        try {
            String name = fileNameFrom(url, contentDisposition, mimeType);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(name);
            r.setMimeType(mimeType != null && !mimeType.isEmpty() && !mimeType.contains("octet-stream")
                    ? mimeType : "video/mp4");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // Movies ফোল্ডারে রাখলে গ্যালারিতেও দেখা যায়
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "VideoDownloader/" + name);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show();
        }
    }

    private String fileNameFrom(String url, String cd, String mime) {
        String name = null;
        if (cd != null) {
            Matcher m = FILENAME_STAR.matcher(cd);
            if (m.find()) {
                try {
                    name = URLDecoder.decode(m.group(1).trim().replace("+", "%2B"), "UTF-8");
                } catch (Exception ignored) {
                }
            }
            if (name == null) {
                m = FILENAME_PLAIN.matcher(cd);
                if (m.find()) name = m.group(1).trim();
            }
        }
        if (name == null || name.isEmpty()) name = URLUtil.guessFileName(url, cd, mime);
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) name = "video.mp4";
        if (!name.contains(".")) name += ".mp4";
        return name;
    }

    // ---------- মেনু ও ব্যাক ----------
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.menu_reload);
        menu.add(0, 2, 1, R.string.menu_server);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            openServer();
            return true;
        }
        if (item.getItemId() == 2) {
            askServer(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
