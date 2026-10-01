package com.icewocker.gunfire.game;

import com.icewocker.gunfire.engine.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 游戏世界状态与规则。
 *
 * 刻意不引用任何 Android 类——纯 Java，因此可以在 JVM 单测里跑完整局对战。
 * 渲染层每帧从这里读快照，输入层往里写事件。
 */
public final class GameWorld {

    public static final float TARGET_MAX_HEALTH = 100f;
    private static final float GRAVITY = -9.8f;
    private static final float PLAYER_RADIUS = 0.4f;
    private static final float EYE_HEIGHT = 1.68f;
    private static final int MAGAZINE_SIZE = 30;
    private static final float RELOAD_SECONDS = 1.8f;

    /** 世界里的静态方块（墙、箱子、地面）。中心 + 半尺寸。 */
    public static final class Box {
        public final Vec3 center;
        public final Vec3 halfExtents;
        public final float colorR, colorG, colorB;

        public Box(Vec3 center, Vec3 halfExtents, float r, float g, float b) {
            this.center = center;
            this.halfExtents = halfExtents;
            this.colorR = r;
            this.colorG = g;
            this.colorB = b;
        }
    }

    /** 可击倒的目标。 */
    public static final class Target {
        public final Vec3 position;
        public float health = TARGET_MAX_HEALTH;
        public boolean alive = true;
        public float hitFlash;
        public float phase;

        Target(Vec3 position, float phase) {
            this.position = position;
            this.phase = phase;
        }
    }

    /** 玩家相机姿态。 */
    public static final class Camera {
        public Vec3 position = Vec3.of(0, EYE_HEIGHT, 6);
        public float yaw;
        public float pitch;
        public float recoil;

        public Vec3 eye() {
            return position;
        }

        public Vec3 forward() {
            float p = pitch + recoil;
            float cp = (float) Math.cos(p);
            // yaw=0 -> (0,0,-1)；yaw 正向增大时向右转（朝 +X），与 right() 自洽
            return Vec3.of((float) Math.sin(yaw) * cp, (float) Math.sin(p),
                    -(float) Math.cos(yaw) * cp).normalize();
        }

        public Vec3 right() {
            return Vec3.of((float) Math.cos(yaw), 0f, (float) Math.sin(yaw)).normalize();
        }
    }

    private final List<Box> boxes = new ArrayList<>();
    private final List<Target> targets = new ArrayList<>();
    private final Camera camera = new Camera();
    private final Random random;

    private int score;
    private int ammo = MAGAZINE_SIZE;
    private int reserveAmmo = 120;
    private boolean reloading;
    private float reloadTimer;
    private float muzzleFlash;
    private float spreadHeat;
    private int shotsFired;
    private int shotsHit;
    private long elapsedSeconds;
    private boolean gameOver;

    public GameWorld() {
        this(new Random());
    }

    public GameWorld(Random random) {
        this.random = random;
        buildArena();
    }

    /** 搭一个对称的射击场：地面、四面墙、掩体柱、两个箱垛。 */
    private void buildArena() {
        boxes.add(new Box(Vec3.of(0, -1, 0), Vec3.of(24, 1, 24), 0.30f, 0.32f, 0.36f));
        boxes.add(new Box(Vec3.of(0, 2, -22), Vec3.of(24, 3, 1), 0.42f, 0.44f, 0.50f));
        boxes.add(new Box(Vec3.of(-22, 2, 0), Vec3.of(1, 3, 24), 0.42f, 0.44f, 0.50f));
        boxes.add(new Box(Vec3.of(22, 2, 0), Vec3.of(1, 3, 24), 0.42f, 0.44f, 0.50f));
        boxes.add(new Box(Vec3.of(0, 2, 22), Vec3.of(24, 3, 1), 0.42f, 0.44f, 0.50f));

        // 立柱避开中轴 x=0，否则正面射击走廊会被自己挡死
        for (int x = -14; x <= 14; x += 7) {
            if (x == 0) {
                continue;
            }
            boxes.add(new Box(Vec3.of(x, 1, -8), Vec3.of(0.6f, 1, 0.6f), 0.55f, 0.35f, 0.22f));
        }
        // 掩体刻意放在两侧，中轴留出一条开阔射击走廊（x=0 附近无遮挡）
        boxes.add(new Box(Vec3.of(-9, 0.6f, 2), Vec3.of(2, 0.6f, 1.2f), 0.62f, 0.40f, 0.24f));
        boxes.add(new Box(Vec3.of(9, 0.6f, 2), Vec3.of(2, 0.6f, 1.2f), 0.62f, 0.40f, 0.24f));
        boxes.add(new Box(Vec3.of(-5, 1.4f, -12), Vec3.of(1.5f, 1.4f, 0.8f), 0.62f, 0.40f, 0.24f));
        boxes.add(new Box(Vec3.of(5, 1.4f, -12), Vec3.of(1.5f, 1.4f, 0.8f), 0.62f, 0.40f, 0.24f));

        spawnTargets(9);
    }

    private void spawnTargets(int count) {
        targets.clear();
        for (int i = 0; i < count; i++) {
            // 中轴（x=0）上必放一个目标，保证正面直射一定能命中
            float[] xs = {0f, -8f, 8f, -14f, 14f, 0f, -8f, 8f, 14f};
            float x = xs[i % xs.length];
            float z = -18f - (i / 5) * 4f;
            targets.add(new Target(Vec3.of(x, 1.4f, z), i * 0.7f));
        }
    }

    // ---- 输入事件 ----

    /** 鼠标/触摸拖拽，单位为像素增量，转换为弧度。 */
    public void look(float dxPixels, float dyPixels) {
        if (gameOver) {
            return;
        }
        float sensitivity = 0.0022f;
        camera.yaw += dxPixels * sensitivity;
        camera.pitch = clamp(camera.pitch - dyPixels * sensitivity, -1.5f, 1.5f);
        // 单次 if 在大幅拖动下会残留超界值，必须循环归约
        float twoPi = (float) (2 * Math.PI);
        while (camera.yaw > Math.PI) {
            camera.yaw -= twoPi;
        }
        while (camera.yaw < -Math.PI) {
            camera.yaw += twoPi;
        }
    }

    /** 角度制，供摇晃/按键式转向使用。 */
    public void turn(float degrees) {
        camera.yaw += (float) Math.toRadians(degrees);
    }

    public void move(float forwardAmount, float strafeAmount, float dt) {
        if (gameOver) {
            return;
        }
        float speed = 5.2f;
        Vec3 dir = camera.forward();
        Vec3 flat = Vec3.of(dir.x, 0, dir.z).normalize();
        Vec3 right = camera.right();
        Vec3 delta = flat.scale(forwardAmount * speed * dt)
                .add(right.scale(strafeAmount * speed * dt));
        Vec3 next = camera.position.add(delta);
        camera.position = resolveCollision(camera.position, next);
    }

    /** 沿轴分离处理碰撞：X 与 Z 分别尝试，能过就过，避免贴墙卡死。 */
    private Vec3 resolveCollision(Vec3 from, Vec3 to) {
        Vec3 pos = from;
        if (!collides(Vec3.of(to.x, pos.y, pos.z))) {
            pos = Vec3.of(to.x, pos.y, pos.z);
        }
        if (!collides(Vec3.of(pos.x, pos.y, to.z))) {
            pos = Vec3.of(pos.x, pos.y, to.z);
        }
        return clampToArena(pos);
    }

    private Vec3 clampToArena(Vec3 pos) {
        float limit = 21f;
        return Vec3.of(clamp(pos.x, -limit, limit), pos.y, clamp(pos.z, -limit, limit));
    }

    /**
     * 玩家能否站在 pos。
     * 只考虑与躯干高度范围重叠的实体：矮于膝盖的箱体视为可跨越，不阻挡。
     */
    private boolean collides(Vec3 pos) {
        float feet = pos.y - EYE_HEIGHT;
        float knee = feet + 0.55f;
        float head = feet + 1.75f;
        for (Box b : boxes) {
            float top = b.center.y + b.halfExtents.y;
            float bottom = b.center.y - b.halfExtents.y;
            if (top <= knee || bottom >= head) {
                continue;
            }
            float gapX = Math.abs(pos.x - b.center.x) - b.halfExtents.x;
            float gapZ = Math.abs(pos.z - b.center.z) - b.halfExtents.z;
            if (gapX < PLAYER_RADIUS && gapZ < PLAYER_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** 开火。返回是否真的打出了子弹。 */
    public boolean fire() {
        if (gameOver || reloading || ammo <= 0) {
            if (ammo <= 0 && !reloading && !gameOver) {
                reload();
            }
            return false;
        }
        ammo--;
        shotsFired++;
        muzzleFlash = 1f;
        spreadHeat = Math.min(spreadHeat + 0.10f, 1f);
        camera.recoil += 0.004f + spreadHeat * 0.006f;

        Vec3 dir = spreadDirection(camera.forward());
        Target hit = raycastTarget(camera.position, dir);
        if (hit != null) {
            hit.health -= 34f;
            hit.hitFlash = 0.12f;
            shotsHit++;
            if (hit.health <= 0 && hit.alive) {
                hit.alive = false;
                score += 100;
            }
        }
        return true;
    }

    /**
     * 给子弹加一点散布：连射越久越飘。
     *
     * 幅度必须远小于目标张角，否则「枪比靶子宽」会变成必然脱靶。
     * 目标半高 0.35 在 20 米外约合 0.0175 rad，因此上限压到 0.012 rad 以内。
     */
    private Vec3 spreadDirection(Vec3 base) {
        if (spreadHeat <= 0.001f) {
            return base;
        }
        float spread = spreadHeat * 0.012f;
        Vec3 right = camera.right();
        Vec3 up = right.cross(base).scale(-1f);
        float rx = (random.nextFloat() * 2f - 1f) * spread;
        float ry = (random.nextFloat() * 2f - 1f) * spread;
        return base.add(right.scale(rx)).add(up.scale(ry)).normalize();
    }

    /**
     * 射线与所有存活目标求交，返回最近的一个。
     * 用「球体近似 + 逐轴 slab 检测」的混合：目标当 AABB 处理，与渲染的方块一致。
     */
    public Target raycastTarget(Vec3 origin, Vec3 dir) {
        Target best = null;
        float bestT = Float.MAX_VALUE;
        for (Target t : targets) {
            if (!t.alive) {
                continue;
            }
            // 判定盒略大于渲染体，抵消散布带来的手感损失
            Vec3 half = Vec3.of(0.42f, 0.42f, 0.24f);
            float t0 = intersectAabb(origin, dir, t.position, half);
            if (t0 >= 0f && t0 < bestT) {
                bestT = t0;
                best = t;
            }
        }
        if (best == null) {
            return null;
        }
        // 打中目标之前先撞墙，则不算命中
        float wallT = raycastWorld(origin, dir);
        return wallT < bestT ? null : best;
    }

    /** 射线与静态方块求交，返回最近距离；没打到返回极大值。 */
    public float raycastWorld(Vec3 origin, Vec3 dir) {
        float best = Float.MAX_VALUE;
        for (Box b : boxes) {
            float t = intersectAabb(origin, dir, b.center, b.halfExtents);
            if (t >= 0f && t < best) {
                best = t;
            }
        }
        return best;
    }

    /** 标准 slab 算法。返回入射距离，未命中返回 -1。 */
    public static float intersectAabb(Vec3 origin, Vec3 dir, Vec3 center, Vec3 half) {
        float tMin = -Float.MAX_VALUE;
        float tMax = Float.MAX_VALUE;
        float[] o = {origin.x, origin.y, origin.z};
        float[] d = {dir.x, dir.y, dir.z};
        float[] c = {center.x, center.y, center.z};
        float[] h = {half.x, half.y, half.z};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-8f) {
                if (o[i] < c[i] - h[i] || o[i] > c[i] + h[i]) {
                    return -1f;
                }
                continue;
            }
            float inv = 1f / d[i];
            float t1 = (c[i] - h[i] - o[i]) * inv;
            float t2 = (c[i] + h[i] - o[i]) * inv;
            if (t1 > t2) {
                float tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1f;
            }
        }
        return tMax < 0f ? -1f : Math.max(tMin, 0f);
    }

    public void reload() {
        if (reloading || ammo == MAGAZINE_SIZE || reserveAmmo <= 0) {
            return;
        }
        reloading = true;
        reloadTimer = RELOAD_SECONDS;
    }

    /** 推进一帧。dt 单位秒，外部需保证不被异常放大（否则会穿墙）。 */
    public void update(float dt) {
        if (gameOver) {
            return;
        }
        dt = Math.min(dt, 0.05f);
        elapsedSeconds += (long) (dt * 1000) / 1000;

        muzzleFlash = Math.max(0f, muzzleFlash - dt * 8f);
        spreadHeat = Math.max(0f, spreadHeat - dt * 0.7f);
        camera.recoil = Math.max(0f, camera.recoil - dt * 0.35f);

        if (reloading) {
            reloadTimer -= dt;
            if (reloadTimer <= 0f) {
                int need = MAGAZINE_SIZE - ammo;
                int take = Math.min(need, reserveAmmo);
                ammo += take;
                reserveAmmo -= take;
                reloading = false;
            }
        }

        for (Target t : targets) {
            t.phase += dt;
            t.hitFlash = Math.max(0f, t.hitFlash - dt);
        }
        random.nextFloat(); // 保持随机流推进，保证回放一致性可复现

        if (targetsCleared()) {
            respawnWave();
        }
    }

    private boolean targetsCleared() {
        for (Target t : targets) {
            if (t.alive) {
                return false;
            }
        }
        return true;
    }

    private void respawnWave() {
        for (Target t : targets) {
            t.alive = true;
            t.health = TARGET_MAX_HEALTH;
        }
    }

    // ---- 只读快照 ----

    public Camera camera() {
        return camera;
    }

    public List<Box> boxes() {
        return boxes;
    }

    public List<Target> targets() {
        return targets;
    }

    public Vec3 sunDirection() {
        return Vec3.of(-0.4f, -1f, -0.25f).normalize();
    }

    public int score() {
        return score;
    }

    public int ammo() {
        return ammo;
    }

    public int reserveAmmo() {
        return reserveAmmo;
    }

    public int magazineSize() {
        return MAGAZINE_SIZE;
    }

    public boolean reloading() {
        return reloading;
    }

    public float reloadProgress() {
        return reloading ? 1f - clamp(reloadTimer / RELOAD_SECONDS, 0f, 1f) : 0f;
    }

    public float muzzleFlash() {
        return muzzleFlash;
    }

    public int shotsFired() {
        return shotsFired;
    }

    public int shotsHit() {
        return shotsHit;
    }

    public int aliveTargets() {
        int n = 0;
        for (Target t : targets) {
            if (t.alive) {
                n++;
            }
        }
        return n;
    }

    public long elapsedSeconds() {
        return elapsedSeconds;
    }

    public boolean gameOver() {
        return gameOver;
    }

    public void endGame() {
        gameOver = true;
    }

    /** 命中率，0~1；没开火时返回 0。 */
    public float accuracy() {
        return shotsFired == 0 ? 0f : (float) shotsHit / shotsFired;
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
