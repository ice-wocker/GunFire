package com.icewocker.gunfire;

import android.content.SharedPreferences;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.icewocker.gunfire.audio.SynthSound;
import com.icewocker.gunfire.engine.Renderer;
import com.icewocker.gunfire.game.GameWorld;
import com.icewocker.gunfire.game.HudView;
import com.icewocker.gunfire.game.TouchControls;

/**
 * 唯一 Activity。
 *
 * 结构是「GLSurfaceView 铺满 + HUD 覆盖 + 触摸接管」，
 * 游戏循环挂在 GL 渲染线程上，HUD 用主线程 postInvalidate 刷新，
 * 两者只通过 GameWorld 的只读快照通信——不需要锁。
 */
public final class MainActivity extends AppCompatActivity {

    private static final String PREFS = "gunfire";
    private static final String KEY_BEST_WAVE = "best_wave";

    private GLSurfaceView surface;
    private HudView hud;
    private GameWorld world;
    private TouchControls controls;
    private Renderer renderer;
    private SynthSound sound;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 音效状态从世界事件推导，避免音频层去轮询游戏状态。 */
    private int lastShots = -1;
    private int lastHits = -1;
    private int lastKills = -1;
    private int lastHeadshots = -1;
    private float lastHealth = -1f;
    private boolean lastReloading;
    private boolean lastGameOver;
    private int lastPickups = -1;

    private final Runnable hudTicker = new Runnable() {
        @Override
        public void run() {
            if (world != null && hud != null) {
                hud.setFps(renderer == null ? 0f : renderer.fps());
                hud.invalidate();
                pumpSound();
            }
            handler.postDelayed(this, 100L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();

        world = new GameWorld();
        world.setBestWaveEver(loadBestWave());
        sound = new SynthSound();
        renderer = new Renderer();
        renderer.attachWorld(world);

        FrameLayout root = new FrameLayout(this);

        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surface.setPreserveEGLContextOnPause(true);
        surface.setFocusableInTouchMode(true);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        hud = new HudView(this);
        hud.bind(world);
        // HUD 只负责显示，不拦截触摸
        hud.setClickable(false);
        hud.setFocusable(false);
        root.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        controls = new TouchControls(this, world, hud);
        hud.bindControls(controls);
        surface.setOnTouchListener(controls);
        renderer.attachControls(controls);

        setContentView(root);
        handler.postDelayed(hudTicker, 100L);
    }

    /**
     * 从「分数类计数器」的增量推导音效。
     *
     * 游戏规则层保持纯 Java（不 import android），所以它不能自己放声音。
     * 这里只做差值检测，逻辑最少，也不会在规则里散落音频调用。
     */
    private void pumpSound() {
        if (lastShots < 0) {
            lastShots = world.shotsFired();
            lastHits = world.shotsHit();
            lastKills = world.kills();
            lastHeadshots = world.headshots();
            lastHealth = world.health();
            lastReloading = world.reloading();
            lastGameOver = world.gameOver();
            lastPickups = world.pickups().size();
            return;
        }
        if (world.shotsFired() > lastShots) {
            if (world.shotsFired() - lastShots > 4) {
                sound.play(SynthSound.Id.SHOTGUN);
            } else if (world.weapon().damage >= 100f) {
                sound.play(SynthSound.Id.SNIPER);
            } else {
                sound.play(SynthSound.Id.RIFLE);
            }
        }
        if (world.headshots() > lastHeadshots) {
            sound.play(SynthSound.Id.HEADSHOT);
        } else if (world.kills() > lastKills) {
            sound.play(SynthSound.Id.KILL);
        } else if (world.shotsHit() > lastHits) {
            sound.play(SynthSound.Id.HIT);
        }
        if (world.health() < lastHealth - 0.5f) {
            sound.play(SynthSound.Id.HURT);
        }
        if (world.reloading() && !lastReloading) {
            sound.play(SynthSound.Id.RELOAD);
        }
        if (world.pickups().size() > lastPickups) {
            sound.play(SynthSound.Id.PICKUP);
        }
        if (world.gameOver() && !lastGameOver) {
            saveBestWave(world.bestWaveEver());
        }

        lastShots = world.shotsFired();
        lastHits = world.shotsHit();
        lastKills = world.kills();
        lastHeadshots = world.headshots();
        lastHealth = world.health();
        lastReloading = world.reloading();
        lastPickups = world.pickups().size();
        lastGameOver = world.gameOver();
    }

    private int loadBestWave() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getInt(KEY_BEST_WAVE, 1);
    }

    private void saveBestWave(int wave) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (wave > prefs.getInt(KEY_BEST_WAVE, 1)) {
            prefs.edit().putInt(KEY_BEST_WAVE, wave).apply();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        renderer.resetFrameClock();
        surface.onResume();
        surface.queueEvent(() -> surface.requestFocus());
        handler.removeCallbacks(hudTicker);
        handler.postDelayed(hudTicker, 100L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(hudTicker);
        // 切后台自动暂停：回来时不会发现自己已经被打了半管血
        if (world != null && !world.gameOver()) {
            world.setPaused(true);
        }
        saveBestWave(world == null ? 1 : world.bestWaveEver());
        surface.onPause();
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** 供渲染器查询当前输入，驱动移动。 */
    public TouchControls controls() {
        return controls;
    }
}
