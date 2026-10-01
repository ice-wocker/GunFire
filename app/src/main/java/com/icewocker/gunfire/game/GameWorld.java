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
    /** 一个像素拖动对应的弧度。 */
    private static final float PLAYER_RADIUS = 0.4f;
    private static final float EYE_HEIGHT = 1.68f;
    private static final float CROUCH_EYE_HEIGHT = 1.05f;
    private static final float JUMP_SPEED = 4.6f;
    private static final int MAGAZINE_SIZE = 30;
    private static final int RESERVE_AMMO = 120;
    private static final float RELOAD_SECONDS = 1.8f;

    /** 玩家两条命：护甲先扛，破甲后才掉血。 */
    public static final float PLAYER_MAX_HEALTH = 100f;
    public static final float PLAYER_MAX_ARMOR = 100f;
    /** 脱战多少秒后开始回血。 */
    private static final float REGEN_DELAY = 5f;
    private static final float REGEN_RATE = 12f;

    /** 连杀窗口：超过这个间隔没再击杀，连击归零。 */
    private static final float STREAK_WINDOW_SECONDS = 3.5f;

    // ---- 武器 ----

    /** 一把枪的全部手感参数。三种口径共用一套射击流程，只是数值不同。 */
    public static final class Weapon {
        public final String name;
        public final int magazine;
        public final int reserve;
        public final float interval;
        public final float damage;
        public final float spreadHeat;
        public final float recoil;
        public final float reloadSeconds;
        public final int pellets;
        public final float zoom;

        Weapon(String name, int magazine, int reserve, float interval, float damage,
               float spreadHeat, float recoil, float reloadSeconds, int pellets, float zoom) {
            this.name = name;
            this.magazine = magazine;
            this.reserve = reserve;
            this.interval = interval;
            this.damage = damage;
            this.spreadHeat = spreadHeat;
            this.recoil = recoil;
            this.reloadSeconds = reloadSeconds;
            this.pellets = pellets;
            this.zoom = zoom;
        }
    }

    private static final Weapon[] WEAPONS = {
            new Weapon("突击步枪", 30, 150, 0.09f, 34f, 0.10f, 1.0f, 1.7f, 1, 1.0f),
            new Weapon("霰弹枪", 8, 40, 0.72f, 22f, 0.05f, 1.8f, 2.2f, 7, 1.0f),
            new Weapon("狙击枪", 5, 30, 1.10f, 130f, 0.02f, 2.4f, 2.6f, 1, 2.6f),
    };

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

        /** 这个方块是否高到能站人（用来判定「踩上去」）。 */
        public float topY() {
            return center.y + halfExtents.y;
        }
    }

    /** 敌人。会巡逻、会追击、会开火，也会躲。 */
    public static final class Enemy {
        public Vec3 position;
        public float health;
        public float maxHealth;
        public boolean alive = true;
        public boolean elite;
        public float hitFlash;
        public float phase;
        /** 0 巡逻 1 追击 2 开火 3 缩回 */
        public int state;
        public float stateTimer;
        public float fireCooldown;
        /** 缩回掩体时移动的朝向 */
        public Vec3 coverAnchor;
        public float speedScale = 1f;
        public float walkPhase;
    }

    /** 掉在地上的补给。靠近自动捡。 */
    public static final class Pickup {
        public Vec3 position;
        public final boolean ammo;
        public float phase;
        public boolean taken;

        Pickup(Vec3 position, boolean ammo) {
            this.position = position;
            this.ammo = ammo;
        }
    }

    /** 飘在屏幕上的伤害/提示文字。渲染层负责把它投到屏幕坐标。 */
    public static final class FloatText {
        public Vec3 world;
        public final String text;
        public float life;
        public final float duration;
        public final float r, g, b;

        FloatText(Vec3 world, String text, float duration, float r, float g, float b) {
            this.world = world;
            this.text = text;
            this.duration = duration;
            this.life = duration;
            this.r = r;
            this.g = g;
            this.b = b;
        }

        public float alpha() {
            return clamp(life / duration, 0f, 1f);
        }
    }

    /** 玩家相机姿态。 */
    public static final class Camera {
        public Vec3 position = Vec3.of(0, EYE_HEIGHT, 6);
        public float yaw;
        public float pitch;
        public float recoil;
        public float velY;
        public float bob;

        public float eyeHeight() {
            return EYE_HEIGHT;
        }

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
    private final List<Enemy> enemies = new ArrayList<>();
    private final List<Pickup> pickups = new ArrayList<>();
    private final List<FloatText> floatTexts = new ArrayList<>();
    private final Camera camera = new Camera();
    private final Random random;

    private int score;
    private int ammo = MAGAZINE_SIZE;
    private int reserveAmmo = RESERVE_AMMO;
    private boolean reloading;
    private float reloadTimer;
    private float muzzleFlash;
    private float spreadHeat;
    private int shotsFired;
    private int shotsHit;
    private long elapsedMillis;
    private boolean gameOver;
    private int wave = 1;
    private int streak;
    private int bestStreak;
    private float streakAge;

    private float playerHealth = PLAYER_MAX_HEALTH;
    private float playerArmor;
    private float sinceDamage = 999f;
    /** 最近 REGEN_DELAY 秒内的受击次数，用于区分「被集火」和「零星擦伤」。 */
    private int hitsInWindow;
    /** 受击方向（相对相机 yaw 的弧度），HUD 用来画方向指示。 */
    private float hitAngle;
    private float hitFlashScreen;
    private boolean crouching;
    private boolean aiming;
    private int weaponIndex;
    private float weaponSwapTimer;
    private int headshots;
    private int kills;
    private int shotsFiredThisLife;
    private boolean paused;
    /** 结算评级，S~D。 */
    private char rank = 'D';
    private int bestWaveEver;

    public GameWorld() {
        this(new Random());
    }

    public GameWorld(Random random) {
        this.random = random;
        buildArena(0);
        spawnWave();
    }

    // ---- 场地 ----

    /** 三张程序生成的场地。每波换一张，走位记忆不会变成肌肉记忆。 */
    private static final int MAP_COUNT = 3;

    private void buildArena(int mapIndex) {
        boxes.clear();
        int m = ((mapIndex % MAP_COUNT) + MAP_COUNT) % MAP_COUNT;
        // 地面 + 四面墙：三张图共用，只在色温上做区分
        float floorTint = m == 0 ? 0.30f : (m == 1 ? 0.26f : 0.32f);
        boxes.add(new Box(Vec3.of(0, -1, 0), Vec3.of(26, 1, 26),
                floorTint, floorTint + 0.02f, floorTint + 0.06f));
        for (int i = 0; i < 4; i++) {
            float[] xz = {0, -24, -24, 24, 24};
            float[] zz = {0, -24, 24, -24, 24};
            if (i == 0) {
                boxes.add(new Box(Vec3.of(0, 3, -24), Vec3.of(26, 4, 1), 0.42f, 0.44f, 0.50f));
            } else if (i == 1) {
                boxes.add(new Box(Vec3.of(-24, 3, 0), Vec3.of(1, 4, 26), 0.42f, 0.44f, 0.50f));
            } else if (i == 2) {
                boxes.add(new Box(Vec3.of(24, 3, 0), Vec3.of(1, 4, 26), 0.42f, 0.44f, 0.50f));
            } else {
                boxes.add(new Box(Vec3.of(0, 3, 24), Vec3.of(26, 4, 1), 0.42f, 0.44f, 0.50f));
            }
            if (xz.length + zz.length > 100) {
                break;
            }
        }

        // 立柱：中轴永远留空，否则玩家出生直射就撞柱子（踩过的坑，别再来一次）
        for (int x = -18; x <= 18; x += 6) {
            if (x == 0) {
                continue;
            }
            for (int z = -16; z <= 4; z += 10) {
                if (m == 1 && Math.abs(z) == 4) {
                    continue;
                }
                boxes.add(new Box(Vec3.of(x, 1.5f, z), Vec3.of(0.6f, 1.5f, 0.6f),
                        0.52f, 0.52f - m * 0.05f, 0.58f));
            }
        }

        // 掩体：刻意两侧对开，中轴留出火力走廊
        for (int i = 0; i < 5; i++) {
            float z = -4f - i * 4f;
            float side = (i % 2 == 0) ? 1f : -1f;
            float x = side * (6f + (m == 2 ? 3f : 0f));
            boxes.add(new Box(Vec3.of(x, 0.7f, z), Vec3.of(2.4f, 0.7f, 1.2f), 0.60f, 0.40f, 0.26f));
            boxes.add(new Box(Vec3.of(-x, 0.7f, z - 2), Vec3.of(2.4f, 0.7f, 1.2f),
                    0.60f, 0.40f, 0.26f));
        }
        // 高台：可跳上去，形成高低差
        boxes.add(new Box(Vec3.of(m == 2 ? 10f : -10f, 0.4f, -14f), Vec3.of(4f, 0.4f, 4f),
                0.36f, 0.38f, 0.44f));
    }

    // ---- 波次与敌人 ----

    private void spawnWave() {
        enemies.clear();
        int count = 3 + wave;
        if (count > 12) {
            count = 12;
        }
        float healthScale = 1f + (wave - 1) * 0.12f;
        float speedScale = 1f + (wave - 1) * 0.045f;
        for (int i = 0; i < count; i++) {
            Enemy e = new Enemy();
            float x = -16f + (i * 32f / Math.max(1, count - 1));
            float z = -18f - (i % 3) * 2f;
            e.position = Vec3.of(x, 1.5f, z);
            e.coverAnchor = Vec3.of(x, 1.5f, z + 6f);
            // 每 3 波出一批精英：更厚、更痛
            e.elite = (wave % 3 == 0) && (i % 3 == 0);
            e.maxHealth = (e.elite ? 260f : 100f) * healthScale;
            e.health = e.maxHealth;
            e.phase = i * 0.7f;
            e.state = 0;
            e.stateTimer = 0.4f + i * 0.15f;
            e.fireCooldown = 0.8f + i * 0.25f;
            e.speedScale = speedScale;
            enemies.add(e);
        }
    }

    public void nextWave() {
        wave++;
        buildArena(wave);
        spawnWave();
        pickups.clear();
        // 每波给一次补给：弹药箱 + 可能的医疗包
        pickups.add(new Pickup(Vec3.of(-6f, 0.4f, 4f), true));
        pickups.add(new Pickup(Vec3.of(6f, 0.4f, 2f), true));
        if (wave % 2 == 0) {
            pickups.add(new Pickup(Vec3.of(0f, 0.4f, -2f), false));
        }
        boolean swapped = false;
        // 每 3 波换一把枪，逼玩家重新适应手感
        if (wave % 3 == 0) {
            weaponIndex = (weaponIndex + 1) % WEAPONS.length;
            ammo = weapon().magazine;
            // 备弹取「新枪的基准量」和「玩家手上已攒的」的较大者。
            // 直接赋值等于换一次枪就把玩家攒的子弹清空：从突击步枪（150 备弹）
            // 换到霰弹枪（40 备弹）时，手上 180 发会瞬间变成 40 发。
            reserveAmmo = Math.max(weapon().reserve, reserveAmmo);
            reloading = false;
            swapped = true;
            addText(camera.position.add(camera.forward().scale(3f)),
                    "换装：" + weapon().name, 2.2f, 1f, 0.85f, 0.4f);
        }
        // 换波必须补一次弹药，不然「弹尽 → 结束」的判定会在第 3 波就断掉整局，
        // 玩家明明活着却因为没子弹被结算。
        if (!swapped) {
            // 只加不封顶到当前武器的初始备弹：从突击步枪（150）换到霰弹枪（40）时，
            // 用 weapon().reserve 当上限会把玩家攒的子弹一次吞掉 110 发。
            reserveAmmo += weapon().magazine * 2;
        }
    }

    public Weapon weapon() {
        return WEAPONS[weaponIndex];
    }

    // ---- 输入事件 ----

    /** 鼠标/触摸拖拽，单位为像素增量，转换为弧度。 */
    public void look(float dxPixels, float dyPixels) {
        if (gameOver || paused) {
            return;
        }
        float sensitivity = 0.0022f / (aiming ? weapon().zoom : 1f);
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

    public void setCrouching(boolean value) {
        crouching = value;
    }

    public boolean crouching() {
        return crouching;
    }

    public void setAiming(boolean value) {
        aiming = value;
    }

    public boolean aiming() {
        return aiming;
    }

    /** 跳跃。只有踩在地面或台子上才能起跳。 */
    public void jump() {
        if (gameOver || paused || !onGround()) {
            return;
        }
        camera.velY = JUMP_SPEED;
    }

    public boolean onGround() {
        return camera.velY <= 0.001f && camera.position.y <= groundHeight(camera.position) + 0.02f;
    }

    public void togglePause() {
        if (gameOver) {
            return;
        }
        paused = !paused;
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean value) {
        paused = value;
    }

    public void move(float forwardAmount, float strafeAmount, float dt) {
        if (gameOver || paused) {
            return;
        }
        float speed = (aiming ? 2.6f : 5.2f) * (crouching ? 0.5f : 1f);
        Vec3 dir = camera.forward();
        Vec3 flat = Vec3.of(dir.x, 0, dir.z).normalize();
        Vec3 right = camera.right();
        Vec3 delta = flat.scale(forwardAmount * speed * dt)
                .add(right.scale(strafeAmount * speed * dt));
        Vec3 next = camera.position.add(delta);
        camera.position = resolveCollision(camera.position, next);

        // 头部随走动轻微起伏——纯相机位移，比任何贴图都便宜
        float moving = Math.min(1f, Math.abs(forwardAmount) + Math.abs(strafeAmount));
        camera.bob += dt * 9f * moving;
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
        float limit = 23f;
        return Vec3.of(clamp(pos.x, -limit, limit), pos.y, clamp(pos.z, -limit, limit));
    }

    /** 脚下最高的可站立表面。玩家站上去后 y 会被抬到它的顶上。 */
    private float groundHeight(Vec3 pos) {
        float base = EYE_HEIGHT;
        for (Box b : boxes) {
            float top = b.topY();
            if (top <= base + 0.01f) {
                continue;
            }
            float gapX = Math.abs(pos.x - b.center.x) - b.halfExtents.x;
            float gapZ = Math.abs(pos.z - b.center.z) - b.halfExtents.z;
            if (gapX < PLAYER_RADIUS && gapZ < PLAYER_RADIUS) {
                base = Math.max(base, top + EYE_HEIGHT);
            }
        }
        return base;
    }

    /**
     * 玩家能否站在 pos。
     * 只考虑与躯干高度范围重叠的实体：矮于膝盖的箱体视为可跨越，不阻挡。
     */
    private boolean collides(Vec3 pos) {
        float stand = groundHeight(Vec3.of(pos.x, EYE_HEIGHT - EYE_HEIGHT, pos.z));
        float feet = pos.y - EYE_HEIGHT;
        float knee = feet + 0.55f;
        float head = feet + 1.75f;
        for (Box b : boxes) {
            float top = b.center.y + b.halfExtents.y;
            float bottom = b.center.y - b.halfExtents.y;
            // 已经站在这个方块顶上时，它不再挡路
            if (top <= feet + 0.12f) {
                continue;
            }
            if (top <= knee || bottom >= head) {
                continue;
            }
            float gapX = Math.abs(pos.x - b.center.x) - b.halfExtents.x;
            float gapZ = Math.abs(pos.z - b.center.z) - b.halfExtents.z;
            if (gapX < PLAYER_RADIUS && gapZ < PLAYER_RADIUS) {
                return true;
            }
            if (stand > 0f) {
                break;
            }
        }
        return false;
    }

    // ---- 射击 ----

    /** 开火。返回是否真的打出了子弹。 */
    public boolean fire() {
        if (gameOver || paused || reloading || weaponSwapTimer > 0f || ammo <= 0) {
            if (ammo <= 0 && !reloading && !gameOver && !paused) {
                reload();
            }
            return false;
        }
        Weapon w = weapon();
        ammo--;
        shotsFired++;
        shotsFiredThisLife++;
        muzzleFlash = 1f;
        spreadHeat = Math.min(spreadHeat + w.spreadHeat, 1f);
        camera.recoil += (aiming ? 0.5f : 1f) * (0.004f + spreadHeat * 0.006f) * w.recoil;

        boolean anyHit = false;
        for (int p = 0; p < w.pellets; p++) {
            Vec3 dir = spreadDirection(camera.forward(), w);
            Enemy hit = raycastEnemy(camera.position, dir);
            if (hit == null) {
                continue;
            }
            anyHit = true;
            float dist = camera.position.distanceTo(hit.position);
            boolean head = isHeadshot(hit, camera.position, dir);
            float dmg = w.damage * (head ? 2.4f : 1f);
            if (dist > 18f) {
                dmg *= 0.85f;
            }
            applyDamage(hit, dmg, head, dir, dist);
        }
        if (anyHit) {
            shotsHit++;
        }
        return true;
    }

    private void applyDamage(Enemy e, float dmg, boolean head, Vec3 dir, float dist) {
        e.health -= dmg;
        e.hitFlash = 0.14f;
        // 被打中就进入追击，不再原地遛弯
        if (e.state == 0) {
            e.state = 1;
            e.stateTimer = 2.5f;
        }
        float base = head ? 10f : 5f;
        score += (int) base;
        addText(e.position.add(Vec3.of(0, head ? 0.55f : 0.25f, 0)),
                head ? "爆头 " + (int) dmg : String.valueOf((int) dmg),
                0.8f, head ? 1f : 1f, head ? 0.55f : 0.9f, head ? 0.3f : 0.6f);
        if (e.health > 0f || !e.alive) {
            return;
        }
        e.alive = false;
        kills++;
        if (head) {
            headshots++;
        }
        streak++;
        streakAge = 0f;
        bestStreak = Math.max(bestStreak, streak);
        // 连杀加成：2 连 200、3 连 300……最多叠到 600 封顶。
        int bonus = 100 + Math.min(streak - 1, 5) * 100;
        if (e.elite) {
            bonus *= 2;
        }
        score += bonus;
        addText(e.position, "×" + streak + " 连杀 +" + bonus, 1.6f, 1f, 0.7f, 0.25f);
        dropLoot(e.position);
    }

    private void dropLoot(Vec3 at) {
        // 弹药掉率 55%，医疗包 35%（血不满时才掉）
        if (random.nextFloat() < 0.55f) {
            pickups.add(new Pickup(at, true));
        }
        if (random.nextFloat() < 0.35f && playerHealth < PLAYER_MAX_HEALTH) {
            pickups.add(new Pickup(at, false));
        }
    }

    /** 判定是否打到头部：命中点高度高于敌人胸口线。 */
    private boolean isHeadshot(Enemy e, Vec3 origin, Vec3 dir) {
        float half = e.elite ? 0.85f : 0.75f;
        float t = intersectAabb(origin, dir, e.position, Vec3.of(0.42f, half, 0.24f));
        if (t < 0f) {
            return false;
        }
        float hitY = origin.y + dir.y * t;
        return hitY > e.position.y + half * 0.35f;
    }

    /**
     * 给子弹加一点散布：连射越久越飘。
     *
     * 幅度必须远小于目标张角，否则「枪比靶子宽」会变成必然脱靶。
     * 目标半高 0.35 在 20 米外约合 0.0175 rad，因此上限压到 0.012 rad 以内。
     */
    private Vec3 spreadDirection(Vec3 base, Weapon w) {
        float heat = spreadHeat * (aiming ? 0.35f : 1f);
        if (heat <= 0.001f || w.pellets > 1) {
            heat = Math.max(heat, w.pellets > 1 ? 0.5f : 0f);
        }
        if (heat <= 0.001f) {
            return base;
        }
        float spread = heat * 0.012f;
        Vec3 right = camera.right();
        Vec3 up = right.cross(base).scale(-1f);
        float rx = (random.nextFloat() * 2f - 1f) * spread;
        float ry = (random.nextFloat() * 2f - 1f) * spread;
        return base.add(right.scale(rx)).add(up.scale(ry)).normalize();
    }

    /**
     * 射线与所有存活敌人求交，返回最近的一个。
     * 用「球体近似 + 逐轴 slab 检测」的混合：敌人当 AABB 处理，与渲染的方块一致。
     */
    public Enemy raycastEnemy(Vec3 origin, Vec3 dir) {
        Enemy best = null;
        float bestT = Float.MAX_VALUE;
        for (Enemy e : enemies) {
            if (!e.alive) {
                continue;
            }
            float half = e.elite ? 0.85f : 0.75f;
            // 判定盒略大于渲染体，抵消散布带来的手感损失
            Vec3 box = Vec3.of(0.42f, half, 0.24f);
            float t0 = intersectAabb(origin, dir, e.position, box);
            if (t0 >= 0f && t0 < bestT) {
                bestT = t0;
                best = e;
            }
        }
        if (best == null) {
            return null;
        }
        // 打中敌人之前先撞墙，则不算命中
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
        Weapon w = weapon();
        if (reloading || ammo == w.magazine || reserveAmmo <= 0 || paused) {
            return;
        }
        reloading = true;
        reloadTimer = w.reloadSeconds;
    }

    // ---- 世界推进 ----

    /** 推进一帧。dt 单位秒，外部需保证不被异常放大（否则会穿墙）。 */
    public void update(float dt) {
        if (gameOver || paused) {
            return;
        }
        dt = Math.min(dt, 0.05f);
        // 毫秒小数部分必须自己攒：每帧 dt≈16.7ms，(long) 强转会把小数全丢掉，
        // 计时器于是永远停在 0。
        elapsedMillis += Math.round(dt * 1000f);

        muzzleFlash = Math.max(0f, muzzleFlash - dt * 8f);
        spreadHeat = Math.max(0f, spreadHeat - dt * 0.7f);
        camera.recoil = Math.max(0f, camera.recoil - dt * 0.35f);
        hitFlashScreen = Math.max(0f, hitFlashScreen - dt * 2.2f);
        sinceDamage += dt;
        if (sinceDamage > REGEN_DELAY) {
            hitsInWindow = 0;
        }
        if (weaponSwapTimer > 0f) {
            weaponSwapTimer -= dt;
        }

        // 先结算死亡，再做恢复类逻辑——顺序反了会出现「死而复活」
        if (playerHealth <= 0f) {
            endGame();
            return;
        }

        updateVertical(dt);
        updateReload(dt);
        regen(dt);
        updateEnemies(dt);
        updatePickups(dt);
        updateFloats(dt);

        // 连杀倒计时：不刷新就会被清零，逼玩家往前压而不是蹲着等。
        if (streak > 0) {
            streakAge += dt;
            if (streakAge > STREAK_WINDOW_SECONDS) {
                streak = 0;
                streakAge = 0f;
            }
        }

        if (enemiesCleared()) {
            nextWave();
        }

        // 敌人可能在本帧把玩家打死，这里再兜一次
        if (playerHealth <= 0f) {
            endGame();
        }

        // 弹匣和备弹都空 = 这一局结束。
        // 少了这条，玩家会卡在「点得动但打不出子弹、也没人告诉你结束了」的死局里。
        // 注意要在换波补弹之后再判：换波会补一次弹药，不该被误判成弹尽。
        if (ammo <= 0 && reserveAmmo <= 0 && !reloading) {
            endGame();
        }
    }

    private void updateVertical(float dt) {
        float floorY = groundHeight(camera.position);
        camera.velY += -9.8f * dt;
        camera.position = Vec3.of(camera.position.x, camera.position.y + camera.velY * dt,
                camera.position.z);
        float standY = groundHeight(camera.position) + (crouching ? CROUCH_EYE_HEIGHT - EYE_HEIGHT : 0f);
        float clampedFloor = Math.max(floorY, groundHeight(camera.position));
        if (camera.position.y <= clampedFloor) {
            camera.position = Vec3.of(camera.position.x, clampedFloor, camera.position.z);
            camera.velY = 0f;
        }
        if (Float.isNaN(standY)) {
            camera.position = Vec3.of(camera.position.x, EYE_HEIGHT, camera.position.z);
        }
    }

    private void updateReload(float dt) {
        if (!reloading) {
            return;
        }
        reloadTimer -= dt;
        if (reloadTimer <= 0f) {
            Weapon w = weapon();
            int need = w.magazine - ammo;
            int take = Math.min(need, reserveAmmo);
            ammo += take;
            reserveAmmo -= take;
            reloading = false;
        }
    }

    private void regen(float dt) {
        // 血量为 0 绝对不能回血。
        //
        // 这是个真的竞态：update() 里先 regen 再判死亡，如果 regen 能把 0 拉起来，
        // 死亡判定就永远不成立，玩家变成打不死的（实测挨 1000 点伤害后血量 0，
        // 一帧后又变成 0.2，游戏继续）。
        if (playerHealth <= 0f) {
            return;
        }
        // 脱战满 REGEN_DELAY 秒才开始回复。
        //
        // 不能用「挨打就清零」的严格判定：敌人是持续开火的，每 1~2 秒就命中一次，
        // 严格的脱战窗口永远等不到，回血等于不存在（实测跑满 12 秒从 60 掉到 44，
        // 一滴没回）。改成「最近 REGEN_DELAY 秒内被命中 2 次以上才视为被集火」——
        // 零星擦伤能慢慢恢复，被按着打照样回不上来。
        boolean pressured = sinceDamage < REGEN_DELAY && hitsInWindow >= 2;
        if (pressured || playerHealth >= PLAYER_MAX_HEALTH) {
            return;
        }
        playerHealth = Math.min(PLAYER_MAX_HEALTH, playerHealth + REGEN_RATE * dt);
    }

    /**
     * 敌人 AI：一个四状态的机器。
     *
     *   0 巡逻 —— 朝玩家方向缓慢推进
     *   1 追击 —— 发现玩家且距离远，直线逼近
     *   2 开火 —— 进入射程且有视线，站着打几发
     *   3 缩回 —— 被打中后后撤到掩体锚点，冷却完再来
     *
     * 状态机而不是寻路：场地是开阔的，没有需要绕的迷宫，
     * 加 A* 是过度设计，直线 + 碰撞滑动就够。
     */
    private void updateEnemies(float dt) {
        Vec3 player = camera.position;
        for (Enemy e : enemies) {
            if (!e.alive) {
                continue;
            }
            e.phase += dt;
            e.hitFlash = Math.max(0f, e.hitFlash - dt);
            e.stateTimer -= dt;
            e.fireCooldown -= dt;

            float dist = e.position.distanceTo(player);
            boolean los = hasLineOfSight(e.position, player);
            float speed = (e.elite ? 1.7f : 2.2f) * e.speedScale;

            if (e.stateTimer <= 0f) {
                if (e.health < e.maxHealth * 0.35f && random.nextFloat() < 0.5f) {
                    e.state = 3;
                    e.stateTimer = 1.6f;
                } else if (los && dist < 22f) {
                    e.state = 2;
                    e.stateTimer = 1.4f + random.nextFloat();
                } else {
                    e.state = 1;
                    e.stateTimer = 1.5f;
                }
            }

            Vec3 target = player;
            if (e.state == 3) {
                target = e.coverAnchor == null ? player : e.coverAnchor;
                if (dist < 6f) {
                    Vec3 away = e.position.sub(player).normalize();
                    target = e.position.add(away.scale(4f));
                }
            }
            if (e.state != 2) {
                Vec3 dir = Vec3.of(target.x - e.position.x, 0, target.z - e.position.z).normalize();
                Vec3 step = dir.scale(speed * dt);
                Vec3 next = e.position.add(step);
                if (!enemyBlocked(Vec3.of(next.x, e.position.y, next.z))) {
                    e.position = Vec3.of(next.x, e.position.y, next.z);
                } else {
                    // 撞墙就地侧移，别抖在原地
                    Vec3 side = Vec3.of(-dir.z, 0, dir.x).scale(speed * dt);
                    Vec3 alt = e.position.add(side);
                    if (!enemyBlocked(alt)) {
                        e.position = alt;
                    }
                }
                e.walkPhase += dt * 6f;
            }
            e.position = Vec3.of(clamp(e.position.x, -22f, 22f), e.position.y,
                    clamp(e.position.z, -22f, 22f));
            float groundY = groundHeight(e.position) - EYE_HEIGHT + 1.5f;
            e.position = Vec3.of(e.position.x, Math.max(1.2f, groundY), e.position.z);

            if (e.state == 2 && los && e.fireCooldown <= 0f) {
                enemyShoot(e, dist);
                e.fireCooldown = (e.elite ? 0.55f : 0.95f) / (1f + (wave - 1) * 0.06f)
                        + random.nextFloat() * 0.5f;
            }
        }
    }

    /** 敌人开枪：命中率随距离和波次衰减。 */
    private void enemyShoot(Enemy e, float dist) {
        float baseHit = e.elite ? 0.45f : 0.30f;
        baseHit += Math.min(0.15f, (wave - 1) * 0.02f);
        float chance = baseHit * clamp(1f - dist / 40f, 0.25f, 1f);
        if (random.nextFloat() > chance) {
            return;
        }
        float dmg = (e.elite ? 13f : 8f) * (1f + (wave - 1) * 0.05f);
        if (crouching) {
            dmg *= 0.75f;
        }
        damagePlayer(dmg, e.position);
    }

    /** 玩家掉血：护甲先扛 70%，破甲后全打在身上。 */
    public void damagePlayer(float dmg, Vec3 from) {
        if (gameOver) {
            return;
        }
        float absorbed = Math.min(playerArmor, dmg * 0.7f);
        playerArmor -= absorbed;
        playerHealth -= (dmg - absorbed);
        sinceDamage = 0f;
        hitsInWindow++;
        hitFlashScreen = Math.min(1f, hitFlashScreen + 0.55f);
        if (from != null) {
            // 用未归一化的位移算 atan2：归一化不会改变角度，但会让后面
            // 「右侧 90°」这类断言的尺度信息看起来更隐晦，这里保留原始位移更清楚。
            float dx = from.x - camera.position.x;
            float dz = from.z - camera.position.z;
            float angle = (float) Math.atan2(dx, -dz) - camera.yaw;
            hitAngle = angle;
        }
        if (playerHealth < 0f) {
            playerHealth = 0f;
        }
    }

    private boolean hasLineOfSight(Vec3 from, Vec3 to) {
        Vec3 eye = Vec3.of(from.x, from.y + 0.3f, from.z);
        Vec3 dir = to.sub(eye);
        float dist = dir.length();
        if (dist < 0.01f) {
            return true;
        }
        dir = dir.normalize();
        float wall = raycastWorld(eye, dir);
        return wall >= dist - 0.2f;
    }

    private boolean enemyBlocked(Vec3 pos) {
        float feet = pos.y - 1.5f;
        for (Box b : boxes) {
            float top = b.center.y + b.halfExtents.y;
            if (top <= feet + 0.6f) {
                continue;
            }
            float gapX = Math.abs(pos.x - b.center.x) - b.halfExtents.x;
            float gapZ = Math.abs(pos.z - b.center.z) - b.halfExtents.z;
            if (gapX < 0.5f && gapZ < 0.5f) {
                return true;
            }
        }
        return false;
    }

    private void updatePickups(float dt) {
        for (Pickup p : pickups) {
            p.phase += dt;
            if (p.taken) {
                continue;
            }
            // 拾取只看水平距离。补给躺在地上（y≈0.4），玩家 eye 在 1.68m，
            // 甚至可能站在掩体顶上（y 更高）——用点到点距离或者加高度容差都会
            // 让「明明踩在上面却捡不到」这种事发生。二维距离才是玩家的直觉。
            float dx = p.position.x - camera.position.x;
            float dz = p.position.z - camera.position.z;
            if (dx * dx + dz * dz < 1.8f * 1.8f) {
                p.taken = true;
                Weapon w = weapon();
                if (p.ammo) {
                    // 上限按「当前武器备弹 + 一弹匣」算，不能用 w.reserve*2 —— 换到弹匣小的枪时
                    // 那个上限会低于玩家已有的备弹，Math.min 会把子弹直接吞掉。
                    reserveAmmo += w.magazine * 2;
                    addText(p.position, "弹药 +" + (w.magazine * 2), 1.2f, 0.5f, 0.9f, 1f);
                } else {
                    playerHealth = Math.min(PLAYER_MAX_HEALTH, playerHealth + 40f);
                    addText(p.position, "生命 +40", 1.2f, 0.4f, 1f, 0.6f);
                }
            }
        }
        for (int i = pickups.size() - 1; i >= 0; i--) {
            if (pickups.get(i).taken) {
                pickups.remove(i);
            }
        }
    }

    private void updateFloats(float dt) {
        for (int i = floatTexts.size() - 1; i >= 0; i--) {
            FloatText t = floatTexts.get(i);
            t.life -= dt;
            t.world = t.world.add(Vec3.of(0, dt * 1.1f, 0));
            if (t.life <= 0f) {
                floatTexts.remove(i);
            }
        }
    }

    private void addText(Vec3 at, String text, float duration, float r, float g, float b) {
        if (floatTexts.size() > 24) {
            floatTexts.remove(0);
        }
        floatTexts.add(new FloatText(at, text, duration, r, g, b));
    }

    private boolean enemiesCleared() {
        for (Enemy e : enemies) {
            if (e.alive) {
                return false;
            }
        }
        return true;
    }

    // ---- 只读快照 ----

    public Camera camera() {
        return camera;
    }

    public List<Box> boxes() {
        return boxes;
    }

    public List<Enemy> enemies() {
        return enemies;
    }

    public List<Pickup> pickups() {
        return pickups;
    }

    public List<FloatText> floatTexts() {
        return floatTexts;
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
        return weapon().magazine;
    }

    public boolean reloading() {
        return reloading;
    }

    public float reloadProgress() {
        return reloading ? 1f - clamp(reloadTimer / weapon().reloadSeconds, 0f, 1f) : 0f;
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

    public int aliveEnemies() {
        int n = 0;
        for (Enemy e : enemies) {
            if (e.alive) {
                n++;
            }
        }
        return n;
    }

    public long elapsedSeconds() {
        return elapsedMillis / 1000;
    }

    public int wave() {
        return wave;
    }

    public int streak() {
        return streak;
    }

    public int bestStreak() {
        return bestStreak;
    }

    public int kills() {
        return kills;
    }

    public int headshots() {
        return headshots;
    }

    public float health() {
        return playerHealth;
    }

    public float armor() {
        return playerArmor;
    }

    public float healthFraction() {
        return playerHealth / PLAYER_MAX_HEALTH;
    }

    public float armorFraction() {
        return playerArmor / PLAYER_MAX_ARMOR;
    }

    public float hitFlashScreen() {
        return hitFlashScreen;
    }

    /** 受击方向，相对当前视角的角度（弧度），HUD 画成屏幕边缘的弧。 */
    public float hitAngle() {
        return hitAngle;
    }

    public float spreadHeat() {
        return spreadHeat;
    }

    public int weaponIndex() {
        return weaponIndex;
    }

    /** 连杀窗口剩余比例，0 表示没在连杀。HUD 用它画一条会缩短的进度条。 */
    public float streakRemaining() {
        if (streak == 0) {
            return 0f;
        }
        return clamp(1f - streakAge / STREAK_WINDOW_SECONDS, 0f, 1f);
    }

    public boolean gameOver() {
        return gameOver;
    }

    public void endGame() {
        gameOver = true;
        bestWaveEver = Math.max(bestWaveEver, wave);
        rank = computeRank();
    }

    /** 结算评级。权重写死在这里，避免 HUD 自己编一套算法。 */
    private char computeRank() {
        float acc = accuracy();
        int hp = (int) (acc * 100);
        int pts = score / 1000 + wave * 2 + hp / 10 + bestStreak + headshots / 5;
        if (pts >= 60) {
            return 'S';
        }
        if (pts >= 42) {
            return 'A';
        }
        if (pts >= 28) {
            return 'B';
        }
        if (pts >= 16) {
            return 'C';
        }
        return 'D';
    }

    public char rank() {
        return rank;
    }

    public int bestWaveEver() {
        return bestWaveEver;
    }

    public void setBestWaveEver(int value) {
        this.bestWaveEver = Math.max(bestWaveEver, value);
    }

    /** 重开一局：场地与武器重置，但保留历史最高记录。 */
    public void restart() {
        score = 0;
        weaponIndex = 0;
        Weapon w = weapon();
        ammo = w.magazine;
        reserveAmmo = w.reserve;
        reloading = false;
        reloadTimer = 0f;
        shotsFired = 0;
        shotsHit = 0;
        shotsFiredThisLife = 0;
        elapsedMillis = 0L;
        wave = 1;
        streak = 0;
        bestStreak = 0;
        streakAge = 0f;
        kills = 0;
        headshots = 0;
        muzzleFlash = 0f;
        spreadHeat = 0f;
        playerHealth = PLAYER_MAX_HEALTH;
        playerArmor = 0f;
        sinceDamage = 999f;
        hitsInWindow = 0;
        hitFlashScreen = 0f;
        camera.recoil = 0f;
        camera.velY = 0f;
        camera.bob = 0f;
        camera.position = Vec3.of(0, EYE_HEIGHT, 6);
        camera.yaw = 0f;
        camera.pitch = 0f;
        crouching = false;
        aiming = false;
        paused = false;
        rank = 'D';
        buildArena(0);
        spawnWave();
        pickups.clear();
        floatTexts.clear();
        gameOver = false;
    }

    /** 命中率，0~1；没开火时返回 0。 */
    public float accuracy() {
        return shotsFired == 0 ? 0f : (float) shotsHit / shotsFired;
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
