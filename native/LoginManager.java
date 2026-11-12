package de.fox.ticket;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Prüft, ob man bei Eventim angemeldet ist, und meldet sich bei Bedarf automatisch an.
 * Zugangsdaten liegen verschlüsselt (Android Keystore) nur auf dem Gerät.
 */
public class LoginManager {

    static final String ACCOUNT_URL = "https://www.eventim.de/myAccount/";
    static final long CHECK_EVERY_MS = 60 * 60 * 1000; // stündlich

    static final String LOGIN_JS =
        "(function (email, pw) {\n" +
        "  function setVal(el, v) {\n" +
        "    var proto = Object.getPrototypeOf(el);\n" +
        "    var d = Object.getOwnPropertyDescriptor(proto, 'value') || Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');\n" +
        "    d.set.call(el, v);\n" +
        "    el.dispatchEvent(new Event('input', { bubbles: true }));\n" +
        "    el.dispatchEvent(new Event('change', { bubbles: true }));\n" +
        "    el.dispatchEvent(new Event('blur', { bubbles: true }));\n" +
        "  }\n" +
        "  var q = function (s) { return [].slice.call(document.querySelectorAll(s)).filter(function (e) { return !e.disabled && e.type !== 'hidden'; }); };\n" +
        "  [].slice.call(document.querySelectorAll('button')).forEach(function (b) { if (/alle akzeptieren|akzeptieren und schließen/i.test(b.innerText || '')) b.click(); });\n" +
        "  var pwd = q('input[type=password]')[0];\n" +
        "  var mail = q('input[type=email],input[autocomplete=username],input[autocomplete=email],input[name*=mail i],input[id*=mail i],input[name=username],input[id=username]')[0];\n" +
        "  var href = location.href.toLowerCase();\n" +
        "  var txt = (document.body ? document.body.innerText : '').toLowerCase();\n" +
        "  if (!pwd && !mail) {\n" +
        "    if (/abmelden|ausloggen|logout|meine bestellungen|persönliche daten|meine daten/.test(txt)) return 'in';\n" +
        "    var lb = q('button,a').filter(function (b) { return /^(anmelden|einloggen|login)$/i.test((b.innerText || '').trim()); })[0];\n" +
        "    if (lb && !window.__foxOpened) { window.__foxOpened = 1; lb.click(); return 'opening'; }\n" +
        "    return 'unknown';\n" +
        "  }\n" +
        "  if (!email || !pw) return 'out';\n" +
        "  if (window.__foxLoginTries > 2) return 'failed';\n" +
        "  window.__foxLoginTries = (window.__foxLoginTries || 0) + 1;\n" +
        "  if (mail && !mail.value) setVal(mail, email);\n" +
        "  if (pwd) setVal(pwd, pw);\n" +
        "  var form = (pwd || mail).form;\n" +
        "  var btn = (form ? [].slice.call(form.querySelectorAll('button,input[type=submit]')) : q('button,input[type=submit]')).filter(function (b) {\n" +
        "    return b.type === 'submit' || /anmelden|einloggen|login|weiter|fortfahren/i.test(b.innerText || b.value || '');\n" +
        "  })[0];\n" +
        "  setTimeout(function () { if (btn) btn.click(); else if (form) form.requestSubmit ? form.requestSubmit() : form.submit(); }, 600);\n" +
        "  return 'submitted';\n" +
        "})(__EMAIL__, __PW__)";

    static SharedPreferences secure(Context c) throws Exception {
        MasterKey key = new MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        return EncryptedSharedPreferences.create(c, "fox_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    public static void setCredentials(Context c, String email, String pw) throws Exception {
        secure(c).edit().putString("email", email).putString("pw", pw).apply();
    }

    public static void clearCredentials(Context c) throws Exception {
        secure(c).edit().clear().apply();
    }

    public static String email(Context c) {
        try {
            return secure(c).getString("email", "");
        } catch (Exception e) {
            return "";
        }
    }

    static String password(Context c) {
        try {
            return secure(c).getString("pw", "");
        } catch (Exception e) {
            return "";
        }
    }

    public static JSONObject status(Context c) {
        try {
            String s = TicketChecker.prefs(c).getString("login", null);
            JSONObject o = s == null ? new JSONObject() : new JSONObject(s);
            o.put("email", email(c));
            o.put("hasPassword", !password(c).isEmpty());
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Nur prüfen, wenn die letzte Prüfung länger als eine Stunde her ist (oder force). */
    public static synchronized JSONObject ensure(Context c, boolean force) {
        JSONObject last = status(c);
        if (!force && System.currentTimeMillis() - last.optLong("time", 0) < CHECK_EVERY_MS) return last;
        String before = last.optString("state", "");
        String state = run(c, email(c), password(c));
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("time", System.currentTimeMillis());
            o.put("text", describe(state));
            TicketChecker.prefs(c).edit().putString("login", o.toString()).apply();
            o.put("email", email(c));
            o.put("hasPassword", !password(c).isEmpty());
        } catch (Exception ignored) {
        }
        if (!"in".equals(state) && !"unknown".equals(state) && !state.equals(before)) {
            TicketChecker.history(c, "🔑 Eventim-Anmeldung: " + describe(state));
            TicketChecker.showNotification(c, 4000, "⚠️ Fox Ticket: Eventim-Anmeldung",
                    describe(state) + "\nTippen zum Anmelden – sonst klappt der Auto-Warenkorb nicht.",
                    false, "shop:" + ACCOUNT_URL);
        }
        return o;
    }

    static String describe(String state) {
        String t = new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date());
        switch (state) {
            case "in": return "Angemeldet ✅ (geprüft " + t + " Uhr)";
            case "relogged": return "War abgemeldet – automatisch neu angemeldet ✅ (" + t + " Uhr)";
            case "out": return "Nicht angemeldet ❌";
            case "failed": return "Automatische Anmeldung hat nicht geklappt ❌ (evtl. Passwort falsch oder Sicherheitsabfrage)";
            default: return "Anmeldestatus unklar (" + state + ")";
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    static String run(Context ctx, String email, String pw) {
        final String[] state = { "unknown" };
        final boolean[] submitted = { false };
        final CountDownLatch done = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];
        final long start = System.currentTimeMillis();
        final String js = LOGIN_JS.replace("__EMAIL__", JSONObject.quote(email == null ? "" : email))
                .replace("__PW__", JSONObject.quote(pw == null ? "" : pw));

        main.post(() -> {
            try {
                WebView wv = new WebView(ctx.getApplicationContext());
                holder[0] = wv;
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setBlockNetworkImage(true);
                s.setUserAgentString(WebLoader.UA);
                CookieManager.getInstance().setAcceptCookie(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
                wv.layout(0, 0, 1080, 2400);
                wv.setWebViewClient(new WebViewClient());
                final Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    if (done.getCount() == 0) return;
                    wv.evaluateJavascript(js, v -> {
                        String r = v == null ? "" : v.replace("\"", "");
                        long el = System.currentTimeMillis() - start;
                        if ("submitted".equals(r)) submitted[0] = true;
                        if ("in".equals(r)) {
                            state[0] = submitted[0] ? "relogged" : "in";
                            done.countDown();
                        } else if ("out".equals(r) || "failed".equals(r)) {
                            state[0] = r;
                            done.countDown();
                        } else if (el > 50000) {
                            state[0] = submitted[0] ? "failed" : ("unknown".equals(r) || r.isEmpty() ? "unknown" : r);
                            done.countDown();
                        } else {
                            main.postDelayed(poll[0], "submitted".equals(r) ? 6000 : 2500);
                        }
                    });
                };
                wv.loadUrl(ACCOUNT_URL);
                main.postDelayed(poll[0], 6000);
            } catch (Exception e) {
                state[0] = "error";
                done.countDown();
            }
        });
        try {
            done.await(70, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
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
        return state[0];
    }
}
