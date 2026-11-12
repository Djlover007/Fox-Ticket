package de.fox.ticket;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Schnellmodus: prüft alle 2 Minuten, solange er eingeschaltet ist. */
public class FastCheckService extends Service {

    static final String CHANNEL = "fast";
    static final int NOTIF_ID = 42;
    static final long INTERVAL_MS = 2 * 60 * 1000;

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            String line;
            try {
                JSONObject state = TicketChecker.run(FastCheckService.this, true);
                line = summary(state);
            } catch (Exception e) {
                line = "Fehler: " + e.getMessage();
            }
            String time = new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date());
            updateNotification("Zuletzt " + time + " Uhr · " + line);
            // leicht zufälliger Abstand (100–150 s), wirkt weniger wie ein Automat
            handler.postDelayed(this, 100000 + new java.util.Random().nextInt(50000));
        }
    };

    public static void setEnabled(Context c, boolean on) {
        TicketChecker.prefs(c).edit().putBoolean("fast", on).apply();
        Intent i = new Intent(c, FastCheckService.class);
        if (on) ContextCompat.startForegroundService(c, i);
        else c.stopService(i);
    }

    public static boolean isEnabled(Context c) {
        return TicketChecker.prefs(c).getBoolean("fast", false);
    }

    static String summary(JSONObject state) {
        JSONArray rs = state.optJSONArray("results");
        if (rs == null || rs.length() == 0) return "nichts zu prüfen";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rs.length(); i++) {
            JSONObject r = rs.optJSONObject(i);
            if (b.length() > 0) b.append(" | ");
            if (!r.optBoolean("ok")) b.append("nicht erreichbar");
            else if ("event".equals(r.optString("type"))) b.append(r.optString("statusText"));
            else b.append(r.optJSONArray("events").length()).append(" Termine");
        }
        return b.toString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("fox-fast");
        thread.start();
        handler = new Handler(thread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FoxTicket:fast");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Prüft alle 2 Minuten …"), type);
        if (!wakeLock.isHeld()) wakeLock.acquire();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private android.app.Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Schnellmodus", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Daueranzeige, solange alle 2 Minuten geprüft wird");
            nm.createNotificationChannel(ch);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = open == null ? null : PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("⚡ Fox Ticket Schnellmodus")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (pi != null) b.setContentIntent(pi);
        return b.build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        thread.quitSafely();
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
