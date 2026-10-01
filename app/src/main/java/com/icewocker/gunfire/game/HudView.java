package com.icewocker.gunfire.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 覆盖在 GL 表面之上的 HUD。
 *
 * 用普通 Canvas 实现——准星、血量、弹药都不是 3D 内容，
 * 硬塞进 GL 只会增加着色器复杂度，反而更慢。
 */
public final class HudView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private GameWorld world;
    private float fps;

    public HudView(Context context) {
        super(context);
        init();
    }

    public HudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        setWillNotDraw(false);
    }

    public void bind(GameWorld world) {
        this.world = world;
    }

    public void setFps(float fps) {
        this.fps = fps;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;

        drawCrosshair(canvas, cx, cy);

        textPaint.setTextSize(dp(13));
        textPaint.setColor(Color.parseColor("#E6E8EF"));

        if (world == null) {
            canvas.drawText("载入中…", dp(20), dp(32), textPaint);
            return;
        }

        // 左上：分数 / 命中率 / 存活时间
        canvas.drawText("得分 " + world.score(), dp(16), dp(28), textPaint);
        canvas.drawText(String.format(java.util.Locale.US, "命中率 %.0f%%", world.accuracy() * 100f),
                dp(16), dp(48), textPaint);
        canvas.drawText(String.format(java.util.Locale.US, "第 %d 波 · %d:%02d",
                world.wave(), world.elapsedSeconds() / 60, world.elapsedSeconds() % 60),
                dp(16), dp(68), textPaint);

        // 顶部中央：剩余目标 + FPS
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("剩余目标 " + world.aliveTargets(), cx, dp(28), textPaint);
        canvas.drawText(String.format(java.util.Locale.US, "%.0f FPS", fps), cx, dp(48), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);

        // 连杀：只有真的连起来了才显示，否则屏幕会更乱
        drawStreak(canvas, cx);

        // 右下：弹药
        drawAmmo(canvas, w, h);

        if (world.gameOver()) {
            drawGameOver(canvas, w, h);
            return;
        }

        // 活跃射击时的散布指示
        float heat = world.muzzleFlash();
        if (heat > 0.01f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb((int) (heat * 200), 255, 170, 60));
            canvas.drawCircle(cx, cy, dp(28) + heat * dp(10), paint);
        }
    }

    /** 连杀提示。窗口剩余时间用一条会缩短的横条表示——玩家能看见自己还剩多久。 */
    private void drawStreak(Canvas canvas, float cx) {
        int streak = world.streak();
        if (streak < 2) {
            return;
        }
        float remain = world.streakRemaining();
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(18));
        textPaint.setColor(Color.parseColor("#FFB33C"));
        canvas.drawText("×" + streak + " 连杀", cx, dp(84), textPaint);
        float barW = dp(96) * remain;
        paint.setColor(Color.parseColor("#FFB33C"));
        canvas.drawRoundRect(cx - barW / 2f, dp(90), cx + barW / 2f, dp(94), dp(2), dp(2), paint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    /** 一局结束的结算面板。原版打完子弹后画面什么都没变，这是个死局。 */
    private void drawGameOver(Canvas canvas, float w, float h) {
        paint.setColor(Color.argb(190, 0, 0, 0));
        canvas.drawRect(0, 0, w, h, paint);
        float cx = w / 2f;
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.parseColor("#FF6B5E"));
        textPaint.setTextSize(dp(26));
        canvas.drawText("弹药耗尽", cx, h / 2f - dp(52), textPaint);
        textPaint.setColor(Color.parseColor("#E6E8EF"));
        textPaint.setTextSize(dp(15));
        canvas.drawText(String.format(java.util.Locale.US, "得分 %d · 最高连杀 %d",
                world.score(), world.bestStreak()), cx, h / 2f - dp(14), textPaint);
        canvas.drawText(String.format(java.util.Locale.US, "坚持 %d 波 · 命中率 %.0f%%",
                world.wave(), world.accuracy() * 100f), cx, h / 2f + dp(10), textPaint);
        textPaint.setColor(Color.parseColor("#8C93A6"));
        textPaint.setTextSize(dp(13));
        canvas.drawText("点右下角重新开始", cx, h / 2f + dp(46), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawCrosshair(Canvas canvas, float cx, float cy) {
        float gap = dp(6);
        float len = dp(11);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.parseColor("#7CFFB2"));
        canvas.drawLine(cx - gap - len, cy, cx - gap, cy, paint);
        canvas.drawLine(cx + gap, cy, cx + gap + len, cy, paint);
        canvas.drawLine(cx, cy - gap - len, cx, cy - gap, paint);
        canvas.drawLine(cx, cy + gap, cx, cy + gap + len, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#7CFFB2"));
        canvas.drawCircle(cx, cy, dp(1.5f), paint);
    }

    private void drawAmmo(Canvas canvas, float w, float h) {
        float boxW = dp(140);
        float boxH = dp(56);
        rect.set(w - boxW - dp(16), h - boxH - dp(16), w - dp(16), h - dp(16));
        paint.setColor(Color.argb(140, 0, 0, 0));
        canvas.drawRoundRect(rect, dp(8), dp(8), paint);

        if (world.reloading()) {
            paint.setColor(Color.parseColor("#FFB33C"));
            float progress = world.reloadProgress();
            rect.set(rect.left + dp(12), rect.top + dp(34),
                    rect.left + dp(12) + (boxW - dp(24)) * progress, rect.top + dp(40));
            canvas.drawRoundRect(rect, dp(3), dp(3), paint);
            textPaint.setTextSize(dp(14));
            textPaint.setColor(Color.parseColor("#FFB33C"));
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("换弹中…", w - boxW, h - boxH / 2f + dp(14), textPaint);
            return;
        }

        textPaint.setTextSize(dp(14));
        textPaint.setColor(Color.parseColor("#E6E8EF"));
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("弹匣", w - boxW, h - dp(38), textPaint);

        textPaint.setTextSize(dp(24));
        textPaint.setColor(world.ammo() <= 5 ? Color.parseColor("#FF6B5E")
                : Color.parseColor("#7CFFB2"));
        canvas.drawText(String.valueOf(world.ammo()), w - boxW, h - dp(12), textPaint);
        textPaint.setTextSize(dp(14));
        textPaint.setColor(Color.parseColor("#8C93A6"));
        canvas.drawText("/ " + world.reserveAmmo(), w - boxW + dp(34), h - dp(14), textPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
