package me.zhanghai.android.files.filelist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DemoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: return
        android.util.Log.i("DemoActionReceiver", "Received broadcast action: $action")
        val activityIntent = Intent(context, FileListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("SHOW_DEMO_DIALOG", action)
        }
        context.startActivity(activityIntent)
    }
}
