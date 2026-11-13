package com.greenbackterminal.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Greenback Terminal for Android.
 *
 * The app's screens (Markets, Chart, Ideas, Community, Me) are served by greenbackterminal.com/m, so every website
 * update reaches the app straight away. This activity gives them a real app around them: a splash screen, the phone's
 * back button, haptics, sharing, file uploads, saving PDFs, full-screen video, an offline screen and Google / Facebook
 * sign-in through the browser. The website talks to it through window.GBNative.
 */
public class MainActivity extends Activity {

    private static final String SITE = "greenbackterminal.com";
    private static final String BASE = "https://" + SITE;
    private static final int REQ_FILE = 41;
    private static final int BG_DARK = 0xFF0B0E14;

    private FrameLayout root;
    private WebView web;
    private View loader;
    private ProgressBar bar;
    private LinearLayout offline;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean firstLoadDone = false;
    private long lastBack = 0;
    private int themeBg = BG_DARK;
    private boolean themeDark = true;

    /* ------------------------------------------------------------------ lifecycle */

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(BG_DARK);
        w.setNavigationBarColor(BG_DARK);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG_DARK);

        web = new WebView(this);
        web.setBackgroundColor(BG_DARK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(0xFF22C55E));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        bar.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        bp.gravity = Gravity.TOP;
        bp.topMargin = -dp(1);
        root.addView(bar, bp);

        loader = buildLoader();
        root.addView(loader, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        offline = buildOffline();
        offline.setVisibility(View.GONE);
        root.addView(offline, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setOffscreenPreRaster(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUserAgentString(s.getUserAgentString() + " GreenbackApp/android/" + BuildConfig.VERSION_NAME);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "GBNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(new Downloads());

        if (state != null) web.restoreState(state);
        if (state == null || web.getUrl() == null) {
            String deep = deepLink(getIntent());
            web.loadUrl(deep != null ? deep : BuildConfig.START_URL);
        }
        // never leave the splash up for long, even on a very slow connection
        ui.postDelayed(this::hideLoader, 9000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String deep = deepLink(intent);
        if (deep != null) web.loadUrl(deep);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
        js("window.GBApp&&GBApp.onResume&&GBApp.onResume()");
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        js("window.GBApp&&GBApp.onPause&&GBApp.onPause()");
        web.onPause();
        web.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    /** Back button: close full-screen video → let the app screens handle it (sheets, tabs) → previous page → exit. */
    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustomView(); return; }
        if (offline.getVisibility() == View.VISIBLE && web.canGoBack()) { offline.setVisibility(View.GONE); web.goBack(); return; }
        web.evaluateJavascript("(function(){try{return !!(window.GBApp&&GBApp.back&&GBApp.back())}catch(e){return false}})()", value -> {
            if ("true".equals(value)) return;
            if (web.canGoBack()) { web.goBack(); return; }
            long now = System.currentTimeMillis();
            if (now - lastBack < 2000) finish();
            else { lastBack = now; Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show(); }
        });
    }

    /* ------------------------------------------------------------------ links */

    /** greenbackterminal://login?t=… (after Google / Facebook sign-in in the browser) or a website link. */
    private String deepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return null;
        Uri u = intent.getData();
        if ("greenbackterminal".equals(u.getScheme())) {
            Uri.Builder b = Uri.parse(BASE + "/api/auth.php").buildUpon().appendQueryParameter("action", "app_login");
            for (String k : new String[]{"t", "r", "m"}) {
                String v = u.getQueryParameter(k);
                if (v != null) b.appendQueryParameter(k, v);
            }
            b.appendQueryParameter("back", "/m");
            return b.build().toString();
        }
        String host = u.getHost();
        if (host != null && host.endsWith(SITE)) return u.toString();
        return null;
    }

    private boolean isOurs(Uri u) {
        String h = u.getHost();
        return h != null && (h.equals(SITE) || h.equals("www." + SITE));
    }

    /** true = handled outside the app's web view */
    private boolean route(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (scheme.equals("greenbackterminal")) {
            String d = deepLink(new Intent(Intent.ACTION_VIEW, u));
            if (d != null) web.loadUrl(d);
            return true;
        }
        if (scheme.equals("http") || scheme.equals("https")) {
            if (isOurs(u)) {
                String p = u.getPath() == null ? "" : u.getPath();
                // Google and Facebook only allow signing in inside a real browser; it hands the session back afterwards
                if (p.startsWith("/api/oauth.php") && u.getQueryParameter("go") != null) { openExternal(u.toString()); return true; }
                // app downloads (an update of this app) go through the browser / download manager
                if (p.startsWith("/api/download.php") || p.endsWith(".apk")) { openExternal(u.toString()); return true; }
                return false;
            }
            openExternal(u.toString());
            return true;
        }
        if (scheme.equals("intent")) {
            try {
                Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                startActivity(i);
            } catch (Exception e) {
                try {
                    Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb != null) openExternal(fb);
                } catch (Exception ignored) { }
            }
            return true;
        }
        // mailto:, tel:, whatsapp:, market: …
        openExternal(u.toString());
        return true;
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    /* ------------------------------------------------------------------ web view clients */

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            if (!req.isForMainFrame()) return false;
            return route(req.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            offline.setVisibility(View.GONE);
            if (firstLoadDone) { bar.setProgress(5); bar.setVisibility(View.VISIBLE); }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            bar.setVisibility(View.GONE);
            CookieManager.getInstance().flush();
            // the app screens call GBNative.ready() when drawn; other pages are ready when loaded
            ui.postDelayed(MainActivity.this::hideLoader, url != null && url.contains("/m") ? 1200 : 150);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) showOffline();
        }

        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
            handler.cancel(); // never load a page with a broken certificate
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int p) {
            if (bar.getVisibility() == View.VISIBLE) bar.setProgress(Math.max(5, p));
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                Intent i = params.createIntent();
                i.addCategory(Intent.CATEGORY_OPENABLE);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(i, "Choose a picture"), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                cb.onReceiveValue(null);
                return false;
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view;
            customCallback = callback;
            customView.setBackgroundColor(Color.BLACK);
            root.addView(customView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            immersive(true);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        }

        @Override
        public void onHideCustomView() { hideCustomView(); }

        @Override
        public android.graphics.Bitmap getDefaultVideoPoster() {
            return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        immersive(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = getWindow().getDecorView();
        if (on) d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        else { d.setSystemUiVisibility(0); applyBars(); }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_FILE) {
            if (fileCallback != null) fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, result, data);
    }

    /** Ordinary downloads (files linked on the website). PDFs the app makes itself come through GBNative.saveFile. */
    private class Downloads implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String ua, String disposition, String mime, long length) {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
            try {
                String name = URLUtil.guessFileName(url, disposition, mime);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && Uri.parse(url).getHost() != null && Uri.parse(url).getHost().endsWith(SITE)) r.addRequestHeader("Cookie", cookies);
                r.addRequestHeader("User-Agent", ua);
                r.setTitle(name);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(r);
                Toast.makeText(MainActivity.this, "Downloading " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                openExternal(url);
            }
        }
    }

    /* ------------------------------------------------------------------ bridge for the website: window.GBNative */

    private class Bridge {
        @JavascriptInterface
        public String version() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface
        public String platform() { return "android"; }

        @JavascriptInterface
        public int sdk() { return Build.VERSION.SDK_INT; }

        /** The app screens have drawn: take the splash away. */
        @JavascriptInterface
        public void ready() { ui.post(MainActivity.this::hideLoader); }

        @JavascriptInterface
        public void haptic(final String kind) {
            ui.post(() -> {
                int c;
                switch (kind == null ? "" : kind) {
                    case "long": c = HapticFeedbackConstants.LONG_PRESS; break;
                    case "success": c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY; break;
                    case "error": c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS; break;
                    case "tick": c = HapticFeedbackConstants.CLOCK_TICK; break;
                    default: c = HapticFeedbackConstants.VIRTUAL_KEY;
                }
                web.performHapticFeedback(c, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
            });
        }

        @JavascriptInterface
        public void share(final String title, final String text, final String url) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                String body = (text == null ? "" : text) + (url == null || url.isEmpty() ? "" : ((text == null || text.isEmpty()) ? "" : "\n") + url);
                if (title != null) i.putExtra(Intent.EXTRA_SUBJECT, title);
                i.putExtra(Intent.EXTRA_TEXT, body);
                try { startActivity(Intent.createChooser(i, title == null ? "Share" : title)); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void openExternal(final String url) { ui.post(() -> MainActivity.this.openExternal(url)); }

        @JavascriptInterface
        public void toast(final String msg) { ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show()); }

        /** Match the status bar and navigation bar to the app's light / dark theme. */
        @JavascriptInterface
        public void setTheme(final String bg, final boolean dark) {
            ui.post(() -> {
                try { themeBg = Color.parseColor(bg); } catch (Exception e) { themeBg = dark ? BG_DARK : Color.WHITE; }
                themeDark = dark;
                root.setBackgroundColor(themeBg);
                web.setBackgroundColor(themeBg);
                applyBars();
            });
        }

        /** Save a file the page made (e.g. a PDF statement) into Downloads and open it. Returns where it went. */
        @JavascriptInterface
        public String saveFile(String base64, String name, String mime) {
            try {
                String clean = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
                byte[] data = Base64.decode(clean, Base64.DEFAULT);
                String file = name == null || name.isEmpty() ? "greenback-file" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
                String type = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, type);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    ContentResolver cr = getContentResolver();
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "error";
                    try (OutputStream os = cr.openOutputStream(uri)) { if (os == null) return "error"; os.write(data); }
                    final Uri open = uri;
                    ui.post(() -> {
                        Toast.makeText(MainActivity.this, "Saved to Downloads: " + file, Toast.LENGTH_LONG).show();
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(open, type);
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception ignored) { }
                    });
                    return "downloads";
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    File f = new File(dir, file);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
                    final String where = f.getAbsolutePath();
                    ui.post(() -> Toast.makeText(MainActivity.this, "Saved: " + where, Toast.LENGTH_LONG).show());
                    return where;
                }
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(MainActivity.this, "Could not save the file", Toast.LENGTH_SHORT).show());
                return "error";
            }
        }

        @JavascriptInterface
        public boolean online() { return isOnline(); }

        @JavascriptInterface
        public void clearCache() { ui.post(() -> { web.clearCache(true); web.reload(); }); }

        @JavascriptInterface
        public void exitApp() { ui.post(MainActivity.this::finish); }
    }

    /* ------------------------------------------------------------------ small helpers */

    @SuppressWarnings("deprecation")
    private void applyBars() {
        Window w = getWindow();
        w.setStatusBarColor(themeBg);
        w.setNavigationBarColor(themeBg);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(themeDark ? 0 : light, light);
            }
        } else {
            int f = 0;
            if (!themeDark) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    private void hideLoader() {
        if (loader == null || loader.getVisibility() != View.VISIBLE) { firstLoadDone = true; return; }
        firstLoadDone = true;
        loader.animate().alpha(0f).setDuration(220).withEndAction(() -> loader.setVisibility(View.GONE)).start();
    }

    private void showOffline() {
        hideLoader();
        bar.setVisibility(View.GONE);
        offline.setVisibility(View.VISIBLE);
    }

    @SuppressWarnings("deprecation")
    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) { return true; }
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** Splash shown while the first screen loads: the logo and a small spinner. */
    private View buildLoader() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(BG_DARK);
        f.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        col.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView name = new TextView(this);
        name.setText("Greenback Terminal");
        name.setTextColor(0xFFE6E9EF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(16);
        col.addView(name, np);
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminateTintList(ColorStateList.valueOf(0xFF22C55E));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(26), dp(26));
        sp.topMargin = dp(26);
        col.addView(spin, sp);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER;
        f.addView(col, cp);
        return f;
    }

    /** "You're offline" screen with a retry button (native, so it works without any connection). */
    private LinearLayout buildOffline() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(BG_DARK);
        col.setPadding(dp(32), dp(32), dp(32), dp(32));
        col.setClickable(true);
        TextView icon = new TextView(this);
        icon.setText("📡");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42);
        icon.setGravity(Gravity.CENTER);
        col.addView(icon);
        TextView t = new TextView(this);
        t.setText("You're offline");
        t.setTextColor(0xFFE6E9EF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, dp(6));
        col.addView(t);
        TextView m = new TextView(this);
        m.setText("Check your Wi-Fi or mobile data, then try again. Live prices come back as soon as you're connected.");
        m.setTextColor(0xFF8A93A6);
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        m.setGravity(Gravity.CENTER);
        m.setLineSpacing(0, 1.25f);
        col.addView(m);
        Button b = new Button(this);
        b.setText("Try again");
        b.setAllCaps(false);
        b.setTextColor(0xFF04110A);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF22C55E);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(180), dp(48));
        lp.topMargin = dp(24);
        col.addView(b, lp);
        b.setOnClickListener(v -> {
            offline.setVisibility(View.GONE);
            bar.setProgress(5);
            bar.setVisibility(View.VISIBLE);
            String u = web.getUrl();
            if (u == null || u.startsWith("about:") || u.startsWith("data:")) web.loadUrl(BuildConfig.START_URL);
            else web.reload();
        });
        return col;
    }
}
