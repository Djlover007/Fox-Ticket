package de.fox.ticket;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Eventim im App-Browser. Teilt die Anmeldung (Cookies) mit der Hintergrundprüfung,
 * damit automatisch in den Warenkorb gelegte Tickets hier zum Bezahlen bereitliegen.
 */
public class ShopActivity extends Activity {

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Color.parseColor("#7b2fd1"));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(24, 16, 24, 16);
        Button close = new Button(this);
        close.setText("✕");
        close.setOnClickListener(v -> finish());
        final TextView title = new TextView(this);
        title.setText("🦊 Fox Ticket · Eventim");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setPadding(24, 0, 0, 0);
        bar.addView(close);
        bar.addView(title);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(WebLoader.UA);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        final String cartJs = getIntent().getStringExtra("cartJs");
        web.setWebViewClient(new WebViewClient() {
            boolean done = false;

            @Override
            public void onPageFinished(WebView view, String u) {
                if (cartJs == null || done) return;
                done = true;
                title.setText("🧪 Warenkorb-Test läuft …");
                view.postDelayed(() -> view.evaluateJavascript(cartJs, null), 5000);
                view.postDelayed(() -> view.evaluateJavascript("window.__foxCart||''", v -> {
                    String res = v == null ? "" : v.replace("\"", "");
                    String log = "Test " + new java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(new java.util.Date()) + ": " + res;
                    TicketChecker.prefs(ShopActivity.this).edit().putString("cartLog", log).apply();
                    title.setText("🧪 " + (res.isEmpty() ? "kein Ergebnis" : res));
                    android.widget.Toast.makeText(ShopActivity.this, res, android.widget.Toast.LENGTH_LONG).show();
                }), 14000);
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String url = getIntent().getStringExtra("url");
        web.loadUrl(url != null ? url : "https://www.eventim.de/");
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null) web.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
