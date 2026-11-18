package com.yukisoffd.lyracode.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yukisoffd.lyracode.MainActivity
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.localizedContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.TimeUnit

class DeepSeekHarnessBalanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val account = DeepSeekHarnessAccountClient.get(applicationContext)
        val state = account.view.value
        val id = state.profileId
        if (id != null && state.alert != null) {
            // A failed or unavailable wallet read has no effect on the persisted alert.
            runCatching { account.refreshBalance(id, maxAgeMillis = 60_000) }
        }
        Result.success()
    }

    internal companion object {
        private const val WORK_NAME = "deepseek-harness-balance"

        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<DeepSeekHarnessBalanceWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

internal class DeepSeekHarnessBalanceNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun available(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    @SuppressLint("MissingPermission")
    fun post(alert: HarnessBalanceAlert, amount: BigDecimal): Boolean {
        val localized = context.localizedContext(AppSettings(context).languageMode)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, localized.getString(R.string.harness_balance_alert), NotificationManager.IMPORTANCE_LOW,
        ))
        if (!available()) return false
        val locale = localized.resources.configuration.locales[0]
        val message = localized.getString(
            R.string.harness_alert_message,
            formatHarnessAmount(amount, alert.currency, locale),
            formatHarnessAmount(alert.threshold, alert.currency, locale),
        )
        val launch = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return runCatching {
            manager.notify(CHANNEL, NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(localized.getString(R.string.harness_alert_title))
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(launch)
                    .setAutoCancel(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build())
        }.isSuccess
    }

    fun clear() = manager.cancel(CHANNEL, NOTIFICATION_ID)

    companion object {
        const val CHANNEL = "deepseek_harness_balance"
        private const val NOTIFICATION_ID = 1
    }
}

internal fun formatHarnessAmount(amount: BigDecimal, currency: HarnessCurrency, locale: Locale): String =
    NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = Currency.getInstance(currency.name)
        minimumFractionDigits = 2
        maximumFractionDigits = 6
    }.format(amount)
