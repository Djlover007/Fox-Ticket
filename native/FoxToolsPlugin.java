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
}
