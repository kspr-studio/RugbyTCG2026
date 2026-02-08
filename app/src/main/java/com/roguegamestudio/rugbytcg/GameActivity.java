package com.roguegamestudio.rugbytcg;

import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

public class GameActivity extends AppCompatActivity {
    public static final String EXTRA_TUTORIAL = "tutorial";
    public static final String EXTRA_ONLINE_MATCH_ID = "online_match_id";
    public static final String EXTRA_ONLINE_OPPONENT_LABEL = "online_opponent_label";
    private static final String PREFS = "rugby_prefs";
    private static final String KEY_TUTORIAL_DONE = "tutorial_done";

    private MediaPlayer bgPlayer;
    private boolean bgPrepared = false;
    private boolean resumePlaybackRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean tutorial = getIntent().getBooleanExtra(EXTRA_TUTORIAL, false);
        String onlineMatchId = getIntent().getStringExtra(EXTRA_ONLINE_MATCH_ID);
        String onlineOpponentLabel = getIntent().getStringExtra(EXTRA_ONLINE_OPPONENT_LABEL);
        GameView gameView = new GameView(this, tutorial, onlineMatchId, onlineOpponentLabel);
        gameView.setTutorialListener(() -> {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_TUTORIAL_DONE, true).apply();
        });
        gameView.setMenuListener(() -> {
            startActivity(new android.content.Intent(this, MenuActivity.class));
            finish();
        });
        setContentView(gameView);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        initBackgroundMusic();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        applyBackgroundMusicVolume();
        startBackgroundMusic();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onPause() {
        resumePlaybackRequested = false;
        if (bgPlayer != null && bgPrepared && bgPlayer.isPlaying()) {
            bgPlayer.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseBackgroundMusic();
        super.onDestroy();
    }

    private void initBackgroundMusic() {
        if (bgPlayer != null) return;

        bgPlayer = new MediaPlayer();
        try (AssetFileDescriptor afd = getAssets().openFd("snd/bg.mp3")) {
            bgPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            bgPlayer.setLooping(true);
            applyBackgroundMusicVolume();
            bgPlayer.setOnPreparedListener(mp -> {
                bgPrepared = true;
                applyBackgroundMusicVolume();
                if (resumePlaybackRequested && !mp.isPlaying()) {
                    mp.start();
                }
                resumePlaybackRequested = false;
            });
            bgPlayer.setOnErrorListener((mp, what, extra) -> {
                releaseBackgroundMusic();
                return true;
            });
            bgPrepared = false;
            bgPlayer.prepareAsync();
        } catch (IOException e) {
            releaseBackgroundMusic();
        }
    }

    private void startBackgroundMusic() {
        if (bgPlayer == null) return;
        applyBackgroundMusicVolume();
        if (bgPrepared) {
            if (!bgPlayer.isPlaying()) {
                bgPlayer.start();
            }
            resumePlaybackRequested = false;
        } else {
            resumePlaybackRequested = true;
        }
    }

    private void releaseBackgroundMusic() {
        if (bgPlayer != null) {
            bgPlayer.setOnPreparedListener(null);
            bgPlayer.setOnErrorListener(null);
            bgPlayer.release();
            bgPlayer = null;
        }
        bgPrepared = false;
        resumePlaybackRequested = false;
    }

    private void applyBackgroundMusicVolume() {
        if (bgPlayer == null) return;
        float volume = SettingsPrefs.getCrowdVolumeLevel(this);
        bgPlayer.setVolume(volume, volume);
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }
}
