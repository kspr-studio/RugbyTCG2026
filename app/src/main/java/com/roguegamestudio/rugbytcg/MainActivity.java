package com.roguegamestudio.rugbytcg;

import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    private MediaPlayer bgPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new GameView(this));
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
        if (bgPlayer != null && bgPlayer.isPlaying()) {
            bgPlayer.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (bgPlayer != null) {
            bgPlayer.release();
            bgPlayer = null;
        }
        super.onDestroy();
    }

    private void initBackgroundMusic() {
        if (bgPlayer != null) return;

        bgPlayer = new MediaPlayer();
        try (AssetFileDescriptor afd = getAssets().openFd("snd/bg.mp3")) {
            bgPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            bgPlayer.setLooping(true);
            applyBackgroundMusicVolume();
            bgPlayer.prepare();
        } catch (IOException e) {
            bgPlayer.release();
            bgPlayer = null;
        }
    }

    private void startBackgroundMusic() {
        if (bgPlayer != null && !bgPlayer.isPlaying()) {
            applyBackgroundMusicVolume();
            bgPlayer.start();
        }
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
