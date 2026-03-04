package com.roguegamestudio.rugbytcg.audio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.util.Log;

import com.roguegamestudio.rugbytcg.SettingsPrefs;
import com.roguegamestudio.rugbytcg.engine.AnnouncerEvent;
import com.roguegamestudio.rugbytcg.engine.AnnouncerSink;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AnnouncerController implements AnnouncerSink {
    private static final String TAG = "AnnouncerController";
    private static final int MAX_PENDING_ANNOUNCEMENTS = 8;
    private static final long CLIP_WAIT_SLICE_MS = 120L;
    private static final float PLAYBACK_SPEED = 3.0f;

    private final Context appContext;
    private final AssetManager assetManager;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final Object queueLock = new Object();
    private final Object drainLock = new Object();
    private final Object playerLock = new Object();
    private final ArrayDeque<PendingAnnouncement> queue = new ArrayDeque<>();

    private volatile boolean draining = false;
    private MediaPlayer currentPlayer;

    private static final class PendingAnnouncement {
        final List<String> clips;
        final boolean critical;

        PendingAnnouncement(List<String> clips, boolean critical) {
            this.clips = clips == null ? new ArrayList<>() : new ArrayList<>(clips);
            this.critical = critical;
        }
    }

    public AnnouncerController(Context context) {
        this.appContext = context.getApplicationContext();
        this.assetManager = appContext.getAssets();
    }

    @Override
    public void onAnnouncerEvent(AnnouncerEvent event) {
        if (event == null || released.get()) return;
        if (!SettingsPrefs.getAnnouncerEnabled(appContext)) return;

        List<String> clips = AnnouncerPlaylistBuilder.buildPlaylist(event);
        if (clips.isEmpty()) {
            Log.d(TAG, "No playlist generated for event: " + event.type + ", side=" + event.side);
            return;
        }

        synchronized (queueLock) {
            offerLocked(new PendingAnnouncement(clips, event.critical));
        }
        scheduleDrain();
    }

    public void release() {
        if (!released.compareAndSet(false, true)) return;
        synchronized (queueLock) {
            queue.clear();
        }
        stopCurrentPlayer();
        worker.shutdownNow();
    }

    private void offerLocked(PendingAnnouncement incoming) {
        if (incoming == null || incoming.clips.isEmpty()) return;

        if (queue.size() < MAX_PENDING_ANNOUNCEMENTS) {
            queue.addLast(incoming);
            return;
        }

        if (incoming.critical) {
            if (!removeOldestNonCriticalLocked()) {
                queue.pollFirst();
            }
            queue.addLast(incoming);
            return;
        }

        if (removeOldestNonCriticalLocked()) {
            queue.addLast(incoming);
        }
    }

    private boolean removeOldestNonCriticalLocked() {
        Iterator<PendingAnnouncement> iterator = queue.iterator();
        while (iterator.hasNext()) {
            PendingAnnouncement existing = iterator.next();
            if (!existing.critical) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void scheduleDrain() {
        synchronized (drainLock) {
            if (released.get() || draining) return;
            draining = true;
        }
        submitWorker(this::drainQueue);
    }

    private void drainQueue() {
        try {
            while (!released.get()) {
                if (!SettingsPrefs.getAnnouncerEnabled(appContext)) {
                    synchronized (queueLock) {
                        queue.clear();
                    }
                    stopCurrentPlayer();
                    break;
                }

                PendingAnnouncement next;
                synchronized (queueLock) {
                    next = queue.pollFirst();
                }

                if (next == null) break;
                playAnnouncement(next);
            }
        } finally {
            synchronized (drainLock) {
                draining = false;
                boolean hasPending;
                synchronized (queueLock) {
                    hasPending = !queue.isEmpty();
                }
                if (!released.get() && hasPending) {
                    draining = true;
                    submitWorker(this::drainQueue);
                }
            }
        }
    }

    private void playAnnouncement(PendingAnnouncement announcement) {
        if (announcement == null || announcement.clips == null || announcement.clips.isEmpty()) return;

        for (String clip : announcement.clips) {
            if (released.get()) return;
            if (!SettingsPrefs.getAnnouncerEnabled(appContext)) return;
            playSingleClip(clip);
        }
    }

    private void playSingleClip(String clipPath) {
        if (clipPath == null || clipPath.trim().isEmpty()) return;

        MediaPlayer player = null;
        try (AssetFileDescriptor afd = assetManager.openFd(clipPath)) {
            if (afd.getLength() <= 0L) {
                Log.w(TAG, "Announcer clip empty: " + clipPath);
                return;
            }

            CountDownLatch finished = new CountDownLatch(1);
            player = new MediaPlayer();
            player.setOnCompletionListener(mp -> finished.countDown());
            player.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "Announcer clip playback error: " + clipPath + " (" + what + ", " + extra + ")");
                finished.countDown();
                return true;
            });

            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            float volume = clamp01(SettingsPrefs.getAnnouncerVolumeLevel(appContext));
            player.setVolume(volume, volume);
            player.prepare();
            applyPlaybackSpeed(player);

            synchronized (playerLock) {
                if (released.get()) {
                    safeRelease(player);
                    return;
                }
                currentPlayer = player;
            }

            player.start();
            waitForCompletion(finished);
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Announcer clip missing: " + clipPath);
        } catch (IOException e) {
            Log.w(TAG, "Unable to open announcer clip: " + clipPath, e);
        } catch (Exception e) {
            Log.w(TAG, "Unexpected announcer clip failure: " + clipPath, e);
        } finally {
            clearCurrentPlayerIfSame(player);
            safeRelease(player);
        }
    }

    private void waitForCompletion(CountDownLatch finished) {
        while (!released.get()) {
            try {
                if (finished.await(CLIP_WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void applyPlaybackSpeed(MediaPlayer player) {
        if (player == null) return;
        try {
            PlaybackParams params = player.getPlaybackParams();
            if (params == null) params = new PlaybackParams();
            player.setPlaybackParams(params.setSpeed(PLAYBACK_SPEED));
        } catch (Exception ignored) {
            // Playback speed is optional on some devices/codecs.
        }
    }

    private float clamp01(float value) {
        if (Float.isNaN(value)) return 1f;
        return Math.max(0f, Math.min(1f, value));
    }

    private void stopCurrentPlayer() {
        MediaPlayer toStop;
        synchronized (playerLock) {
            toStop = currentPlayer;
            currentPlayer = null;
        }
        if (toStop == null) return;
        try {
            if (toStop.isPlaying()) {
                toStop.stop();
            }
        } catch (Exception ignored) {
        }
        safeRelease(toStop);
    }

    private void clearCurrentPlayerIfSame(MediaPlayer player) {
        if (player == null) return;
        synchronized (playerLock) {
            if (currentPlayer == player) {
                currentPlayer = null;
            }
        }
    }

    private void safeRelease(MediaPlayer player) {
        if (player == null) return;
        try {
            player.reset();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
    }

    private void submitWorker(Runnable task) {
        if (task == null) return;
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }
}