/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.script

import androidx.lifecycle.ViewModel
import java8.nio.file.Path

class FilePropertiesScriptTabViewModel(path: Path) : ViewModel() {
    val scriptInfoLiveData = ScriptInfoLiveData(path)

    fun reload() {
        scriptInfoLiveData.loadValue()
    }

    override fun onCleared() {
        scriptInfoLiveData.close()
    }
}
