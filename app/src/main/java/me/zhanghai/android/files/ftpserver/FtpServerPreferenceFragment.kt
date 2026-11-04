/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.os.Bundle
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.valueCompat

class FtpServerPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        if (!Settings.FTP_SERVER_ANONYMOUS_LOGIN.valueCompat && Settings.FTP_SERVER_PASSWORD.valueCompat.isEmpty()) {
            Settings.FTP_SERVER_PASSWORD.putValue(FtpServerService.generateRandomPassword())
        }
        addPreferencesFromResource(R.xml.ftp_server)
    }
}
