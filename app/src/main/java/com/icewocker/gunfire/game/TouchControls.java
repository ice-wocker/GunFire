package com.icewocker.gunfire.game;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import com.icewocker.gunfire.engine.Vec3;

/**
 * 触屏操作布局。
 *
 * <pre>
 *   ┌──────────────────────────────┬───────────────────────┐
 *   │                              │  ⏸ 暂停（左上角贴右） │
 *   │        移动摇杆区            │       视角拖动区      │
 *   │      （左半屏）              │      （右半屏）       │
 *   │                              │  [镜] [蹲] [跳]       │
 *   │                              │      [ 开火 ]         │
 *   └──────────────────────────────┴───────────────────────┘
 * </pre>
 *
 * 姿态都是「按下时记录原点、移动时算增量」，这对 FPS 是标准做法——
 * 绝对定位会让人手指一放就开始乱转。
 */
public final class TouchControls implements View.OnTouchListener {

    private final GameWorld world;
    private final HudView hud;

    private int movePointer = -1;
    private int lookPointer = -1;
    private int firePointer = -1;
    private int aimPointer = -1;

    private float moveOriginX;
    private float moveOriginY;
    private float moveDx;
    private float moveDy;

    private float lastLookX;
    private float lastLookY;
    private float lookSensitivity = 1f;

    private final float deadZonePx;
    private float fireCooldown;
    private boolean crouchLatched;

    /** 头顶按钮的命中半径（dp）。 */
    private static final float BTN_RADIUS_DP = 30f;

    public TouchControls(Context context, GameWorld world, HudView hud) {
        this.world = world;
        this.hud = hud;
        this.deadZonePx = 12f * context.getResources().getDisplayMetrics().density;
    }

    public void setLookSensitivity(float value) {
        this.lookSensitivity = value;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                float x = event.getX(index);
                float y = event.getY(index);

                if (inFireZone(v, x, y)) {
                    firePointer = id;
                    if (world.gameOver()) {
                        world.restart();
                        // 重开也要把触控层的暂存状态清掉，否则会带着上一局的蹲下/开镜状态进新局
                        crouchLatched = false;
                        aimPointer = -1;
                    } else if (world.paused()) {
                        world.setPaused(false);
                    } else {
                        fire();
                    }
                } else if (inPauseZone(v, x, y)) {
                    world.togglePause();
                } else if (inAimZone(v, x, y)) {
                    aimPointer = id;
                    world.setAiming(!world.aiming());
                } else if (inCrouchZone(v, x, y)) {
                    // 以世界的状态为准来翻转，不要用本地 latch：
                    // restart() 会把 crouching 复位，本地 latch 却还记着，
                    // 于是按一下「蹲下」反而会先站起来。
                    crouchLatched = !world.crouching();
                    world.setCrouching(crouchLatched);
                } else if (inJumpZone(v, x, y)) {
                    world.jump();
                } else if (x < v.getWidth() / 2f && movePointer < 0) {
                    movePointer = id;
                    moveOriginX = x;
                    moveOriginY = y;
                    moveDx = 0f;
                    moveDy = 0f;
                } else if (lookPointer < 0) {
                    lookPointer = id;
                    lastLookX = x;
                    lastLookY = y;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float maxDrag = 96f * v.getResources().getDisplayMetrics().density;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    float x = event.getX(i);
                    float y = event.getY(i);
                    if (id == movePointer) {
                        moveDx = clampDelta(x - moveOriginX, maxDrag);
                        moveDy = clampDelta(y - moveOriginY, maxDrag);
                    } else if (id == lookPointer) {
                        world.look((x - lastLookX) * lookSensitivity * 14f,
                                (y - lastLookY) * lookSensitivity * 14f);
                        lastLookX = x;
                        lastLookY = y;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                release(event.getPointerId(event.getActionIndex()));
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                movePointer = -1;
                lookPointer = -1;
                firePointer = -1;
                aimPointer = -1;
                moveDx = 0f;
                moveDy = 0f;
                world.setAiming(false);
                return true;
            }
            default:
                return false;
        }
    }

    private void release(int id) {
        if (id == movePointer) {
            movePointer = -1;
            moveDx = 0f;
            moveDy = 0f;
        }
        if (id == lookPointer) {
            lookPointer = -1;
        }
        if (id == firePointer) {
            firePointer = -1;
            fireCooldown = 0f;
        }
        if (id == aimPointer) {
            aimPointer = -1;
        }
    }

    /** 真正打一发，并把命中告诉 HUD 触发准星反馈。 */
    private void fire() {
        int before = world.shotsHit();
        if (world.fire() && world.shotsHit() > before && hud != null) {
            hud.pingHitMarker();
        }
    }

    // ---- 按钮热区 ----

    private boolean inFireZone(View v, float x, float y) {
        float d = v.getResources().getDisplayMetrics().density;
        return x > v.getWidth() - 140 * d && y > v.getHeight() - 140 * d;
    }

    private boolean inPauseZone(View v, float x, float y) {
        float d = v.getResources().getDisplayMetrics().density;
        float ax = 34 * d;
        float ay = 34 * d;
        return x > v.getWidth() - 90 * d && y < 70 * d
                || (Math.abs(x - ax) < 22 * d && Math.abs(y - ay) < 22 * d);
    }

    private boolean inAimZone(View v, float x, float y) {
        return nearButton(v, x, y, -1, 1);
    }

    private boolean inCrouchZone(View v, float x, float y) {
        return nearButton(v, x, y, -1, 2);
    }

    private boolean inJumpZone(View v, float x, float y) {
        return nearButton(v, x, y, -1, 3);
    }

    /**
     * 功能按钮排在开火键左边的一竖列，从下往上依次是 镜 / 蹲 / 跳。
     * index 从 1 开始，0 预留给开火键。
     */
    private boolean nearButton(View v, float x, float y, int col, int index) {
        float d = v.getResources().getDisplayMetrics().density;
        float bx = v.getWidth() - (index == 1 ? 190 : 130) * d;
        float by = v.getHeight() - (40 + (index - 1) * 76) * d;
        return Math.hypot(x - bx, y - by) < BTN_RADIUS_DP * d + 8 * d;
    }

    // ---- 每帧 ----

    /** 每帧读一次：先处理按住连射，再把摇杆状态喂给世界。 */
    public void applyMovement(float dt) {
        tickAutoFire(dt);
        if (movePointer < 0) {
            return;
        }
        float dz = deadZonePx;
        float ax = Math.abs(moveDx) < dz ? 0f : moveDx;
        float ay = Math.abs(moveDy) < dz ? 0f : moveDy;
        float max = 96f * 3f;
        float forward = GameWorld.clamp(-ay / max, -1f, 1f);
        float strafe = GameWorld.clamp(ax / max, -1f, 1f);
        world.move(forward, strafe, dt);
    }

    /**
     * 按住开火键持续射击。
     *
     * 原来每次 ACTION_DOWN 只 fire 一次，玩家想连射就得疯狂戳屏幕——
     * 手感上的"卡顿"多半来自这里。改成按住后按武器射速匀速出膛。
     */
    private void tickAutoFire(float dt) {
        if (firePointer < 0 || world.gameOver() || world.paused()) {
            return;
        }
        fireCooldown -= dt;
        if (fireCooldown <= 0f) {
            if (world.reloading()) {
                fireCooldown = 0.1f;
                return;
            }
            fire();
            // 空仓时下一帧会触发 reload，等装填完再继续
            fireCooldown = world.ammo() > 0 ? world.weapon().interval : 0.1f;
        }
    }

    private static float clampDelta(float v, float max) {
        return GameWorld.clamp(v, -max, max);
    }

    /** 摇杆状态，HUD 可用来画摇杆位置。 */
    public Vec3 moveVector() {
        return Vec3.of(moveDx, moveDy, 0f);
    }

    public boolean hasMoveInput() {
        return movePointer >= 0;
    }

    /** 当前按下的功能键数量，用于 HUD 显示提示。 */
    public int modeCount() {
        return (movePointer >= 0 ? 1 : 0) + (lookPointer >= 0 ? 1 : 0)
                + (firePointer >= 0 ? 1 : 0) + (aimPointer >= 0 ? 1 : 0);
    }
}
