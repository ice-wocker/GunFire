package com.icewocker.gunfire;

import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

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

    private GLSurfaceView surface;
    private HudView hud;
    private GameWorld world;
    private TouchControls controls;
    private Renderer renderer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastFrameNanos;

    private final Runnable hudTicker = new Runnable() {
        @Override
        public void run() {
            if (world != null && hud != null) {
                hud.setFps(renderer == null ? 0f : renderer.fps());
                hud.invalidate();
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
        root.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT) {{
            // HUD 只负责显示，不拦截触摸
        }});
        hud.setClickable(false);
        hud.setFocusable(false);

        controls = new TouchControls(this, world);
        surface.setOnTouchListener(controls);
        renderer.attachControls(controls);

        setContentView(root);
        handler.postDelayed(hudTicker, 100L);
    }

    /** 让游戏循环里的 dt 有真实来源，避免掉帧时瞬移。 */
    public float consumeDeltaSeconds() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1f / 60f;
        }
        float dt = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        return GameWorld.clamp(dt, 0f, 0.05f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        lastFrameNanos = 0L;
        surface.onResume();
        surface.queueEvent(() -> surface.requestFocus());
        handler.removeCallbacks(hudTicker);
        handler.postDelayed(hudTicker, 100L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(hudTicker);
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
