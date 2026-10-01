package com.icewocker.gunfire.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.icewocker.gunfire.engine.Vec3;

import java.util.Locale;

/**
 * 覆盖在 GL 表面之上的 HUD。
 *
 * 用普通 Canvas 实现——准星、血条、小地图都不是 3D 内容，
 * 硬塞进 GL 只会增加着色器复杂度，反而更慢。
 *
 * 所有元素都是程序绘制的：没有一张 PNG，配色只有下面这一组常量，
 * 「精美」靠的是统一的色板和对齐，而不是素材堆叠。
 */
public final class HudView extends View {

    // 一套克制的霓虹色板：深底 + 三种状态色，不引入第四种
    private static final int COL_ACCENT = 0xFF7CFFB2;   // 主色：玩家、准星
    private static final int COL_WARN = 0xFFFFB33C;     // 警示：连杀、换弹
    private static final int COL_DANGER = 0xFFFF6B5E;   // 危险：低血、受击
    private static final int COL_ARMOR = 0xFF5EC8FF;    // 护甲
    private static final int COL_TEXT = 0xFFE6E8EF;
    private static final int COL_MUTED = 0xFF8C93A6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private GameWorld world;
    private TouchControls controls;
    private float fps;
    private float time;

    /** 命中标记（准星短暂张开）与方向受击指示的剩余时间。 */
    private float hitMarker;
    private float introTime = 2.4f;

    public HudView(Context context) {
        super(context);
        init();
    }

    public HudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        textPaint.setColor(COL_TEXT);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        glowPaint.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
    }

    public void bind(GameWorld world) {
        this.world = world;
    }

    public void bindControls(TouchControls controls) {
        this.controls = controls;
    }

    public void setFps(float fps) {
        this.fps = fps;
    }

    /** 由触控层在开火命中时调用，让准星闪一下。 */
    public void pingHitMarker() {
        hitMarker = 0.16f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // HUD 不消费触摸，事件应继续下发给 GLSurfaceView。
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        time += 1f / 60f;
        hitMarker = Math.max(0f, hitMarker - 1f / 60f);
        introTime = Math.max(0f, introTime - 1f / 60f);

        if (world == null) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(14));
            textPaint.setColor(COL_MUTED);
            canvas.drawText("载入中…", cx, cy, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        drawDamageVignette(canvas, w, h);
        drawHitDirection(canvas, cx, cy);

        if (!world.paused() && !world.gameOver()) {
            drawCrosshair(canvas, cx, cy);
            drawHitMarkers(canvas, cx, cy);
        }
        drawFloatTexts(canvas, w, h);
        drawMiniMap(canvas, w, h);
        drawTopBar(canvas, w, cx);
        drawHealthBar(canvas, w, h);
        drawAmmo(canvas, w, h);
        drawStreak(canvas, cx);

        if (world.gameOver()) {
            drawGameOver(canvas, w, h);
        } else if (world.paused()) {
            drawPause(canvas, w, h);
        } else if (introTime > 0f) {
            drawIntro(canvas, w, h);
        }
    }

    // ---- 受击反馈 ----

    /** 受伤时屏幕边缘泛红，血量越低常态红边越明显。 */
    private void drawDamageVignette(Canvas canvas, float w, float h) {
        float damage = world.hitFlashScreen();
        float lowHp = 1f - world.healthFraction();
        float strength = Math.max(damage, lowHp > 0.55f ? (lowHp - 0.55f) * 0.9f : 0f);
        if (strength <= 0.01f) {
            return;
        }
        int alpha = (int) (GameWorld.clamp(strength, 0f, 1f) * 170);
        paint.setShader(new LinearGradient(0, h, 0, h * 0.35f,
                new int[]{Color.argb(alpha, 255, 30, 30), Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h * 0.35f,
                new int[]{Color.argb(alpha, 255, 30, 30), Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    /** 屏幕中央一圈弧，指出伤害来自哪个方向。 */
    private void drawHitDirection(Canvas canvas, float cx, float cy) {
        float strength = world.hitFlashScreen();
        if (strength <= 0.02f) {
            return;
        }
        float radius = Math.min(getWidth(), getHeight()) * 0.22f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setColor(Color.argb((int) (strength * 200), 255, 90, 70));
        // 世界角度 -> 屏幕角度：视角正前方为 -90°（屏幕上方）
        float angle = world.hitAngle();
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(rect, (float) Math.toDegrees(angle) - 90f - 22f, 44f, false, paint);
    }

    // ---- 准星 ----

    private void drawCrosshair(Canvas canvas, float cx, float cy) {
        float heat = world.spreadHeat();
        float gap = dp(6) + heat * dp(16);
        float len = dp(11);
        float alpha = world.aiming() ? 1f : 0.85f;

        if (world.aiming()) {
            // 开镜：一圈细环 + 十字刻度
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(COL_ACCENT);
            canvas.drawCircle(cx, cy, dp(52), paint);
            canvas.drawCircle(cx, cy, dp(48), paint);
            paint.setStrokeWidth(dp(2));
            for (int i = 0; i < 4; i++) {
                float a = (float) (i * Math.PI / 2);
                canvas.drawLine(cx + (float) Math.cos(a) * dp(44),
                        cy + (float) Math.sin(a) * dp(44),
                        cx + (float) Math.cos(a) * dp(52),
                        cy + (float) Math.sin(a) * dp(52), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(COL_ACCENT);
        paint.setAlpha((int) (alpha * 255));
        canvas.drawLine(cx - gap - len, cy, cx - gap, cy, paint);
        canvas.drawLine(cx + gap, cy, cx + gap + len, cy, paint);
        canvas.drawLine(cx, cy - gap - len, cx, cy - gap, paint);
        canvas.drawLine(cx, cy + gap, cx, cy + gap + len, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_ACCENT);
        canvas.drawCircle(cx, cy, dp(1.6f), paint);
        paint.setAlpha(255);

        // 换弹进度：准星下方一条细线，不挡视野
        if (world.reloading()) {
            float progress = world.reloadProgress();
            paint.setColor(COL_WARN);
            rect.set(cx - dp(30), cy + dp(30), cx - dp(30) + dp(60) * progress, cy + dp(34));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
        }

        // 开火时的散布扩散环
        float flash = world.muzzleFlash();
        if (flash > 0.01f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb((int) (flash * 180), 255, 170, 60));
            canvas.drawCircle(cx, cy, dp(28) + flash * dp(14), paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /** 命中时四道斜线由内向外张开。 */
    private void drawHitMarkers(Canvas canvas, float cx, float cy) {
        float life = hitMarker;
        if (life <= 0f) {
            return;
        }
        float t = 1f - life / 0.16f;
        float inner = dp(7) + t * dp(3);
        float outer = inner + dp(6);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.argb((int) ((1f - t) * 230), 255, 255, 255));
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                canvas.drawLine(cx + sx * inner, cy + sy * inner,
                        cx + sx * outer, cy + sy * outer, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    // ---- 世界锚定的飘字 ----

    /**
     * 伤害数字直接画在 HUD 上、位置由世界坐标投影而来。
     * 投影逻辑和渲染器共用同一套相机参数，否则数字会飘到目标旁边去。
     */
    private void drawFloatTexts(Canvas canvas, float w, float h) {
        java.util.List<GameWorld.FloatText> texts = world.floatTexts();
        if (texts.isEmpty()) {
            return;
        }
        Vec3 eye = world.camera().eye();
        Vec3 fwd = world.camera().forward();
        Vec3 right = world.camera().right();
        Vec3 up = right.cross(fwd).scale(-1f);
        float fovY = (float) Math.toRadians(70);
        float tanHalf = (float) Math.tan(fovY / 2f);
        float aspect = w / h;
        if (fovY <= 0f || tanHalf <= 0f || Float.isNaN(aspect)) {
            return;
        }

        textPaint.setTextAlign(Paint.Align.CENTER);
        for (GameWorld.FloatText t : texts) {
            Vec3 rel = t.world.sub(eye);
            float depth = rel.dot(fwd);
            if (depth < 0.4f) {
                continue;
            }
            float sx = rel.dot(right) / (depth * tanHalf * aspect);
            float sy = rel.dot(up) / (depth * tanHalf);
            if (Math.abs(sx) > 1.6f || Math.abs(sy) > 1.6f) {
                continue;
            }
            float px = w / 2f + sx * w / 2f;
            float py = h / 2f - sy * h / 2f;
            float size = GameWorld.clamp(dp(15) * (8f / depth + 0.5f), dp(11), dp(26));
            textPaint.setTextSize(size);
            textPaint.setColor(colorWithAlpha(t.r, t.g, t.b, t.alpha()));
            // 描一层黑边，避免亮背景上读不出来
            textPaint.setStyle(Paint.Style.STROKE);
            textPaint.setStrokeWidth(dp(2.5f));
            int prev = textPaint.getColor();
            textPaint.setColor(colorWithAlpha(0f, 0f, 0f, t.alpha() * 0.6f));
            canvas.drawText(t.text, px, py, textPaint);
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(prev);
            canvas.drawText(t.text, px, py, textPaint);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    // ---- 小地图 ----

    /** 右上角俯视小地图：墙是灰块，敌人是红点，补给是绿点，玩家是朝向三角。 */
    private void drawMiniMap(Canvas canvas, float w, float h) {
        float size = dp(96);
        float left = w - size - dp(16);
        float top = dp(56);
        rect.set(left, top, left + size, top + size);
        paint.setColor(Color.argb(150, 8, 10, 14));
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(90, 124, 255, 178));
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);
        paint.setStyle(Paint.Style.FILL);

        float scale = size / 52f; // 世界 ±26 -> 地图尺寸
        float cxm = left + size / 2f;
        float cym = top + size / 2f;

        paint.setColor(Color.argb(120, 120, 128, 145));
        for (GameWorld.Box b : world.boxes()) {
            if (b.halfExtents.y > 1.5f && b.center.y > 2f) {
                continue; // 外墙不画，只画内部结构
            }
            float bx = cxm + b.center.x * scale;
            float bz = cym + b.center.z * scale;
            float bw = b.halfExtents.x * scale;
            float bh = b.halfExtents.z * scale;
            canvas.drawRect(bx - bw, bz - bh, bx + bw, bz + bh, paint);
        }

        for (GameWorld.Pickup p : world.pickups()) {
            paint.setColor(p.ammo ? COL_ARMOR : COL_ACCENT);
            canvas.drawCircle(cxm + p.position.x * scale, cym + p.position.z * scale,
                    dp(2.5f), paint);
        }

        for (GameWorld.Enemy e : world.enemies()) {
            if (!e.alive) {
                continue;
            }
            paint.setColor(e.elite ? COL_WARN : COL_DANGER);
            canvas.drawCircle(cxm + e.position.x * scale, cym + e.position.z * scale,
                    e.elite ? dp(4f) : dp(3f), paint);
        }

        // 玩家：一个朝向视角的三角形
        float px = cxm + world.camera().position.x * scale;
        float py = cym + world.camera().position.z * scale;
        float yaw = world.camera().yaw;
        float fx = (float) Math.sin(yaw);
        float fz = -(float) Math.cos(yaw);
        path.reset();
        path.moveTo(px + fx * dp(6), py + fz * dp(6));
        path.lineTo(px - fz * dp(4) - fx * dp(3), py + fx * dp(4) - fz * dp(3));
        path.lineTo(px + fz * dp(4) - fx * dp(3), py - fx * dp(4) - fz * dp(3));
        path.close();
        paint.setColor(COL_ACCENT);
        canvas.drawPath(path, paint);
    }

    // ---- 血条 / 弹药 / 顶部信息 ----

    private void drawTopBar(Canvas canvas, float w, float cx) {
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(13));
        textPaint.setColor(COL_TEXT);
        canvas.drawText("第 " + world.wave() + " 波 · 剩余 " + world.aliveEnemies(), cx, dp(26), textPaint);
        textPaint.setColor(COL_MUTED);
        textPaint.setTextSize(dp(11));
        canvas.drawText(String.format(Locale.US, "%.0f FPS · %d 击杀 · 爆头 %d",
                fps, world.kills(), world.headshots()), cx, dp(44), textPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(dp(13));
        textPaint.setColor(COL_TEXT);
        canvas.drawText("得分 " + world.score(), dp(16), dp(26), textPaint);
        textPaint.setTextSize(dp(11));
        textPaint.setColor(COL_MUTED);
        canvas.drawText(String.format(Locale.US, "命中率 %.0f%%", world.accuracy() * 100f),
                dp(16), dp(44), textPaint);
        long sec = world.elapsedSeconds();
        canvas.drawText(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60), dp(16), dp(62), textPaint);
    }

    /** 左下角血条 + 护甲条，用双色条而不是数字，扫一眼就知道还剩多少。 */
    private void drawHealthBar(Canvas canvas, float w, float h) {
        float left = dp(20);
        float barW = dp(170);
        float bottom = h - dp(28);

        paint.setColor(Color.argb(120, 0, 0, 0));
        rect.set(left - dp(6), bottom - dp(38), left + barW + dp(6), bottom + dp(10));
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);

        // 血量
        rect.set(left, bottom - dp(16), left + barW, bottom - dp(4));
        paint.setColor(Color.argb(90, 255, 255, 255));
        canvas.drawRoundRect(rect, dp(4), dp(4), paint);
        float hp = world.healthFraction();
        paint.setColor(hp < 0.3f ? COL_DANGER : COL_ACCENT);
        rect.set(left, bottom - dp(16), left + barW * hp, bottom - dp(4));
        canvas.drawRoundRect(rect, dp(4), dp(4), paint);

        // 护甲
        float ap = world.armorFraction();
        if (ap > 0.001f) {
            rect.set(left, bottom, left + barW, bottom + dp(8));
            paint.setColor(Color.argb(90, 255, 255, 255));
            canvas.drawRoundRect(rect, dp(3), dp(3), paint);
            paint.setColor(COL_ARMOR);
            rect.set(left, bottom, left + barW * ap, bottom + dp(8));
            canvas.drawRoundRect(rect, dp(3), dp(3), paint);
        }

        textPaint.setTextSize(dp(13));
        textPaint.setColor(COL_TEXT);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("HP " + (int) Math.ceil(world.health()), left, bottom - dp(20), textPaint);
        if (hp < 0.35f) {
            // 低血脉动提示——纯排版层面的信号，不需要贴图
            float pulse = 0.5f + 0.5f * (float) Math.sin(time * 8f);
            textPaint.setColor(colorWithAlpha(1f, 0.2f, 0.2f, 0.35f + pulse * 0.65f));
            canvas.drawText("HP " + (int) Math.ceil(world.health()), left, bottom - dp(20), textPaint);
            textPaint.setColor(COL_TEXT);
        }
        if (ap > 0.001f) {
            textPaint.setTextSize(dp(11));
            textPaint.setColor(COL_ARMOR);
            canvas.drawText("ARMOR " + (int) Math.ceil(world.armor()), left + barW - dp(64),
                    bottom + dp(8), textPaint);
        }

        // 蹲下 / 开镜状态
        textPaint.setTextSize(dp(11));
        textPaint.setColor(COL_MUTED);
        StringBuilder sb = new StringBuilder();
        if (world.crouching()) {
            sb.append("蹲下  ");
        }
        if (world.aiming()) {
            sb.append("开镜");
        }
        if (sb.length() > 0) {
            canvas.drawText(sb.toString().trim(), left, bottom - dp(32), textPaint);
        }
    }

    /** 右下角弹药块：大号弹匣数 + 备弹 + 换弹进度。 */
    private void drawAmmo(Canvas canvas, float w, float h) {
        float boxW = dp(150);
        float boxH = dp(58);
        rect.set(w - boxW - dp(16), h - boxH - dp(16), w - dp(16), h - dp(16));
        paint.setColor(Color.argb(140, 0, 0, 0));
        canvas.drawRoundRect(rect, dp(8), dp(8), paint);

        if (world.reloading()) {
            float progress = world.reloadProgress();
            paint.setColor(COL_WARN);
            rect.set(rect.left + dp(12), rect.top + dp(38),
                    rect.left + dp(12) + (boxW - dp(24)) * progress, rect.top + dp(44));
            canvas.drawRoundRect(rect, dp(3), dp(3), paint);
            textPaint.setTextSize(dp(13));
            textPaint.setColor(COL_WARN);
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("换弹中…", w - boxW, h - boxH / 2f + dp(6), textPaint);
            return;
        }

        int mag = world.magazineSize();
        textPaint.setTextSize(dp(13));
        textPaint.setColor(COL_MUTED);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(world.weapon().name, w - boxW, h - dp(40), textPaint);

        textPaint.setTextSize(dp(26));
        boolean low = world.ammo() <= Math.max(2, mag / 5);
        textPaint.setColor(low ? COL_DANGER : COL_ACCENT);
        canvas.drawText(String.valueOf(world.ammo()), w - boxW, h - dp(12), textPaint);
        textPaint.setTextSize(dp(14));
        textPaint.setColor(COL_MUTED);
        canvas.drawText("/ " + world.reserveAmmo(), w - boxW + dp(42), h - dp(14), textPaint);

        // 弹匣余量画成小格，比数字更直观
        float segW = (boxW - dp(24)) / mag;
        for (int i = 0; i < mag; i++) {
            rect.set(w - boxW + dp(12) + i * segW, h - dp(34),
                    w - boxW + dp(12) + i * segW + segW * 0.6f, h - dp(30));
            paint.setColor(i < world.ammo() ? COL_ACCENT : Color.argb(70, 255, 255, 255));
            canvas.drawRect(rect, paint);
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
        textPaint.setTextSize(dp(20));
        textPaint.setColor(COL_WARN);
        canvas.drawText("×" + streak + " 连杀", cx, dp(84), textPaint);
        float barW = dp(110) * remain;
        paint.setColor(COL_WARN);
        canvas.drawRoundRect(cx - barW / 2f, dp(90), cx + barW / 2f, dp(94), dp(2), dp(2), paint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    // ---- 覆盖面板 ----

    /** 开场 2.4 秒的标题展示，让第一帧不是硬切进战场。 */
    private void drawIntro(Canvas canvas, float w, float h) {
        float t = 1f - introTime / 2.4f;
        float alpha = introTime > 1.6f ? 1f : GameWorld.clamp(introTime / 1.6f, 0f, 1f);
        float rise = (1f - GameWorld.clamp(t * 3f, 0f, 1f)) * dp(16);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(44));
        textPaint.setColor(colorWithAlpha(0.49f, 1f, 0.70f, alpha));
        canvas.drawText("GUNFIRE", w / 2f, h / 2f - dp(6) + rise, textPaint);
        textPaint.setTextSize(dp(13));
        textPaint.setColor(colorWithAlpha(0.55f, 0.58f, 0.65f, alpha));
        canvas.drawText("左半屏移动 · 右半屏转视角 · 右下角开火", w / 2f, h / 2f + dp(24) + rise,
                textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawPause(Canvas canvas, float w, float h) {
        paint.setColor(Color.argb(200, 0, 0, 0));
        canvas.drawRect(0, 0, w, h, paint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(30));
        textPaint.setColor(COL_TEXT);
        canvas.drawText("已暂停", w / 2f, h / 2f - dp(20), textPaint);
        textPaint.setTextSize(dp(13));
        textPaint.setColor(COL_MUTED);
        canvas.drawText("点左上角继续 · 得分 " + world.score(), w / 2f, h / 2f + dp(14), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGameOver(Canvas canvas, float w, float h) {
        paint.setColor(Color.argb(205, 4, 6, 10));
        canvas.drawRect(0, 0, w, h, paint);
        float cx = w / 2f;

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(26));
        textPaint.setColor(COL_DANGER);
        canvas.drawText("阵亡", cx, h / 2f - dp(78), textPaint);

        // 评级：一个大字母 + 一圈环，比一串数字更有结算感
        float ringR = dp(30);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(rankColor(world.rank()));
        canvas.drawCircle(cx, h / 2f - dp(20), ringR, paint);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(dp(34));
        textPaint.setColor(rankColor(world.rank()));
        canvas.drawText(String.valueOf(world.rank()), cx, h / 2f - dp(8), textPaint);

        textPaint.setTextSize(dp(14));
        textPaint.setColor(COL_TEXT);
        canvas.drawText(String.format(Locale.US, "得分 %d · 坚持 %d 波", world.score(), world.wave()),
                cx, h / 2f + dp(30), textPaint);
        textPaint.setColor(COL_MUTED);
        textPaint.setTextSize(dp(12));
        canvas.drawText(String.format(Locale.US, "击杀 %d · 爆头 %d · 最高连杀 %d · 命中率 %.0f%%",
                        world.kills(), world.headshots(), world.bestStreak(), world.accuracy() * 100f),
                cx, h / 2f + dp(52), textPaint);
        canvas.drawText("历史最高波次 " + world.bestWaveEver(), cx, h / 2f + dp(72), textPaint);

        textPaint.setColor(COL_ACCENT);
        textPaint.setTextSize(dp(13));
        canvas.drawText("点右下角再来一局", cx, h / 2f + dp(104), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private static int rankColor(char rank) {
        switch (rank) {
            case 'S':
                return 0xFFFFD75E;
            case 'A':
                return COL_ACCENT;
            case 'B':
                return COL_ARMOR;
            case 'C':
                return COL_WARN;
            default:
                return COL_DANGER;
        }
    }

    private static int colorWithAlpha(float r, float g, float b, float a) {
        return Color.argb((int) (GameWorld.clamp(a, 0f, 1f) * 255),
                (int) (GameWorld.clamp(r, 0f, 1f) * 255),
                (int) (GameWorld.clamp(g, 0f, 1f) * 255),
                (int) (GameWorld.clamp(b, 0f, 1f) * 255));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
