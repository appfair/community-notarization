package org.appfair.notarization;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Reports outcomes the policy reached on its own, with a tap-through to the assessments. */
public final class Notifications {
    private static final String CHANNEL = "notarization";

    public static void post(Context context, int id, String title, String text, Intent details) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.channel_description));
        nm.createNotificationChannel(channel);

        PendingIntent tap = PendingIntent.getActivity(context, id, details,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notary)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }

    private Notifications() { }
}
