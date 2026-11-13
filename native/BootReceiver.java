package de.fox.ticket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet den Schnellmodus nach einem Neustart des Handys wieder. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && FastCheckService.isEnabled(context)
                && TicketChecker.backgroundOn(context)) {
            try {
                FastCheckService.setEnabled(context, true);
            } catch (Exception ignored) {
            }
        }
    }
}
