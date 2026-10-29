package dev.anilbeesetti.nextplayer.feature.player

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.core.util.Consumer
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import dev.anilbeesetti.nextplayer.core.cast.CastDevice
import dev.anilbeesetti.nextplayer.core.cast.CastManager
import dev.anilbeesetti.nextplayer.core.cast.CastMimeTypes
import dev.anilbeesetti.nextplayer.core.cast.CastStatus
import dev.anilbeesetti.nextplayer.core.cast.CastUiState
import dev.anilbeesetti.nextplayer.core.common.extensions.getInitialDirectoryUri
import dev.anilbeesetti.nextplayer.core.common.extensions.getMediaContentUri
import dev.anilbeesetti.nextplayer.core.common.service.registerForSuspendActivityResult
import dev.anilbeesetti.nextplayer.core.data.repository.PlaylistRepository
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.extensions.OpenDocumentAtInitialUri
import dev.anilbeesetti.nextplayer.feature.player.extensions.setExtras
import dev.anilbeesetti.nextplayer.feature.player.extensions.uriToSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.service.addAudioTrack
import dev.anilbeesetti.nextplayer.feature.player.service.addSubtitleTrack
import dev.anilbeesetti.nextplayer.feature.player.service.decoderServiceState
import dev.anilbeesetti.nextplayer.feature.player.service.setAudioDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.service.setVideoDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.service.stopPlayerSession
import dev.anilbeesetti.nextplayer.feature.player.service.tryDecoderFallback
import dev.anilbeesetti.nextplayer.feature.player.state.rememberMediaController
import dev.anilbeesetti.nextplayer.feature.player.ui.CastDevicePanel
import dev.anilbeesetti.nextplayer.feature.player.ui.PlayerSettingsOverlay
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import dev.anilbeesetti.nextplayer.feature.player.utils.PlaylistPlaybackContract
import dev.anilbeesetti.nextplayer.feature.player.utils.asExplicitPlaylistItem
import dev.anilbeesetti.nextplayer.feature.player.utils.toMediaQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

internal fun shouldResumeExistingPlayback(
    returningFromBackground: Boolean,
    isRequestedUriCurrent: Boolean,
    hasExplicitPlaylist: Boolean,
    hasNextMediaItem: Boolean,
): Boolean = returningFromBackground ||
    (isRequestedUriCurrent && !hasExplicitPlaylist && hasNextMediaItem)

@SuppressLint("UnsafeOptInUsageError")
class PlayerActivity : ComponentActivity() {

    private val playlistRepository: PlaylistRepository by inject()

    private val viewModel: PlayerViewModel by viewModel { parametersOf(playerOutput()) }
    val playerPreferences get() = viewModel.state.value.playerPreferences

    private val onWindowAttributesChangedListener =
        CopyOnWriteArrayList<Consumer<WindowManager.LayoutParams?>>()

    private var isPlaybackFinished = false
    private var isIntentNew: Boolean = true

    private var mediaController by mutableStateOf<MediaController?>(value = null)
    private lateinit var playerApi: PlayerApi
    private var playbackRequestJob: Job? = null

    private val castManager by lazy { CastManager(context = applicationContext) }
    private var castState by mutableStateOf(value = CastUiState())
    private var showCastPanel by mutableStateOf(value = false)
    private var resumeLocalPlaybackAfterCast = false

    private val playbackStateListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            intent.data = mediaItem?.localConfiguration?.uri
            // The next item starts from scratch, so a completed state belongs to the previous one.
            isPlaybackFinished = false
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateKeepScreenOnFlag()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Stay on the last frame and let the user replay instead of closing the player.
            // Any other state (seeking away from the end, buffering again) means it is no longer finished.
            isPlaybackFinished = playbackState == Player.STATE_ENDED
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            when {
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> {
                    // Pausing at the very end keeps STATE_READY, so the state alone is not enough.
                    isPlaybackFinished = true
                }

                playWhenReady -> {
                    // Replaying the last frame or resuming playback cancels the finished flag.
                    isPlaybackFinished = false
                }
            }
        }
    }

    private val audioFileSuspendLauncher =
        registerForSuspendActivityResult(contract = OpenDocumentAtInitialUri())

    private val subtitleFileSuspendLauncher =
        registerForSuspendActivityResult(contract = OpenDocumentAtInitialUri())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isIntentNew = savedInstanceState?.getBoolean(
            "isIntentNew",
            true,
        ) ?: true
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        viewModel.output = playerOutput()
        playerApi = PlayerApi(activity = this)
        setContent {
            val player = rememberMediaController(
                onBeforeRelease = ::onControllerStopped,
                onExtrasChanged = { extras ->
                    viewModel.onAction(
                        PlayerAction.UpdateDecoderServiceState(extras.decoderServiceState()),
                    )
                },
            )
            LaunchedEffect(key1 = player) {
                if (player == null || !player.isConnected) return@LaunchedEffect
                mediaController = player
                viewModel.onAction(
                    PlayerAction.UpdateDecoderServiceState(
                        player.sessionExtras.decoderServiceState(),
                    ),
                )
                player.addListener(playbackStateListener)
                updateKeepScreenOnFlag()
                startPlayback()
            }

            LaunchedEffect(key1 = Unit) {
                var previousDevice: CastDevice? = null
                castManager.state.collect { state ->
                    castState = state
                    if (previousDevice != null && state.castingDevice == null) {
                        if (resumeLocalPlaybackAfterCast) mediaController?.play()
                        resumeLocalPlaybackAfterCast = false
                    }
                    previousDevice = state.castingDevice
                }
            }

            NextPlayerTheme(darkTheme = true) {
                var showSettings by rememberSaveable { mutableStateOf(value = false) }
                Box(modifier = Modifier.fillMaxSize()) {
                    MediaPlayerScreen(
                        viewModel = viewModel,
                        player = player,
                        onSettingsClick = { showSettings = true },
                        isCasting = castState.status == CastStatus.CASTING ||
                            castState.status == CastStatus.CONNECTING,
                        onCastClick = {
                            showCastPanel = true
                            if (castState.status != CastStatus.CASTING) castManager.discover()
                        },
                    )
                    if (showCastPanel) {
                        CastDevicePanel(
                            state = castState,
                            onDismiss = { showCastPanel = false },
                            onRefresh = { castManager.discover() },
                            onDeviceClick = ::startCasting,
                            onPlayPauseClick = {
                                if (castState.isPlaying) castManager.pause() else castManager.play()
                            },
                            onStopClick = { castManager.stopCasting() },
                        )
                    }
                }
                if (showSettings) {
                    PlayerSettingsOverlay(onDismiss = { showSettings = false })
                }
            }
        }
    }

    private fun playerOutput() = PlayerViewModel.Output(
        navigateUp = ::finishAndStopPlayerSession,
        selectSubtitle = ::selectSubtitle,
        selectAudio = ::selectAudio,
        setVideoDecoderMode = { mode ->
            lifecycleScope.launch { mediaController?.setVideoDecoderMode(mode = mode) }
        },
        setAudioDecoderMode = { mode ->
            lifecycleScope.launch { mediaController?.setAudioDecoderMode(mode = mode) }
        },
        tryDecoderFallback = { lifecycleScope.launch { mediaController?.tryDecoderFallback() } },
    )

    private suspend fun currentMediaDirectory(): Uri? {
        val uri = mediaController?.currentMediaItem?.localConfiguration?.uri ?: return null
        return withContext(Dispatchers.IO) { getInitialDirectoryUri(fileUri = uri) }
    }

    private fun selectSubtitle() {
        lifecycleScope.launch {
            val uri = subtitleFileSuspendLauncher.launch(
                OpenDocumentAtInitialUri.Input(
                    mimeTypes = arrayOf(
                        MimeTypes.APPLICATION_SUBRIP,
                        MimeTypes.APPLICATION_TTML,
                        MimeTypes.TEXT_VTT,
                        MimeTypes.TEXT_SSA,
                        MimeTypes.BASE_TYPE_APPLICATION + "/octet-stream",
                        MimeTypes.BASE_TYPE_TEXT + "/*",
                    ),
                    initialUri = currentMediaDirectory(),
                ),
            ) ?: return@launch
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            snapshotFlow { mediaController }.filterNotNull().first().addSubtitleTrack(uri = uri)
        }
    }

    private fun selectAudio() {
        lifecycleScope.launch {
            val uri = audioFileSuspendLauncher.launch(
                OpenDocumentAtInitialUri.Input(
                    mimeTypes = arrayOf(
                        "audio/*",
                        "application/ogg",
                    ),
                    initialUri = currentMediaDirectory(),
                ),
            ) ?: return@launch
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val controller = snapshotFlow { mediaController }.filterNotNull().first()
                if (!controller.addAudioTrack(uri = uri)) {
                    Toast.makeText(
                        this@PlayerActivity,
                        coreUiR.string.error_opening_audio,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (_: SecurityException) {
                Toast.makeText(
                    this@PlayerActivity,
                    coreUiR.string.error_opening_audio,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun startCasting(device: CastDevice) {
        val controller = mediaController ?: return
        val mediaItem = controller.currentMediaItem ?: return
        val uri = mediaItem.localConfiguration?.uri ?: return
        val title = mediaItem.mediaMetadata.title?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: getString(coreUiR.string.unknown)
        val mimeType = CastMimeTypes.resolve(
            contentResolver = contentResolver,
            uri = uri,
            hint = mediaItem.localConfiguration?.mimeType,
            title = mediaItem.mediaMetadata.title?.toString(),
        )
        resumeLocalPlaybackAfterCast = controller.playWhenReady
        controller.pause()
        castManager.cast(
            device = device,
            uri = uri,
            title = title,
            mimeType = mimeType,
            positionMs = controller.currentPosition,
        )
    }

    private fun onControllerStopped() {
        playbackRequestJob?.cancel()
        mediaController?.run {
            viewModel.onAction(PlayerAction.UpdatePlayWhenReady(playWhenReady = playWhenReady))
            removeListener(playbackStateListener)
            val shouldPlayInBackground = playerPreferences.autoBackgroundPlay
            if (subtitleFileSuspendLauncher.isAwaitingResult ||
                audioFileSuspendLauncher.isAwaitingResult ||
                !shouldPlayInBackground
            ) {
                pause()
            }
            if (isInPictureInPictureMode) {
                finish()
                if (!shouldPlayInBackground) stopPlayerSession()
            }
        }
        mediaController = null
        updateKeepScreenOnFlag()
    }

    private fun startPlayback() {
        val controller = mediaController ?: return
        if (!isIntentNew && controller.currentMediaItem == null) {
            // Completion can happen while the activity's playback listener is detached.
            isPlaybackFinished = true
            finishAndStopPlayerSession()
            return
        }
        val uri = intent.data ?: return

        val returningFromBackground = !isIntentNew
        val currentUri = controller.currentMediaItem?.localConfiguration?.uri
        val hasExplicitPlaylist = intent.hasExtra(PlayerApi.API_PLAYLIST) ||
            intent.hasExtra(PlaylistPlaybackContract.EXTRA_PLAYLIST_ID)
        isIntentNew = false

        if (shouldResumeExistingPlayback(
                returningFromBackground = returningFromBackground,
                isRequestedUriCurrent = currentUri.toString() == uri.toString(),
                hasExplicitPlaylist = hasExplicitPlaylist,
                hasNextMediaItem = controller.hasNextMediaItem(),
            )
        ) {
            controller.prepare()
            controller.playWhenReady = viewModel.state.value.playWhenReady
            return
        }

        playbackRequestJob?.cancel()
        playbackRequestJob = lifecycleScope.launch {
            playVideo(
                uri = uri,
                playlistId = intent.playlistIdOrNull(),
            )
        }
    }

    private suspend fun playVideo(uri: Uri, playlistId: Long?) = withContext(Dispatchers.Default) {
        val savedQueue = playlistId
            ?.let { playlistRepository.getPlaylist(playlistId = it) }
            ?.toMediaQueue(selectedUri = uri.toString())

        if (playlistId != null) {
            val mediaItems = savedQueue?.mediaItems ?: listOf(
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(uri.toString())
                    .build(),
            )
            val startIndex = savedQueue?.startIndex ?: 0
            ensureActive()
            withContext(Dispatchers.Main) {
                mediaController?.run {
                    setMediaItems(
                        mediaItems,
                        startIndex,
                        C.TIME_UNSET,
                    )
                    playWhenReady = viewModel.state.value.playWhenReady
                    prepare()
                }
            }
            return@withContext
        }

        val mediaContentUri = getMediaContentUri(uri = uri)
        val explicitPlaylist = playerApi.getPlaylist()
        val playlist = explicitPlaylist.takeIf { it.isNotEmpty() }
            ?: mediaContentUri?.let { mediaUri ->
                viewModel.getPlaylistFromUri(uri = mediaUri)
                    .map { it.uriString }
                    .toMutableList()
                    .apply {
                        if (!contains(mediaUri.toString())) {
                            add(
                                index = 0,
                                element = mediaUri.toString(),
                            )
                        }
                    }
            } ?: listOf(uri.toString())

        val mediaItemIndexToPlay = playlist.indexOfFirst {
            it == (mediaContentUri ?: uri).toString()
        }.takeIf { it >= 0 } ?: 0

        val mediaItems = playlist.mapIndexed { index, uri ->
            MediaItem.Builder().apply {
                setUri(uri)
                setMediaId(uri)
                if (index == mediaItemIndexToPlay) {
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(playerApi.title)
                            setExtras(positionMs = playerApi.position?.toLong())
                        }.build(),
                    )
                    val apiSubs = playerApi.getSubs().map { subtitle ->
                        uriToSubtitleConfiguration(
                            uri = subtitle.uri,
                            subtitleEncoding = playerPreferences.subtitleTextEncoding,
                            isSelected = subtitle.isSelected,
                        )
                    }
                    setSubtitleConfigurations(apiSubs)
                }
            }.build().let { item ->
                if (explicitPlaylist.isNotEmpty()) item.asExplicitPlaylistItem() else item
            }
        }

        withContext(Dispatchers.Main) {
            mediaController?.run {
                setMediaItems(
                    mediaItems,
                    mediaItemIndexToPlay,
                    playerApi.position?.toLong() ?: C.TIME_UNSET,
                )
                playWhenReady = viewModel.state.value.playWhenReady
                prepare()
            }
        }
    }

    private fun Intent.playlistIdOrNull(): Long? = getLongExtra(
        PlaylistPlaybackContract.EXTRA_PLAYLIST_ID,
        Long.MIN_VALUE,
    ).takeUnless { it == Long.MIN_VALUE }

    override fun finish() {
        if (playerApi.shouldReturnResult) {
            val result = playerApi.getResult(
                isPlaybackFinished = isPlaybackFinished,
                duration = mediaController?.duration ?: C.TIME_UNSET,
                position = mediaController?.currentPosition ?: C.TIME_UNSET,
            )
            setResult(
                RESULT_OK,
                result,
            )
        }
        super.finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) {
            setIntent(intent)
            isIntentNew = true
            startPlayback()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            "isIntentNew",
            isIntentNew,
        )
        super.onSaveInstanceState(outState)
    }

    private fun updateKeepScreenOnFlag() {
        if (mediaController?.isPlaying == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun finishAndStopPlayerSession() {
        finish()
        mediaController?.stopPlayerSession()
    }

    override fun onDestroy() {
        castManager.release()
        super.onDestroy()
    }

    override fun onWindowAttributesChanged(params: WindowManager.LayoutParams?) {
        super.onWindowAttributesChanged(params)
        for (listener in onWindowAttributesChangedListener) {
            listener.accept(value = params)
        }
    }

    fun addOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.add(listener)
    }

    fun removeOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.remove(listener)
    }
}
