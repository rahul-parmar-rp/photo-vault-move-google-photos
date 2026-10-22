package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import com.example.data.AppPreferences
import com.example.service.MediaObserverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d("BootReceiver", "Boot or package update received: $action")
            val prefs = AppPreferences(context.applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                val isEnabled = prefs.isServiceEnabledFlow.first()
                if (isEnabled) {
                    val hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Environment.isExternalStorageManager()
                    } else {
                        true
                    }
                    if (hasStoragePermission) {
                        Log.i("BootReceiver", "Starting MediaObserverService on boot")
                        MediaObserverService.startService(context.applicationContext)
                    } else {
                        Log.w("BootReceiver", "Service enabled but storage permission not granted on boot")
                    }
                }
            }
        }
    }
}
