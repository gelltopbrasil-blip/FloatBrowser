package com.floatbrowser.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notify {
    fun alert(ctx: Context, nid: Int, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alert", "Alertas", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(ctx, "alert")
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true).build()
        nm.notify(nid, n)
    }
}
