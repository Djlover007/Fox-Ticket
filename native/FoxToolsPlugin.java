package de.fox.ticket;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

@CapacitorPlugin(
    name = "FoxTools",
    permissions = { @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications") }
)
public class FoxToolsPlugin extends Plugin {

    static final String KIWI = "com.kiwibrowser.browser";

    @Override
    public void load() {
        schedule();
        if (FastCheckService.isEnabled(getContext())) {
            try {
                FastCheckService.setEnabled(getContext(), true);
            } catch (Exception ignored) {
            }
        }
    }

    @PluginMethod
    public void setFastMode(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        try {
            FastCheckService.setEnabled(getContext(), on);
            if (on && Build.VERSION.SDK_INT >= 23) {
                android.os.PowerManager pm = (android.os.PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getContext().getPackageName())) {
                    Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getContext().getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                }
            }
            JSObject r = new JSObject();
            r.put("fast", FastCheckService.isEnabled(getContext()));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Schnellmodus: " + e.getMessage());
        }
    }

    private void schedule() {
        try {
            Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(TicketWorker.class, 30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
            WorkManager.getInstance(getContext()).enqueueUniquePeriodicWork("fox-ticket-check", ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Exception ignored) {
        }
    }

    @PluginMethod
    public void getState(PluginCall call) {
        try {
            JSObject r = new JSObject();
            r.put("watch", new JSArray(TicketChecker.getWatch(getContext()).toString()));
            JSONObject last = TicketChecker.getLast(getContext());
            if (last != null) r.put("last", JSObject.fromJSONObject(last));
            r.put("notifications", TicketChecker.notificationsAllowed(getContext()));
            r.put("fast", FastCheckService.isEnabled(getContext()));
            r.put("cartLog", TicketChecker.prefs(getContext()).getString("cartLog", ""));
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void setWatch(PluginCall call) {
        JSArray arr = call.getArray("watch", new JSArray());
        TicketChecker.setWatch(getContext(), arr);
        call.resolve();
    }

    @PluginMethod
    public void checkNow(PluginCall call) {
        new Thread(() -> {
            try {
                JSONObject state = TicketChecker.run(getContext(), true);
                call.resolve(JSObject.fromJSONObject(state));
            } catch (Exception e) {
                call.reject("Prüfen fehlgeschlagen: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void testNotification(PluginCall call) {
        boolean ok = TicketChecker.showNotification(getContext(), 999, "🦊 Fox Ticket", "Benachrichtigungen funktionieren!");
        JSObject r = new JSObject();
        r.put("shown", ok);
        call.resolve(r);
    }

    @PluginMethod
    public void notificationStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", TicketChecker.notificationsAllowed(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void askNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notificationsCallback");
        } else if (!TicketChecker.notificationsAllowed(getContext())) {
            openNotificationSettings();
            notificationStatus(call);
        } else {
            notificationStatus(call);
        }
    }

    @PermissionCallback
    private void notificationsCallback(PluginCall call) {
        if (!TicketChecker.notificationsAllowed(getContext())) openNotificationSettings();
        notificationStatus(call);
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("android.provider.extra.APP_PACKAGE", getContext().getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url");
        if (url == null) {
            call.reject("url fehlt");
            return;
        }
        Uri uri = Uri.parse(url);
        JSObject r = new JSObject();
        try {
            Intent kiwi = new Intent(Intent.ACTION_VIEW, uri);
            kiwi.setPackage(KIWI);
            kiwi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(kiwi);
            r.put("browser", "kiwi");
        } catch (ActivityNotFoundException e) {
            Intent any = Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri), "Öffnen mit");
            any.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(any);
            r.put("browser", "auswahl");
        }
        call.resolve(r);
    }

    /** Lädt die neue APK herunter und öffnet den Android-Installationsdialog. */
    @PluginMethod
    public void installUpdate(PluginCall call) {
        String url = call.getString("url");
        if (url == null) {
            call.reject("url fehlt");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getContext().getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception ignored) {
            }
            JSObject r = new JSObject();
            r.put("needsPermission", true);
            call.resolve(r);
            return;
        }
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                File dir = new File(getContext().getCacheDir(), "updates");
                dir.mkdirs();
                File apk = new File(dir, "Fox-Ticket.apk");
                String next = url;
                for (int hop = 0; hop < 5; hop++) {
                    con = (HttpURLConnection) new URL(next).openConnection();
                    con.setInstanceFollowRedirects(false);
                    con.setConnectTimeout(20000);
                    con.setReadTimeout(60000);
                    int code = con.getResponseCode();
                    if (code >= 300 && code < 400 && con.getHeaderField("Location") != null) {
                        next = new URL(new URL(next), con.getHeaderField("Location")).toString();
                        con.disconnect();
                        continue;
                    }
                    if (code != 200) throw new Exception("HTTP " + code);
                    break;
                }
                try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apk);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(uri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(install);
                JSObject r = new JSObject();
                r.put("started", true);
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Update fehlgeschlagen: " + e.getMessage());
            } finally {
                if (con != null) con.disconnect();
            }
        }).start();
    }

    /** Öffnet Eventim im App-Browser (z. B. zum Anmelden oder Bezahlen). */
    @PluginMethod
    public void openShop(PluginCall call) {
        Intent i = new Intent(getContext(), ShopActivity.class);
        i.putExtra("url", call.getString("url", "https://www.eventim.de/"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void testAlarm(PluginCall call) {
        boolean ok = TicketChecker.showNotification(getContext(), 998, "🎟️ Test-Alarm", "So klingt es, wenn Tickets verfügbar sind", true, "shop:https://www.eventim.de/");
        JSObject r = new JSObject();
        r.put("shown", ok);
        call.resolve(r);
    }

    /** Warenkorb-Test: sichtbar im App-Browser, damit man zusehen und direkt bezahlen kann (kauft nichts). */
    @PluginMethod
    public void testCart(PluginCall call) {
        int index = call.getInt("index", -1);
        JSONObject item = TicketChecker.getWatch(getContext()).optJSONObject(index);
        if (item == null) {
            call.reject("Eintrag nicht gefunden");
            return;
        }
        int qty = Math.max(1, Math.min(10, item.optInt("qty", 1)));
        JSONArray sel = item.optJSONArray("cats");
        String js = TicketChecker.CART_JS.replace("__QTY__", String.valueOf(qty)).replace("__CATS__", sel == null ? "[]" : sel.toString());
        Intent i = new Intent(getContext(), ShopActivity.class);
        i.putExtra("url", item.optString("url").split("\\?")[0]);
        i.putExtra("cartJs", js);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        JSObject r = new JSObject();
        r.put("visible", true);
        call.resolve(r);
    }
}
