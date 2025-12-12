package com.pirorin215.fastrecmob.viewModel

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

class AudioPlayerManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val _currentlyPlayingFile = MutableStateFlow<String?>(null)
    val currentlyPlayingFile: StateFlow<String?> = _currentlyPlayingFile.asStateFlow()

    fun play(filePath: String) {
        if (mediaPlayer != null && mediaPlayer?.isPlaying == true) {
            stop()
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setOnCompletionListener {
                    _currentlyPlayingFile.value = null
                }
                prepare()
                start()
                _currentlyPlayingFile.value = filePath
            }
        } catch (e: IOException) {
            _currentlyPlayingFile.value = null
            // Handle exception
        }
    }

    fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.reset()
            it.release()
        }
        mediaPlayer = null
        _currentlyPlayingFile.value = null
    }

    fun release() {
        stop()
    }
}
