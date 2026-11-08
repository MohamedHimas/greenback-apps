package com.greenbackterminal.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Greenback Terminal for Android.
 * The app shows the live website inside its own window, so every feature added to the website
 * appears in the app automatically. The website switches to a phone layout (bottom tabs) when it
 * sees "GreenbackApp/android" in the user agent.
 */
public class MainActivity extends Activity {

    static final String HOST = "greenbackterminal.com";
    static final String HOME = "https://" + HOST + "/";
    static final int FILE_PICK = 41;

    WebView web;
    ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#0b0e14"));
        w.setNavigationBarColor(Color.parseColor("#0b0e14"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b0e14"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " GreenbackApp/android/" + BuildConfig.VERSION_NAME);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return route(req.getUrl());
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) v.loadUrl("file:///android_asset/offline.html");
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), FILE_PICK);
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> openExternal(Uri.parse(url)));

        if (saved != null) web.restoreState(saved);
        else if (!handleDeepLink(getIntent())) web.loadUrl(HOME);
    }

    /** true = handled outside the app window. */
    boolean route(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if (scheme.equals("greenbackterminal")) { handleDeepLink(new Intent(Intent.ACTION_VIEW, u)); return true; }
        if (scheme.equals("file") && "/android_asset/offline.html".equals(u.getPath())) return false;
        if (!scheme.equals("https") && !scheme.equals("http")) { openExternal(u); return true; }
        String h = u.getHost() == null ? "" : u.getHost();
        boolean ours = h.equals(HOST) || h.endsWith("." + HOST);
        if (!ours) { openExternal(u); return true; }
        // Google & Facebook don't allow sign-in inside apps -> start it in the real browser.
        String path = u.getPath() == null ? "" : u.getPath();
        if (path.startsWith("/api/oauth.php") && u.getQueryParameter("go") != null) { openExternal(u); return true; }
        return false;
    }

    /** greenbackterminal://login?t=CODE&r=ok&m=... -> sign the app in. */
    boolean handleDeepLink(Intent i) {
        if (i == null || i.getData() == null) return false;
        Uri d = i.getData();
        if (!"greenbackterminal".equals(d.getScheme())) return false;
        Uri.Builder b = Uri.parse(HOME + "api/auth.php").buildUpon().appendQueryParameter("action", "app_login");
        for (String k : new String[]{"t", "r", "m"}) {
            String val = d.getQueryParameter(k);
            if (val != null) b.appendQueryParameter(k, val);
        }
        web.loadUrl(b.build().toString());
        return true;
    }

    void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException ignored) { }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleDeepLink(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_PICK && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }
}
