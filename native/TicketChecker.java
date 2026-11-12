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
        String url = item.optString("url");
        String name = item.optString("name");
        String slug = slugOf(url);
        JSONObject r = new JSONObject();
        r.put("name", name);
        r.put("url", url);
        r.put("ok", false);
        JSONArray events = new JSONArray();
        String detail;

        Page page = fetch(url.split("\\?")[0]);
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
            JSONObject r = checkArtist(item);
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

    public static boolean notificationsAllowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    public static boolean showNotification(Context c, int id, String title, String body) {
        if (!notificationsAllowed(c)) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Neue Konzerttermine", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Meldet neue Termine auf Eventim");
            nm.createNotificationChannel(ch);
        }
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        PendingIntent pi = null;
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(c.getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
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
