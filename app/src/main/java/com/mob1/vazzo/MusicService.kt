package com.mob1.vazzo

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions

class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {
    private var myBinder = MyBinder()
    var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var runnable: Runnable
    lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private val NOTIFICATION_ID = 13
    private val CHANNEL_ID = "MusicNotification"

    companion object {
        private const val TAG = "MusicService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Créer le canal de notification ici
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Now Playing Song",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Needed to Show Notification for Playing Song"
            }
            Log.d(TAG, "Creating notification channel: $CHANNEL_ID")
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind called")
        mediaSession = MediaSessionCompat(baseContext, "My Music")
        return myBinder
    }

    inner class MyBinder : Binder() {
        fun currentService(): MusicService {
            return this@MusicService
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with intent: $intent")
        showNotification(if (PlayerActivity.isPlaying) R.drawable.pause_icon else R.drawable.play_icon, true)
        return START_STICKY
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    fun showNotification(playPauseBtn: Int, startForeground: Boolean = false) {
        Log.d(TAG, "showNotification called with playPauseBtn: $playPauseBtn, startForeground: $startForeground")

        val intent = Intent(baseContext, MainActivity::class.java)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, intent, flag)
        Log.d(TAG, "Creating content intent with flag: $flag")

        val prevIntent = Intent(baseContext, NotificationReceiver::class.java).setAction(ApplicationClass.PREVIOUS)
        val prevPendingIntent = PendingIntent.getBroadcast(baseContext, 0, prevIntent, flag)
        Log.d(TAG, "Creating previous intent")

        val playIntent = Intent(baseContext, NotificationReceiver::class.java).setAction(ApplicationClass.PLAY)
        val playPendingIntent = PendingIntent.getBroadcast(baseContext, 0, playIntent, flag)
        Log.d(TAG, "Creating play intent")

        val nextIntent = Intent(baseContext, NotificationReceiver::class.java).setAction(ApplicationClass.NEXT)
        val nextPendingIntent = PendingIntent.getBroadcast(baseContext, 0, nextIntent, flag)
        Log.d(TAG, "Creating next intent")

        val exitIntent = Intent(baseContext, NotificationReceiver::class.java).setAction(ApplicationClass.EXIT)
        val exitPendingIntent = PendingIntent.getBroadcast(baseContext, 0, exitIntent, flag)
        Log.d(TAG, "Creating exit intent")

        val isMusicListValid = PlayerActivity.musicListPA.isNotEmpty() && PlayerActivity.songPosition >= 0 && PlayerActivity.songPosition < PlayerActivity.musicListPA.size
        Log.d(TAG, "musicListPA valid: $isMusicListValid, size: ${PlayerActivity.musicListPA.size}, songPosition: ${PlayerActivity.songPosition}")

        val imgArt = if (isMusicListValid) getImgArt(PlayerActivity.musicListPA[PlayerActivity.songPosition].path) else null
        val image = if (imgArt != null) {
            Log.d(TAG, "Decoding image from art")
            BitmapFactory.decodeByteArray(imgArt, 0, imgArt.size)
        } else {
            Log.d(TAG, "Using default image: R.drawable.music_player_icon_slash_screen")
            BitmapFactory.decodeResource(resources, R.drawable.music_player_icon_slash_screen)
        }

        val title = if (isMusicListValid) PlayerActivity.musicListPA[PlayerActivity.songPosition].title else "No Song Playing"
        val artist = if (isMusicListValid) PlayerActivity.musicListPA[PlayerActivity.songPosition].artist else "Unknown"
        Log.d(TAG, "Notification title: $title, artist: $artist")

        try {
            Log.d(TAG, "Building notification")
            val notification = androidx.core.app.NotificationCompat.Builder(baseContext, CHANNEL_ID)
                .setContentIntent(contentIntent)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(R.drawable.music_icon)
                .setLargeIcon(image)
                .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.previous_icon, "Previous", prevPendingIntent)
                .addAction(playPauseBtn, "Play", playPendingIntent)
                .addAction(R.drawable.next_icon, "Next", nextPendingIntent)
                .addAction(R.drawable.exit_icon, "Exit", exitPendingIntent)
                .build()

            Log.d(TAG, "Notification built successfully")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Setting media session metadata and playback state")
                mediaSession.setMetadata(
                    MediaMetadataCompat.Builder().putLong(
                        MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer?.duration?.toLong() ?: 0L
                    ).build()
                )
                mediaSession.setPlaybackState(getPlayBackState())
                mediaSession.setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        Log.d(TAG, "MediaSession: onPlay")
                        super.onPlay()
                        handlePlayPause()
                    }
                    override fun onPause() {
                        Log.d(TAG, "MediaSession: onPause")
                        super.onPause()
                        handlePlayPause()
                    }
                    override fun onSkipToNext() {
                        Log.d(TAG, "MediaSession: onSkipToNext")
                        super.onSkipToNext()
                        prevNextSong(increment = true, context = baseContext)
                    }
                    override fun onSkipToPrevious() {
                        Log.d(TAG, "MediaSession: onSkipToPrevious")
                        super.onSkipToPrevious()
                        prevNextSong(increment = false, context = baseContext)
                    }
                    override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                        Log.d(TAG, "MediaSession: onMediaButtonEvent")
                        handlePlayPause()
                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }
                    override fun onSeekTo(pos: Long) {
                        Log.d(TAG, "MediaSession: onSeekTo $pos")
                        super.onSeekTo(pos)
                        mediaPlayer?.seekTo(pos.toInt())
                        mediaSession.setPlaybackState(getPlayBackState())
                    }
                })
            }

            if (startForeground) {
                Log.d(TAG, "Starting foreground service with notification")
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "Foreground service started successfully")
            } else {
                Log.d(TAG, "Updating notification without starting foreground")
                notificationManager.notify(NOTIFICATION_ID, notification)
                Log.d(TAG, "Notification updated successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build or start notification: ${e.message}", e)
            throw e
        }
    }

    fun createMediaPlayer() {
        try {
            Log.d(TAG, "Creating media player")
            if (mediaPlayer == null) mediaPlayer = MediaPlayer()
            mediaPlayer?.reset()
            mediaPlayer?.setDataSource(PlayerActivity.musicListPA[PlayerActivity.songPosition].path)
            mediaPlayer?.prepare()
            PlayerActivity.binding.playPauseBtnPA.setIconResource(R.drawable.pause_icon)
            showNotification(R.drawable.pause_icon) // Mise à jour uniquement
            PlayerActivity.binding.tvSeekBarStart.text = formatDuration(mediaPlayer!!.currentPosition.toLong())
            PlayerActivity.binding.tvSeekBarEnd.text = formatDuration(mediaPlayer!!.duration.toLong())
            PlayerActivity.binding.seekBarPA.progress = 0
            PlayerActivity.binding.seekBarPA.max = mediaPlayer!!.duration
            PlayerActivity.nowPlayingId = PlayerActivity.musicListPA[PlayerActivity.songPosition].id
            Log.d(TAG, "Media player created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating media player: ${e.message}", e)
        }
    }

    fun seekBarSetup() {
        Log.d(TAG, "Setting up seek bar")
        runnable = Runnable {
            PlayerActivity.binding.tvSeekBarStart.text = formatDuration(mediaPlayer!!.currentPosition.toLong())
            PlayerActivity.binding.seekBarPA.progress = mediaPlayer!!.currentPosition
            Handler(Looper.getMainLooper()).postDelayed(runnable, 200)
        }
        Handler(Looper.getMainLooper()).postDelayed(runnable, 0)
    }

    fun getPlayBackState(): PlaybackStateCompat {
        val playbackSpeed = if (PlayerActivity.isPlaying) 1F else 0F
        val state = if (mediaPlayer?.isPlaying == true) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        Log.d(TAG, "getPlayBackState: state=$state, position=${mediaPlayer?.currentPosition}, speed=$playbackSpeed")
        return PlaybackStateCompat.Builder()
            .setState(state, mediaPlayer?.currentPosition?.toLong() ?: 0L, playbackSpeed)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .build()
    }

    fun handlePlayPause() {
        Log.d(TAG, "handlePlayPause: isPlaying=${PlayerActivity.isPlaying}")
        if (PlayerActivity.isPlaying) pauseMusic() else playMusic()
        mediaSession.setPlaybackState(getPlayBackState())
    }

    private fun prevNextSong(increment: Boolean, context: Context) {
        Log.d(TAG, "prevNextSong: increment=$increment")
        setSongPosition(increment = increment)
        PlayerActivity.musicService?.createMediaPlayer()
        Glide.with(context)
            .load(PlayerActivity.musicListPA[PlayerActivity.songPosition].artUri)
            .apply(RequestOptions().placeholder(R.drawable.music_player_icon_slash_screen).centerCrop())
            .into(PlayerActivity.binding.songImgPA)
        PlayerActivity.binding.songNamePA.text = PlayerActivity.musicListPA[PlayerActivity.songPosition].title
        Glide.with(context)
            .load(PlayerActivity.musicListPA[PlayerActivity.songPosition].artUri)
            .apply(RequestOptions().placeholder(R.drawable.music_player_icon_slash_screen).centerCrop())
            .into(NowPlaying.binding.songImgNP)
        NowPlaying.binding.songNameNP.text = PlayerActivity.musicListPA[PlayerActivity.songPosition].title
        playMusic()
        PlayerActivity.fIndex = favouriteChecker(PlayerActivity.musicListPA[PlayerActivity.songPosition].id)
        if (PlayerActivity.isFavourite) PlayerActivity.binding.favouriteBtnPA.setImageResource(R.drawable.favourite_icon)
        else PlayerActivity.binding.favouriteBtnPA.setImageResource(R.drawable.favourite_empty_icon)
        mediaSession.setPlaybackState(getPlayBackState())
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "onAudioFocusChange: focusChange=$focusChange")
        if (focusChange <= 0) {
            pauseMusic()
        }
    }

    private fun playMusic() {
        Log.d(TAG, "playMusic called")
        PlayerActivity.binding.playPauseBtnPA.setIconResource(R.drawable.pause_icon)
        NowPlaying.binding.playPauseBtnNP.setIconResource(R.drawable.pause_icon)
        PlayerActivity.isPlaying = true
        mediaPlayer?.start()
        showNotification(R.drawable.pause_icon) // Mise à jour uniquement
    }

    private fun pauseMusic() {
        Log.d(TAG, "pauseMusic called")
        PlayerActivity.binding.playPauseBtnPA.setIconResource(R.drawable.play_icon)
        NowPlaying.binding.playPauseBtnNP.setIconResource(R.drawable.play_icon)
        PlayerActivity.isPlaying = false
        mediaPlayer?.pause()
        showNotification(R.drawable.play_icon) // Mise à jour uniquement
    }
}