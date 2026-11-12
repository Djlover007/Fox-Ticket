package de.fox.ticket;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Schickt Alarme zusätzlich per Telegram (eigener Bot des Nutzers), damit sie auf allen Geräten ankommen. */
public class Telegram {

    public static void set(Context c, String token, String chat) throws Exception {
        SharedPreferences.Editor e = LoginManager.secure(c).edit();
        if (token != null && !token.isEmpty()) e.putString("tgToken", token.trim());
        e.putString("tgChat", chat == null ? "" : chat.trim());
        if (chat == null || chat.trim().isEmpty()) e.remove("tgToken");
        e.apply();
    }

    public static String chat(Context c) {
        try {
            return LoginManager.secure(c).getString("tgChat", "");
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasToken(Context c) {
        try {
            return !LoginManager.secure(c).getString("tgToken", "").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Gibt null bei Erfolg zurück, sonst eine Fehlermeldung. */
    public static String send(Context c, String text) {
        HttpURLConnection con = null;
        try {
            SharedPreferences p = LoginManager.secure(c);
            String token = p.getString("tgToken", "");
            String chat = p.getString("tgChat", "");
            if (token.isEmpty() || chat.isEmpty()) return "nicht eingerichtet";
            con = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/sendMessage").openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String data = "chat_id=" + URLEncoder.encode(chat, "UTF-8") + "&text=" + URLEncoder.encode(text, "UTF-8");
            try (OutputStream os = con.getOutputStream()) {
                os.write(data.getBytes("UTF-8"));
            }
            int code = con.getResponseCode();
            if (code == 200) return null;
            if (code == 401 || code == 404) return "Bot-Token ist falsch";
            if (code == 400 || code == 403) return "Chat-ID falsch oder du hast deinem Bot noch nicht „/start“ geschrieben";
            return "Telegram antwortet mit HTTP " + code;
        } catch (Exception e) {
            return "Netzwerkfehler: " + e.getClass().getSimpleName();
        } finally {
            if (con != null) con.disconnect();
        }
    }

    public static void sendAsync(final Context c, final String text) {
        new Thread(() -> send(c, text)).start();
    }
}
