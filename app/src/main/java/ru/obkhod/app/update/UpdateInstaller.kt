package ru.obkhod.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * Надёжное обновление: скачивание системным DownloadManager с уведомлением о прогрессе,
 * после завершения — автоматический запуск установщика Android.
 * Перед первой установкой запрашивает разрешение «Установка из этого приложения».
 */
object UpdateInstaller {

    /** true = скачивание запущено; false = сначала нужно разрешить установку (открыты настройки). */
    fun downloadAndInstall(
        context: Context,
        url: String,
        version: String,
        onNeedInstallPermission: () -> Unit,
    ): Boolean {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            // Открыть системную настройку «Установка приложений» для нашего пакета
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
            context.startActivity(intent)
            onNeedInstallPermission()
            return false
        }

        val fileName = "МойУчасток-v$version.apk"
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Мой участок $version")
            .setDescription("Обновление приложения")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                val query = DownloadManager.Query().setFilterById(downloadId)
                dm.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uri = dm.getUriForDownloadedFile(downloadId)
                            val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
                                .setData(uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(install)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return true
    }
}
