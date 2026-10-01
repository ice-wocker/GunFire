package com.icewocker.gunfire.game;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import com.icewocker.gunfire.engine.Vec3;

/**
 * 触屏操作：左半屏虚拟摇杆移动，右半屏拖动转视角，右下角开火/换弹。
 *
 * 姿态都是「按下时记录原点、移动时算增量」，这对 FPS 是标准做法——
 * 绝对定位会让人手指一放就开始乱转。
 */
public final class TouchControls implements View.OnTouchListener {

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_LOOK = 2;
    private static final int MODE_FIRE = 3;

    private final GameWorld world;

    private int movePointer = -1;
    private int lookPointer = -1;
    private int firePointer = -1;

    private float moveOriginX;
    private float moveOriginY;
    private float moveDx;
    private float moveDy;

    private float lastLookX;
    private float lastLookY;
    private float lookSensitivity = 1f;

    private float deadZonePx = 12f;
    private float fireStartY;
    private float fireCooldown;

    /** 射速：约 10 发/秒，和 30 发弹匣配起来一梭子 3 秒。 */
    private static final float FIRE_INTERVAL = 0.1f;

    public TouchControls(Context context, GameWorld world) {
        this.world = world;
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
                if (isFireZone(v, x, y)) {
                    firePointer = id;
                    fireStartY = y;
                    if (world.gameOver()) {
                        world.restart();
                    } else {
                        world.fire();
                    }
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
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    float x = event.getX(i);
                    float y = event.getY(i);
                    if (id == movePointer) {
                        float maxDrag = 96f * v.getResources().getDisplayMetrics().density;
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
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                release(id);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                movePointer = -1;
                lookPointer = -1;
                firePointer = -1;
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
    }

    private boolean isFireZone(View v, float x, float y) {
        float density = v.getResources().getDisplayMetrics().density;
        return x > v.getWidth() - 150 * density && y > v.getHeight() - 150 * density;
    }

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
        if (firePointer < 0 || world.gameOver()) {
            return;
        }
        fireCooldown -= dt;
        if (fireCooldown <= 0f) {
            if (world.fire()) {
                fireCooldown = FIRE_INTERVAL;
            } else {
                // 换弹或空仓时不要空转：等装填完再继续
                fireCooldown = 0.1f;
            }
        }
    }

    private static float clampDelta(float v, float max) {
        return GameWorld.clamp(v, -max, max);
    }

    /** 右摇杆正前方向，HUD 可用来画摇杆位置。 */
    public Vec3 moveVector() {
        return Vec3.of(moveDx, moveDy, 0f);
    }

    public boolean hasMoveInput() {
        return movePointer >= 0;
    }

    public int modeCount() {
        return (movePointer >= 0 ? 1 : 0) + (lookPointer >= 0 ? 1 : 0)
                + (firePointer >= 0 ? 1 : 0);
    }

    static {
        // 占位：确保 MODE_* 常量语义被 IDE 识别，实际按 pointer 槽位区分。
        assert MODE_NONE + MODE_MOVE + MODE_LOOK + MODE_FIRE == 6;
    }
}
