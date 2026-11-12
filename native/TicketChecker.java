package de.fox.ticket;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prüft Eventim-Künstlerseiten auf neue Termine. Wird von der App und vom Hintergrund-Worker benutzt. */
public class TicketChecker {

    static final String PREFS = "fox_ticket";
    static final String CHANNEL = "tickets";
    static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    static final int TIMEOUT_MS = 20000;
    static final int MAX_BYTES = 6 * 1024 * 1024;

    static final Pattern EVENT_LINK = Pattern.compile("(?:https?:)?(?:\\\\?/\\\\?/www\\.eventim\\.de)?\\\\?/event\\\\?/([a-z0-9-]+-\\d{5,})", Pattern.CASE_INSENSITIVE);
    static final Pattern LD_JSON = Pattern.compile("<script[^>]*application/ld\\+json[^>]*>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);
    static final String CART_JS =
        "(function (qty, cats) {\n" +
        "  function t(e) { return ((e.innerText || e.textContent || \"\") + \" \" + (e.getAttribute(\"aria-label\") || \"\") + \" \" + (e.getAttribute(\"title\") || \"\")).trim().toLowerCase(); }\n" +
        "  function clickable() { return [].slice.call(document.querySelectorAll(\"button,[role=button],a,input[type=submit]\")).filter(function (b) { return !b.disabled && b.getAttribute(\"aria-disabled\") !== \"true\" && b.offsetParent !== null; }); }\n" +
        "  var log = [];\n" +
        "  // Cookie-Banner wegklicken\n" +
        "  clickable().forEach(function (b) { if (/alle akzeptieren|akzeptieren und schließen|alle cookies/.test(t(b))) { b.click(); log.push(\"cookies\"); } });\n" +
        "  function isPlus(b) { var s = t(b); return s === \"+\" || /(erhöhen|mehr tickets|increase|anzahl erhöhen|plus)/.test(s); }\n" +
        "  // Menge erhöhen: \"+\"-Knopf der ersten verfügbaren (gewählten) Kategorie\n" +
        "  var plus = [];\n" +
        "  if (cats.length) {\n" +
        "    var all = [].slice.call(document.querySelectorAll(\"body *\"));\n" +
        "    cats.forEach(function (name) {\n" +
        "      if (plus.length) return;\n" +
        "      var n = name.toLowerCase();\n" +
        "      var label = all.filter(function (e) { return e.children.length < 4 && (e.innerText || \"\").trim().toLowerCase().indexOf(n) === 0; }).pop();\n" +
        "      for (var el = label, d = 0; el && d < 10 && !plus.length; el = el.parentElement, d++) {\n" +
        "        if (((el.innerText || \"\").toLowerCase().match(/kategorie/g) || []).length > 1) break; // nicht in andere Kategorien rutschen\n" +
        "        plus = [].slice.call(el.querySelectorAll(\"button,[role=button]\")).filter(function (b) { return !b.disabled && b.getAttribute(\"aria-disabled\") !== \"true\" && isPlus(b); });\n" +
        "      }\n" +
        "      if (plus.length) log.push(\"Kategorie: \" + name);\n" +
        "    });\n" +
        "    if (!plus.length) log.push(\"gewählte Kategorie nicht kaufbar\");\n" +
        "  } else {\n" +
        "    plus = clickable().filter(isPlus);\n" +
        "  }\n" +
        "  if (cats.length && !plus.length) {\n" +
        "    window.__foxCart = \"abgebrochen\";\n" +
        "    return log.join(\", \");\n" +
        "  }\n" +
        "  if (plus.length) {\n" +
        "    for (var i = 0; i < qty; i++) plus[0].click();\n" +
        "    log.push(\"plus x\" + qty);\n" +
        "  } else {\n" +
        "    var sel = [].slice.call(document.querySelectorAll(\"select\")).filter(function (s) { return s.offsetParent !== null; })[0];\n" +
        "    if (sel) {\n" +
        "      var opt = [].slice.call(sel.options).filter(function (o) { return parseInt(o.value || o.text, 10) === qty; })[0] || sel.options[Math.min(qty, sel.options.length - 1)];\n" +
        "      sel.value = opt.value;\n" +
        "      sel.dispatchEvent(new Event(\"input\", { bubbles: true }));\n" +
        "      sel.dispatchEvent(new Event(\"change\", { bubbles: true }));\n" +
        "      log.push(\"select \" + opt.value);\n" +
        "    } else {\n" +
        "      log.push(\"keine Mengenauswahl\");\n" +
        "    }\n" +
        "  }\n" +
        "  // Danach \"In den Warenkorb\" klicken\n" +
        "  setTimeout(function () {\n" +
        "    var btn = clickable().filter(function (b) { return /in den warenkorb|zum warenkorb hinzufügen|warenkorb legen/.test(t(b)); })[0]\n" +
        "      || clickable().filter(function (b) { return /tickets sichern|jetzt buchen|weiter zur buchung/.test(t(b)); })[0];\n" +
        "    if (btn) { btn.click(); window.__foxCart = \"geklickt: \" + t(btn).slice(0, 40); }\n" +
        "    else { window.__foxCart = \"kein Warenkorb-Knopf\"; }\n" +
        "  }, 2000);\n" +
        "  return log.join(\", \");\n" +
        "})(__QTY__, __CATS__)\n" +
        "";

    static final String CHANNEL_ALARM = "tickets_alarm";

    static final Pattern ARTIST = Pattern.compile("/artist/([a-z0-9-]+)", Pattern.CASE_INSENSITIVE);

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static JSONArray getWatch(Context c) {
        try {
            String s = prefs(c).getString("watch", null);
            if (s != null) return new JSONArray(s);
            JSONArray def = new JSONArray();
            JSONObject fob = new JSONObject();
            fob.put("name", "Fall Out Boy");
            fob.put("url", "https://www.eventim.de/artist/fall-out-boy/");
            def.put(fob);
            return def;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void setWatch(Context c, JSONArray watch) {
        prefs(c).edit().putString("watch", watch.toString()).apply();
    }

    public static JSONObject getLast(Context c) {
        try {
            String s = prefs(c).getString("last", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static String slugOf(String url) {
        Matcher m = ARTIST.matcher(url == null ? "" : url);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    static String titleFromId(String id) {
        String s = id.replaceAll("-\\d+$", "");
        StringBuilder b = new StringBuilder();
        for (String w : s.split("-")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    /** Ergebnis eines Abrufs: HTTP-Status (oder -1) und Text. */
    static class Page {
        int status = -1;
        String text = "";
        String error = null;
    }

    static Page fetch(String url) {
        Page p = new Page();
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            con.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.5");
            p.status = con.getResponseCode();
            InputStream in = p.status >= 400 ? con.getErrorStream() : con.getInputStream();
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                long start = System.currentTimeMillis();
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES || System.currentTimeMillis() - start > 45000) break;
                }
                in.close();
                p.text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (SocketTimeoutException e) {
            p.error = "Zeitüberschreitung";
        } catch (Exception e) {
            p.error = "Netzwerkfehler: " + e.getClass().getSimpleName();
        } finally {
            if (con != null) con.disconnect();
        }
        return p;
    }

    static boolean looksBlocked(Page p) {
        if (p.status == 403 || p.status == 429 || p.status == 503) return true;
        String h = p.text.length() > 20000 ? p.text.substring(0, 20000) : p.text;
        h = h.toLowerCase(Locale.ROOT);
        return h.contains("captcha") || h.contains("access denied") || h.contains("pardon our interruption") || h.contains("request unsuccessful");
    }

    static JSONArray extract(String text, String slug) {
        Map<String, JSONObject> found = new LinkedHashMap<>();
        try {
            Matcher m = EVENT_LINK.matcher(text);
            while (m.find()) {
                String id = m.group(1).toLowerCase(Locale.ROOT);
                if (!slug.isEmpty() && !id.contains(slug)) continue;
                String link = "https://www.eventim.de/event/" + id + "/";
                if (found.containsKey(link)) continue;
                JSONObject ev = new JSONObject();
                ev.put("link", link);
                ev.put("title", titleFromId(id));
                found.put(link, ev);
            }
            Matcher ld = LD_JSON.matcher(text);
            while (ld.find()) {
                List<JSONObject> events = new ArrayList<>();
                try {
                    String raw = ld.group(1).trim();
                    Object root = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
                    walk(root, events);
                } catch (Exception ignored) {
                    continue;
                }
                for (JSONObject e : events) {
                    Matcher mm = EVENT_LINK.matcher(e.optString("url"));
                    if (!mm.find()) continue;
                    String id = mm.group(1).toLowerCase(Locale.ROOT);
                    if (!slug.isEmpty() && !id.contains(slug)) continue;
                    String link = "https://www.eventim.de/event/" + id + "/";
                    JSONObject ev = new JSONObject();
                    ev.put("link", link);
                    ev.put("title", e.optString("name", titleFromId(id)));
                    ev.put("date", e.optString("startDate", ""));
                    JSONObject loc = e.optJSONObject("location");
                    String place = "";
                    if (loc != null) {
                        place = loc.optString("name", "");
                        JSONObject addr = loc.optJSONObject("address");
                        String city = addr != null ? addr.optString("addressLocality", "") : "";
                        if (!city.isEmpty()) place = place.isEmpty() ? city : place + ", " + city;
                    }
                    ev.put("place", place);
                    Object offers = e.opt("offers");
                    JSONObject offer = offers instanceof JSONArray ? ((JSONArray) offers).optJSONObject(0) : (offers instanceof JSONObject ? (JSONObject) offers : null);
                    String av = offer != null ? offer.optString("availability", "") : "";
                    ev.put("availability", av.replaceAll("^.*/", ""));
                    found.put(link, ev);
                }
            }
        } catch (Exception ignored) {
        }
        JSONArray out = new JSONArray();
        for (JSONObject o : found.values()) out.put(o);
        return out;
    }

    static void walk(Object x, List<JSONObject> out) {
        if (x instanceof JSONArray) {
            JSONArray a = (JSONArray) x;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), out);
        } else if (x instanceof JSONObject) {
            JSONObject o = (JSONObject) x;
            if (o.has("startDate") && o.has("url")) out.add(o);
            JSONArray names = o.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                Object v = o.opt(names.optString(i));
                if (v instanceof JSONObject || v instanceof JSONArray) walk(v, out);
            }
        }
    }

    static JSONObject checkArtist(JSONObject item) throws Exception {
        return checkArtist(null, item);
    }

    static Page browserFetch(Context c, String url) {
        Page p = new Page();
        WebLoader.Result w = WebLoader.load(c, url, 35000);
        p.error = w.error;
        p.status = w.error == null ? 200 : -1;
        p.text = w.html + "\n" + w.text;
        return p;
    }

    static JSONObject checkArtist(Context c, JSONObject item) throws Exception {
        String url = item.optString("url");
        String name = item.optString("name");
        String slug = slugOf(url);
        JSONObject r = new JSONObject();
        r.put("name", name);
        r.put("url", url);
        r.put("ok", false);
        JSONArray events = new JSONArray();
        String detail;

        Page page = c != null ? browserFetch(c, url.split("\\?")[0]) : fetch(url.split("\\?")[0]);
        if (page.error != null) {
            detail = "Eventim: " + page.error;
        } else if (looksBlocked(page)) {
            detail = "Eventim blockiert (HTTP " + page.status + ")";
        } else if (page.status >= 200 && page.status < 400) {
            r.put("ok", true);
            events = extract(page.text, slug);
            detail = "Seite geladen (" + (page.text.length() / 1024) + " KB)";
        } else {
            detail = "Eventim: HTTP " + page.status;
        }

        if (!r.optBoolean("ok")) {
            // Ersatzweg: Eventim-Suche
            String term = URLEncoder.encode(name.isEmpty() ? slug.replace('-', ' ') : name, "UTF-8");
            Page api = fetch("https://public-api.eventim.com/websearch/search/api/exploration/v2/productGroups?webId=web__eventim-de&language=de&search_term=" + term);
            if (api.error == null && api.status >= 200 && api.status < 300 && !looksBlocked(api)) {
                r.put("ok", true);
                events = extract(api.text, slug);
                detail += " · über Eventim-Suche geprüft";
            } else {
                detail += " · Suche: " + (api.error != null ? api.error : "HTTP " + api.status);
            }
        }
        r.put("events", events);
        r.put("detail", detail);
        r.put("message", r.optBoolean("ok") ? "" : "Eventim nicht erreichbar – nächster Versuch später");
        return r;
    }

    static boolean isEvent(String url) {
        return url != null && url.contains("/event/");
    }

    static String statusText(String av) {
        switch (av) {
            case "InStock": return "Tickets verfügbar";
            case "LimitedAvailability": return "Teilweise verfügbar";
            case "SoldOut": return "Ausverkauft";
            case "PreOrder": case "PreSale": return "Vorverkauf noch nicht gestartet";
            case "OutOfStock": return "Gerade keine Tickets";
            case "NotAvailable": return "Zurzeit nicht verfügbar";
            default: return av.isEmpty() ? "Status unbekannt" : av;
        }
    }

    static boolean buyable(String av) {
        return "InStock".equals(av) || "LimitedAvailability".equals(av) || "OnlineOnly".equals(av);
    }

    static boolean catAvailable(String cat) {
        String cl = cat.toLowerCase(Locale.GERMANY);
        boolean no = cl.contains("nicht verfügbar") || cl.contains("ausverkauft") || cl.contains("nicht mehr verfügbar");
        String rest = cl.replace("nicht mehr verfügbar", "").replace("nicht verfügbar", "");
        return !no || rest.contains("verfügbar") || rest.contains("warenkorb");
    }

    static boolean selected(String catName, JSONArray sel) {
        if (sel == null || sel.length() == 0) return true;
        for (int i = 0; i < sel.length(); i++) {
            if (catName.equalsIgnoreCase(sel.optString(i))) return true;
        }
        return false;
    }

    /**
     * Liest Kategorien, Preise und Status aus dem sichtbaren Seitentext.
     * sel = gewählte Kategorien (leer = alle). Status bezieht sich nur auf die gewählten.
     */
    static JSONObject analyzeEvent(String text, JSONArray sel) throws Exception {
        String[] lines = text.split("\\n");
        List<String> clean = new ArrayList<>();
        for (String l : lines) {
            String t = l.trim().replaceAll("\\s+", " ");
            if (!t.isEmpty()) clean.add(t);
        }
        JSONArray cats = new JSONArray();
        StringBuilder sum = new StringBuilder();
        int considered = 0, unavailable = 0;
        boolean anyBuy = false;
        for (int i = 0; i < clean.size(); i++) {
            if (!clean.get(i).toLowerCase(Locale.GERMANY).startsWith("kategorie")) continue;
            String name = clean.get(i);
            StringBuilder b = new StringBuilder();
            for (int k = i + 1; k < clean.size() && k <= i + 5; k++) {
                String n = clean.get(k);
                String nl = n.toLowerCase(Locale.GERMANY);
                if (nl.startsWith("kategorie") || nl.startsWith("versand") || nl.startsWith("informationen")) break;
                if (n.length() > 60) continue;
                if (n.matches("^[−+\\-0-9]{1,3}$")) continue;
                if (b.length() > 0) b.append(" · ");
                b.append(n);
            }
            boolean av = catAvailable(name + " " + b);
            boolean isSel = selected(name, sel);
            JSONObject cat = new JSONObject();
            cat.put("name", name);
            cat.put("info", b.toString());
            cat.put("available", av);
            cat.put("selected", isSel && sel != null && sel.length() > 0);
            cats.put(cat);
            if (sum.length() > 0) sum.append('\n');
            sum.append(name).append(b.length() > 0 ? " · " + b : "");
            if (isSel) {
                considered++;
                if (av) anyBuy = true; else unavailable++;
            }
        }
        String low = text.toLowerCase(Locale.GERMANY);
        String status;
        if (cats.length() > 0 && considered > 0) {
            if (!anyBuy) status = low.contains("ausverkauft") ? "SoldOut" : "NotAvailable";
            else status = unavailable > 0 ? "LimitedAvailability" : "InStock";
        } else if (cats.length() > 0) {
            status = "NotAvailable"; // gewählte Kategorien gibt es (noch) nicht
        } else if (low.contains("in den warenkorb")) {
            status = "InStock";
        } else if (low.contains("ausverkauft")) {
            status = "SoldOut";
        } else if (low.contains("zurzeit nicht verfügbar")) {
            status = "NotAvailable";
        } else {
            status = "";
        }
        JSONObject r = new JSONObject();
        r.put("status", status);
        r.put("summary", sum.toString());
        r.put("cats", cats);
        return r;
    }

    /** Einzelnen Termin prüfen: Ticketstatus erkennen (über unsichtbaren Browser). */
    static JSONObject checkEvent(Context c, JSONObject item) throws Exception {
        String url = item.optString("url").split("\\?")[0];
        JSONObject r = new JSONObject();
        r.put("name", item.optString("name"));
        r.put("url", item.optString("url"));
        r.put("type", "event");
        r.put("ok", false);
        JSONArray events = new JSONArray();
        String av = "";
        String summary = "";
        String detail;
        WebLoader.Result w = WebLoader.load(c, url, 35000);
        String low = w.text.toLowerCase(Locale.GERMANY);
        if (w.error != null) {
            detail = "Eventim: " + w.error;
        } else if (low.contains("access denied") || low.contains("captcha") || low.contains("pardon our interruption")) {
            detail = "Eventim blockiert den Browser";
        } else {
            r.put("ok", true);
            JSONObject a = analyzeEvent(w.text, item.optJSONArray("cats"));
            av = a.optString("status");
            summary = a.optString("summary");
            r.put("cats", a.optJSONArray("cats"));
            Matcher id = EVENT_LINK.matcher(url);
            String eid = id.find() ? id.group(1).toLowerCase(Locale.ROOT) : "";
            JSONArray found = extract(w.html, eid);
            JSONObject ev = null;
            for (int i = 0; i < found.length(); i++) {
                JSONObject f = found.optJSONObject(i);
                if (f.has("date") && f.optString("link").contains(eid)) ev = f;
            }
            if (ev == null) {
                ev = new JSONObject();
                ev.put("link", url.endsWith("/") ? url : url + "/");
                ev.put("title", item.optString("name"));
            }
            ev.put("availability", av);
            events.put(ev);
            detail = "Geprüft im Browser (" + (w.text.length() / 1024) + " KB Text)";
        }
        r.put("events", events);
        r.put("status", av);
        r.put("statusText", statusText(av));
        r.put("summary", summary);
        r.put("detail", detail);
        r.put("message", r.optBoolean("ok") ? "" : "Eventim nicht erreichbar – nächster Versuch später");
        return r;
    }

    public static synchronized JSONObject run(Context c, boolean notify) throws Exception {
        JSONArray watch = getWatch(c);
        JSONObject known;
        try {
            known = new JSONObject(prefs(c).getString("known", "{}"));
        } catch (Exception e) {
            known = new JSONObject();
        }
        JSONArray results = new JSONArray();
        int newCount = 0;
        for (int i = 0; i < watch.length(); i++) {
            JSONObject item = watch.optJSONObject(i);
            if (item == null) continue;
            if (isEvent(item.optString("url"))) {
                JSONObject r = checkEvent(c, item);
                if (r.optBoolean("ok")) {
                    String key = "event:" + item.optString("url").split("\\?")[0];
                    String before = known.has(key) ? known.optString(key) : null;
                    String now = r.optString("status");
                    r.put("changed", before != null && !before.equals(now));
                    if (notify && !now.equals(before) && (before != null || buyable(now))) {
                        newCount++;
                        JSONObject ev = r.optJSONArray("events").optJSONObject(0);
                        String body = statusText(now) + (r.optString("summary").isEmpty() ? "" : "\n" + r.optString("summary"));
                        if (ev != null && ev.optString("date").length() >= 10) {
                            String d = ev.optString("date");
                            body = d.substring(8, 10) + "." + d.substring(5, 7) + "." + d.substring(0, 4) + " · " + body;
                        }
                        if (buyable(now) && item.optBoolean("autoCart")) {
                            autoCart(c, item, 2000 + i, body);
                        } else {
                            showNotification(c, 2000 + i, (buyable(now) ? "🎟️ Tickets! " : "ℹ️ ") + item.optString("name"), body,
                                    buyable(now), item.optString("url"));
                        }
                    }
                    known.put(key, now);
                }
                results.put(r);
                continue;
            }
            JSONObject r = checkArtist(c, item);
            if (r.optBoolean("ok")) {
                String key = slugOf(item.optString("url"));
                JSONArray before = known.optJSONArray(key);
                List<String> beforeList = new ArrayList<>();
                if (before != null) for (int k = 0; k < before.length(); k++) beforeList.add(before.optString(k));
                JSONArray events = r.optJSONArray("events");
                JSONArray newLinks = new JSONArray();
                JSONArray all = new JSONArray();
                StringBuilder body = new StringBuilder();
                int shown = 0;
                for (int k = 0; k < events.length(); k++) {
                    JSONObject ev = events.optJSONObject(k);
                    String link = ev.optString("link");
                    all.put(link);
                    if (!beforeList.contains(link)) {
                        newLinks.put(link);
                        if (shown < 4) {
                            if (body.length() > 0) body.append('\n');
                            String d = ev.optString("date", "");
                            if (d.length() >= 10) body.append(d.substring(8, 10)).append('.').append(d.substring(5, 7)).append('.').append(d.substring(0, 4)).append(" · ");
                            String place = ev.optString("place", "");
                            body.append(place.isEmpty() ? ev.optString("title") : place);
                            shown++;
                        }
                    }
                }
                r.put("newLinks", newLinks);
                if (newLinks.length() > 0 && notify) {
                    newCount += newLinks.length();
                    if (newLinks.length() > 4) body.append("\n…");
                    showNotification(c, 1000 + i, "🎟️ " + item.optString("name") + ": " + newLinks.length() + (newLinks.length() == 1 ? " neuer Termin" : " neue Termine"), body.toString());
                }
                known.put(key, all);
            }
            results.put(r);
        }
        JSONObject state = new JSONObject();
        state.put("time", System.currentTimeMillis());
        state.put("results", results);
        state.put("newCount", newCount);
        prefs(c).edit().putString("known", known.toString()).putString("last", state.toString()).apply();
        return state;
    }

    static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean notificationsAllowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    public static boolean showNotification(Context c, int id, String title, String body) {
        return showNotification(c, id, title, body, false, null);
    }

    /** Versucht, Tickets automatisch in den Warenkorb zu legen (im App-Browser, dort wo du angemeldet bist). */
    static JSONObject autoCart(Context c, JSONObject item, int id, String body) {
        String url = item.optString("url").split("\\?")[0];
        int qty = Math.max(1, Math.min(10, item.optInt("qty", 1)));
        JSONArray sel = item.optJSONArray("cats");
        String js = CART_JS.replace("__QTY__", String.valueOf(qty)).replace("__CATS__", sel == null ? "[]" : sel.toString());
        WebLoader.Result w = WebLoader.load(c, url, 30000, js);
        String low = (w.text + " " + w.finalUrl).toLowerCase(Locale.GERMANY);
        boolean inCart = !w.finalUrl.isEmpty() && !w.finalUrl.split("\\?")[0].equals(url) && (low.contains("warenkorb") || low.contains("basket") || low.contains("checkout"));
        String log = new java.text.SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(new java.util.Date()) + ": "
                + (w.error != null ? w.error : w.action.replace("\"", "")) + " → " + (inCart ? "im Warenkorb ✅" : "nicht im Warenkorb ❌") + " (" + w.finalUrl + ")";
        prefs(c).edit().putString("cartLog", log).apply();
        JSONObject res = new JSONObject();
        try {
            res.put("inCart", inCart);
            res.put("log", log);
            res.put("url", inCart ? w.finalUrl : url);
        } catch (Exception ignored) {
        }
        if (inCart) {
            showNotification(c, id, "🛒 " + qty + " Ticket(s) im Warenkorb! Jetzt bezahlen", item.optString("name") + "\nTippen und Kauf abschließen – Warenkorb ist nur kurz reserviert!", true, "shop:" + w.finalUrl);
        } else {
            showNotification(c, id, "🎟️ Tickets! " + item.optString("name"), body + "\nAutomatisch in den Warenkorb hat nicht geklappt – schnell selbst kaufen!", true, "shop:" + url);
        }
        return res;
    }

    /**
     * alarm = lauter Alarmton. target = Link zum Öffnen beim Tippen:
     * "shop:URL" öffnet den App-Browser (angemeldet), sonst Kiwi.
     */
    public static boolean showNotification(Context c, int id, String title, String body, boolean alarm, String target) {
        if (!notificationsAllowed(c)) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Neue Konzerttermine", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Meldet neue Termine auf Eventim");
            nm.createNotificationChannel(ch);
            NotificationChannel al = new NotificationChannel(CHANNEL_ALARM, "Tickets verfügbar (Alarm)", NotificationManager.IMPORTANCE_HIGH);
            al.setDescription("Lauter Alarm, wenn Tickets verfügbar sind");
            al.enableVibration(true);
            al.setVibrationPattern(new long[] { 0, 800, 400, 800, 400, 800 });
            al.setBypassDnd(true);
            al.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(al);
        }
        Intent open;
        if (target != null && target.startsWith("shop:")) {
            open = new Intent(c, ShopActivity.class);
            open.putExtra("url", target.substring(5));
        } else if (target != null && !target.isEmpty() && isInstalled(c, "com.kiwibrowser.browser")) {
            open = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target));
            open.setPackage("com.kiwibrowser.browser");
        } else {
            open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        }
        PendingIntent pi = null;
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, alarm ? CHANNEL_ALARM : CHANNEL)
                .setSmallIcon(c.getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(alarm ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_HIGH)
                .setCategory(alarm ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true);
        if (pi != null) b.setContentIntent(pi);
        try {
            NotificationManagerCompat.from(c).notify(id, b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
