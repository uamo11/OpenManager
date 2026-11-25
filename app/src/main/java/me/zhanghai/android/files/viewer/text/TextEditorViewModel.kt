/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import android.os.Parcelable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.file.AxmlDecoder
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.isFinished
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.toError
import me.zhanghai.android.files.util.toLoading
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class TextEditorViewModel(file: Path) : ViewModel() {
    private val _file = MutableStateFlow(file)
    val file = _file.asStateFlow()

    private val _bytesState = MutableStateFlow<DataState<ByteArray>>(DataState.Loading())

    val fileSize = MutableStateFlow(0L)
    val isCrlf = MutableStateFlow(false)
    val syntaxLanguage = MutableStateFlow(SyntaxLanguage.detectFromFileName(file.fileName.toString()))

    var lastLoadedModifiedTime: Long = 0L
        private set
    var lastLoadedFileSize: Long = 0L
        private set
    var isSelfSaving: Boolean = false
        private set

    private var loadJob: Job? = null
    private var reloadJob: Job? = null

    init {
        viewModelScope.launch {
            _file.collectLatest {
                loadJob?.cancel()?.also { loadJob = null }
                reloadJob?.cancel()?.also { reloadJob = null }
                loadJob = launch {
                    mapFileToBytesState(it)
                    if (isActive) {
                        loadJob = null
                    }
                }
            }
        }
    }

    fun reload() {
        viewModelScope.launch {
            loadJob?.cancel()?.also { loadJob = null }
            reloadJob?.cancel()?.also { reloadJob = null }
            reloadJob = launch {
                mapFileToBytesState(_file.value)
                if (isActive) {
                    reloadJob = null
                }
            }
        }
    }

    /**
     * Set when the user chose to read only the first [MAX_FILE_SIZE] of a larger file: the text is
     * then read-only and can never be saved (it would cut the file).
     */
    val isPartial = MutableStateFlow(false)

    fun loadPartial() {
        isPartial.value = true
        reload()
    }

    private suspend fun mapFileToBytesState(file: Path) {
        _bytesState.value = _bytesState.value.toLoading()
        try {
            val partial = isPartial.value
            val (bytes, size, modTime) = runInterruptible(Dispatchers.IO) {
                val size = file.size()
                if (size > MAX_FILE_SIZE && !partial) {
                    throw FileTooLargeException(size, MAX_FILE_SIZE)
                }
                val modTime = try {
                    if (file.isLinuxPath) file.toFile().lastModified() else file.getLastModifiedTime().toMillis()
                } catch (_: Throwable) {
                    0L
                }
                val bytes = if (partial && size > MAX_FILE_SIZE) readHead(file) else file.readAllBytes()
                Triple(bytes, size, modTime)
            }
            fileSize.value = size
            lastLoadedFileSize = size
            lastLoadedModifiedTime = modTime
            currentCoroutineContext().ensureActive()
            _bytesState.value = DataState.Success(bytes)
        } catch (e: CancellationException) {
            e.printStackTrace()
        } catch (e: Exception) {
            _bytesState.value = _bytesState.value.toError(e)
        }
    }

    val encoding = MutableStateFlow(StandardCharsets.UTF_8)

    private val _textState = MutableStateFlow<DataState<CharSequence>>(DataState.Loading())
    val textState = _textState.asStateFlow()

    init {
        viewModelScope.launch {
            // The editor colors the text itself, so a change of language does not reload it.
            combine(_bytesState, encoding) { bytesState, encoding ->
                bytesState to encoding
            }.collectLatest { (bytesState, encoding) ->
                when (bytesState) {
                    is DataState.Loading -> _textState.value = _textState.value.toLoading()
                    is DataState.Success -> {
                        _textState.value = _textState.value.toLoading()
                        try {
                            val highlighted = withContext(Dispatchers.Default) {
                                val rawText = if (AxmlDecoder.isAxml(bytesState.data)) {
                                    try {
                                        AxmlDecoder.decode(bytesState.data)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        String(bytesState.data, encoding)
                                    }
                                } else {
                                    String(bytesState.data, encoding)
                                }

                                // Detect and normalize line endings
                                val hasCrlf = rawText.contains("\r\n")
                                isCrlf.value = hasCrlf
                                val normalized = if (hasCrlf) rawText.replace("\r\n", "\n") else rawText

                                normalized
                            }
                            currentCoroutineContext().ensureActive()
                            _textState.value = DataState.Success(highlighted)
                        } catch (e: CancellationException) {
                            e.printStackTrace()
                        } catch (e: Exception) {
                            _textState.value = _textState.value.toError(e)
                        }
                    }
                    is DataState.Error ->
                        _textState.value = _textState.value.toError(bytesState.throwable)
                }
            }
        }
    }

    val isTextChanged = MutableStateFlow(false)

    private val _writeFileState =
        MutableStateFlow<ActionState<Pair<Path, String>, Unit>>(ActionState.Ready())
    val writeFileState = _writeFileState.asStateFlow()

    fun setLineEndings(toCrlf: Boolean) {
        if (isCrlf.value != toCrlf) {
            isCrlf.value = toCrlf
            isTextChanged.value = true
        }
    }

    fun writeFile(path: Path, text: String, context: Context) {
        // Only part of the file is loaded: writing it would cut the file.
        if (isPartial.value && path == _file.value) return
        // A second tap on Save while the first save runs used to fail check() and close the app.
        if (!_writeFileState.value.isReady) return
        viewModelScope.launch {
            val argument = path to text
            _writeFileState.value = ActionState.Running(argument)
            isSelfSaving = true

            val bytes = withContext(Dispatchers.Default) {
                // Restore line endings if CRLF
                val textToSave = if (isCrlf.value) {
                    text.replace("\r\n", "\n").replace("\n", "\r\n")
                } else {
                    text.replace("\r\n", "\n")
                }
                textToSave.toByteArray(encoding.value)
            }

            FileJobService.write(path, bytes, context) { successful ->
                if (successful) {
                    loadJob?.cancel()?.also { loadJob = null }
                    reloadJob?.cancel()?.also { reloadJob = null }
                    val newModTime = try {
                        if (path.isLinuxPath) path.toFile().lastModified() else path.getLastModifiedTime().toMillis()
                    } catch (_: Throwable) {
                        System.currentTimeMillis()
                    }
                    lastLoadedModifiedTime = newModTime
                    lastLoadedFileSize = bytes.size.toLong()
                    fileSize.value = bytes.size.toLong()
                    _bytesState.value = DataState.Success(bytes)
                }
                isSelfSaving = false
                _writeFileState.value = if (successful) {
                    ActionState.Success(argument, Unit)
                } else {
                    ActionState.Error(argument, Throwable())
                }
            }
        }
    }

    fun hasExternalChange(path: Path = _file.value): Boolean {
        if (isSelfSaving) return false
        if (lastLoadedModifiedTime <= 0L) return false
        val (currentModTime, currentSize) = try {
            val m = if (path.isLinuxPath) path.toFile().lastModified() else path.getLastModifiedTime().toMillis()
            val s = if (path.isLinuxPath) path.toFile().length() else path.size()
            m to s
        } catch (_: Throwable) {
            return false
        }
        if (currentModTime <= 0L) return false

        return currentModTime > lastLoadedModifiedTime ||
            (currentModTime != lastLoadedModifiedTime && currentSize != lastLoadedFileSize)
    }

    fun acknowledgeExternalChange(path: Path = _file.value) {
        try {
            val m = if (path.isLinuxPath) path.toFile().lastModified() else path.getLastModifiedTime().toMillis()
            val s = if (path.isLinuxPath) path.toFile().length() else path.size()
            if (m > 0L) {
                lastLoadedModifiedTime = m
                lastLoadedFileSize = s
            }
        } catch (_: Throwable) {}
    }

    fun finishWritingFile() {
        viewModelScope.launch {
            if (_writeFileState.value.isFinished) {
                _writeFileState.value = ActionState.Ready()
            }
        }
    }

    /**
     * The editor's document (with its undo history) while the screen is rebuilt, e.g. on rotation:
     * the new editor view continues with it instead of losing the edits.
     */
    var retainedContent: Any? = null
    var retainedSelection: IntArray? = null

    override fun onCleared() {
        retainedContent = null
        super.onCleared()
    }

    private var editTextSavedState: Parcelable? = null

    fun setEditTextSavedState(editTextSavedState: Parcelable?) {
        this.editTextSavedState = editTextSavedState
    }

    fun removeEditTextSavedState(): Parcelable? {
        val savedState = editTextSavedState
        editTextSavedState = null
        return savedState
    }

    /** The first [MAX_FILE_SIZE] bytes, cut after the last line break near the end when there is one. */
    private fun readHead(file: Path): ByteArray {
        val head = ByteArray(MAX_FILE_SIZE.toInt())
        var read = 0
        file.newInputStream().use { input ->
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
        }
        var end = read
        for (i in read - 1 downTo maxOf(0, read - 64 * 1024)) {
            if (head[i] == '\n'.code.toByte()) {
                end = i + 1
                break
            }
        }
        return head.copyOf(end)
    }

    /** The file is bigger than the editor accepts. */
    class FileTooLargeException(val size: Long, val maxSize: Long) :
        IOException("File size $size is too large (max $maxSize)")

    companion object {
        const val MAX_FILE_SIZE = 20 * 1024 * 1024L
    }
}
