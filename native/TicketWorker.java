package de.fox.ticket;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Läuft regelmäßig im Hintergrund (auch wenn die App geschlossen ist). */
public class TicketWorker extends Worker {

    public TicketWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            TicketChecker.run(getApplicationContext(), true);
        } catch (Exception ignored) {
        }
        return Result.success();
    }
}
