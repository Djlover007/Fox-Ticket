package de.fox.ticket;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lädt eine Seite in einem unsichtbaren Browser (WebView), wie Kiwi/Chrome.
 * Eventim lässt einfache Abrufe nicht durch, einen echten Browser aber schon.
 * Darf NICHT auf dem Haupt-Thread aufgerufen werden.
 */
public class WebLoader {

    public static class Result {
        public String html = "";
        public String text = "";
        public String error = null;
        public String finalUrl = "";
        public String action = "";
        public String cart = "";
        public boolean acted = false;
    }

    /** Entscheidet nach dem Laden, ob (und welches) Skript auf der Seite laufen soll. null = keins. */
    public interface Decider {
        String script(String text, String html);
    }

    static final String UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    /** Fertig, sobald der Text eines dieser Wörter enthält (bzw. nach Ablauf der Zeit). */
    static boolean looksReady(String text) {
        String t = text.toLowerCase(Locale.GERMANY);
        return t.contains("kategorie") || t.contains("verfügbar") || t.contains("ausverkauft")
                || t.contains("warenkorb") || t.contains("keine veranstaltungen") || t.contains("termine");
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static Result load(Context ctx, String url, long timeoutMs) {
        return load(ctx, url, timeoutMs, null);
    }

    /** Lädt die Seite, führt (falls angegeben) ein Skript aus und wartet danach 10 s auf das Ergebnis. */
    public static Result load(Context ctx, String url, long timeoutMs, final String actionScript) {
        return load(ctx, url, timeoutMs, actionScript == null ? null : (Decider) (t, h) -> actionScript);
    }

    public static Result load(Context ctx, String url, long timeoutMs, final Decider decider) {
        final Result res = new Result();
        final CountDownLatch done = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];
        final long start = System.currentTimeMillis();

        main.post(() -> {
            try {
                WebView wv = new WebView(ctx.getApplicationContext());
                holder[0] = wv;
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setLoadsImagesAutomatically(false);
                s.setBlockNetworkImage(true);
                s.setUserAgentString(UA);
                CookieManager.getInstance().setAcceptCookie(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
                wv.layout(0, 0, 1080, 2400);

                final boolean[] polling = new boolean[1];
                final boolean[] acted = new boolean[1];
                final Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    if (done.getCount() == 0) return;
                    wv.evaluateJavascript(
                        "(function(){try{return JSON.stringify({t:document.body?document.body.innerText:'',h:document.documentElement.outerHTML,u:location.href,c:window.__foxCart||''})}catch(e){return ''}})()",
                        value -> {
                            try {
                                String json = new org.json.JSONTokener(value).nextValue().toString();
                                JSONObject o = new JSONObject(json);
                                res.text = o.optString("t");
                                res.html = o.optString("h");
                                res.finalUrl = o.optString("u");
                                res.cart = o.optString("c");
                            } catch (Exception ignored) {
                            }
                            long elapsed = System.currentTimeMillis() - start;
                            // Mind. 5 s warten, damit die Preise nachgeladen sind
                            boolean ready = (elapsed > 5000 && looksReady(res.text)) || elapsed > timeoutMs;
                            String script = null;
                            if (ready && decider != null && !acted[0]) {
                                acted[0] = true;
                                try {
                                    script = decider.script(res.text, res.html);
                                } catch (Exception ignored) {
                                }
                            }
                            if (script != null) {
                                res.acted = true;
                                wv.evaluateJavascript(script, v -> {
                                    res.action = v == null ? "" : v;
                                    main.postDelayed(poll[0], 10000);
                                });
                            } else if (ready) {
                                done.countDown();
                            } else {
                                main.postDelayed(poll[0], 1500);
                            }
                        });
                };

                wv.setWebViewClient(new WebViewClient() {
                    boolean started = false;

                    @Override
                    public void onPageFinished(WebView view, String u) {
                        if (!started && !polling[0]) {
                            started = true;
                            polling[0] = true;
                            main.postDelayed(poll[0], 2500);
                        }
                    }
                });
                wv.loadUrl(url);
                // Falls onPageFinished nie kommt: trotzdem nachsehen
                main.postDelayed(() -> {
                    if (!polling[0]) {
                        polling[0] = true;
                        poll[0].run();
                    }
                }, 12000);
            } catch (Exception e) {
                res.error = "Browser-Fehler: " + e.getClass().getSimpleName();
                done.countDown();
            }
        });

        try {
            if (!done.await(timeoutMs + (decider != null ? 25000 : 10000), TimeUnit.MILLISECONDS) && res.text.isEmpty()) {
                res.error = "Zeitüberschreitung";
            }
        } catch (InterruptedException e) {
            res.error = "Abgebrochen";
        }
        main.post(() -> {
            if (holder[0] != null) {
                try {
                    holder[0].stopLoading();
                    holder[0].destroy();
                } catch (Exception ignored) {
                }
            }
            CookieManager.getInstance().flush();
        });
        if (res.error == null && res.text.trim().isEmpty()) res.error = "Seite blieb leer";
        return res;
    }
}
