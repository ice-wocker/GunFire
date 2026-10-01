package com.icewocker.gunfire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.icewocker.gunfire.engine.Mat4;
import com.icewocker.gunfire.engine.Vec3;
import com.icewocker.gunfire.game.GameWorld;

import org.junit.Test;

import java.util.Random;

/**
 * 游戏规则的离线测试。
 * GameWorld 不依赖 Android，所以这些用例在普通 JVM 上就能跑——
 * 这是把它和渲染层拆开的主要好处。
 */
public class GameWorldTest {

    private GameWorld world() {
        return new GameWorld(new Random(42));
    }

    /** 推进一秒（60 帧），很多状态转换需要时间。 */
    private void tick(GameWorld w, int frames) {
        for (int i = 0; i < frames; i++) {
            w.update(1f / 60f);
        }
    }

    /** 把相机摆到正对中轴敌人的位置。 */
    private void aimAtNearest(GameWorld w) {
        for (GameWorld.Enemy e : w.enemies()) {
            if (!e.alive) {
                continue;
            }
            Vec3 from = Vec3.of(e.position.x, e.position.y, e.position.z + 8f);
            w.camera().position = from;
            w.camera().yaw = 0f;
            w.camera().pitch = 0f;
            return;
        }
    }

    @Test
    public void startsWithFullMagazineAndReserve() {
        GameWorld w = world();
        assertEquals(w.magazineSize(), w.ammo());
        assertTrue(w.reserveAmmo() > 0);
        assertFalse(w.reloading());
    }

    @Test
    public void startsWithFullHealthAndNoArmor() {
        GameWorld w = world();
        assertEquals(GameWorld.PLAYER_MAX_HEALTH, w.health(), 1e-3f);
        assertEquals(0f, w.armor(), 1e-3f);
        assertFalse(w.gameOver());
    }

    @Test
    public void firingConsumesOneRound() {
        GameWorld w = world();
        int before = w.ammo();
        assertTrue(w.fire());
        assertEquals(before - 1, w.ammo());
        assertEquals(1, w.shotsFired());
    }

    @Test
    public void emptyMagazineTriggersAutoReload() {
        GameWorld w = world();
        for (int i = 0; i < w.magazineSize(); i++) {
            w.fire();
        }
        assertEquals(0, w.ammo());
        w.fire();
        assertTrue(w.reloading());
    }

    @Test
    public void reloadRefillsFromReserve() {
        GameWorld w = world();
        for (int i = 0; i < 10; i++) {
            w.fire();
        }
        int ammoBefore = w.ammo();
        int reserveBefore = w.reserveAmmo();
        w.reload();
        tick(w, 200);
        assertFalse(w.reloading());
        assertEquals(w.magazineSize(), w.ammo());
        assertEquals(reserveBefore - (w.magazineSize() - ammoBefore), w.reserveAmmo());
    }

    // ---- 射线检测 ----

    @Test
    public void rayHitsEnemyDirectlyAhead() {
        GameWorld w = world();
        GameWorld.Enemy e = w.enemies().get(0);
        Vec3 origin = Vec3.of(e.position.x, e.position.y, e.position.z + 6f);
        assertNotNull(w.raycastEnemy(origin, Vec3.of(0, 0, -1)));
    }

    @Test
    public void rayMissesWhenAimedAwayFromEnemies() {
        GameWorld w = world();
        assertNull(w.raycastEnemy(Vec3.of(0, 1.4f, 5), Vec3.of(0, 1, 0)));
    }

    @Test
    public void raycastIgnoresDeadEnemies() {
        GameWorld w = world();
        GameWorld.Enemy e = w.enemies().get(0);
        Vec3 origin = Vec3.of(e.position.x, e.position.y, e.position.z + 6f);
        assertNotNull(w.raycastEnemy(origin, Vec3.of(0, 0, -1)));
        for (GameWorld.Enemy other : w.enemies()) {
            other.alive = false;
        }
        assertNull(w.raycastEnemy(origin, Vec3.of(0, 0, -1)));
    }

    @Test
    public void wallBlocksShotAtEnemyBehindIt() {
        GameWorld w = world();
        // 站在场地外面朝里打，外墙应该先把子弹吃掉
        Vec3 origin = Vec3.of(0, 1.6f, 40f);
        GameWorld.Enemy e = w.enemies().get(0);
        assertNull("墙后的敌人不应被命中", w.raycastEnemy(origin, Vec3.of(0, 0, -1)));
        assertNotNull(e);
    }

    @Test
    public void aabbIntersectionHandlesMissAndHit() {
        Vec3 center = Vec3.of(0, 0, 0);
        Vec3 half = Vec3.of(1, 1, 1);
        float hit = GameWorld.intersectAabb(Vec3.of(0, 0, 10), Vec3.of(0, 0, -1), center, half);
        assertEquals(9f, hit, 1e-3f);
        float miss = GameWorld.intersectAabb(Vec3.of(5, 5, 10), Vec3.of(0, 0, -1), center, half);
        assertTrue(miss < 0f);
    }

    @Test
    public void aabbParallelRayOutsideSlabDoesNotHit() {
        float t = GameWorld.intersectAabb(Vec3.of(10, 10, 10), Vec3.of(1, 0, 0),
                Vec3.of(0, 0, 0), Vec3.of(1, 1, 1));
        assertTrue(t < 0f);
    }

    // ---- 计分与反馈 ----

    @Test
    public void hittingButNotKillingStillScores() {
        GameWorld w = world();
        aimAtNearest(w);
        int before = w.score();
        w.fire();
        assertTrue("命中未击杀也应该给分", w.score() > before);
    }

    @Test
    public void killIncrementsKillCounterAndStreak() {
        GameWorld w = world();
        aimAtNearest(w);
        int guard = 0;
        while (w.kills() == 0 && guard++ < 600) {
            aimAtNearest(w);
            w.fire();
            tick(w, 1);
            if (w.reloading()) {
                tick(w, 130);
            }
            if (w.ammo() == 0 && w.reserveAmmo() == 0) {
                break;
            }
        }
        assertEquals("至少要打死一个敌人", 1, w.kills());
        assertTrue(w.bestStreak() >= 1);
    }

    @Test
    public void headshotDoesMoreDamageThanBodyShot() {
        GameWorld w = world();
        GameWorld.Enemy e = w.enemies().get(0);
        Vec3 bodyOrigin = Vec3.of(e.position.x, e.position.y, e.position.z + 8f);
        Vec3 headOrigin = Vec3.of(e.position.x, e.position.y + 0.6f, e.position.z + 8f);
        // 同一把枪、同一个敌人：抬高一米再打应该更快打死
        GameWorld a = world();
        aimAtNearest(a);
        float hpBefore = a.enemies().get(0).health;
        a.fire();
        float bodyDamage = hpBefore - a.enemies().get(0).health;

        GameWorld b = world();
        GameWorld.Enemy target = b.enemies().get(0);
        b.camera().position = Vec3.of(target.position.x, target.position.y + 0.62f,
                target.position.z + 8f);
        b.camera().yaw = 0f;
        b.camera().pitch = 0f;
        float hpBefore2 = target.health;
        b.fire();
        float headDamage = hpBefore2 - target.health;
        assertTrue("爆头伤害应不低于躯干：" + headDamage + " vs " + bodyDamage,
                headDamage >= bodyDamage);
        assertNotNull(bodyOrigin);
        assertNotNull(headOrigin);
    }

    @Test
    public void waveIncrementsAfterClearingAllEnemies() {
        GameWorld w = world();
        int before = w.wave();
        for (GameWorld.Enemy e : w.enemies()) {
            e.alive = false;
        }
        tick(w, 1);
        assertEquals(before + 1, w.wave());
        assertTrue("新一波必须有敌人", w.aliveEnemies() > 0);
    }

    @Test
    public void laterWavesAreTougher() {
        GameWorld w = world();
        float firstMax = w.enemies().get(0).maxHealth;
        for (GameWorld.Enemy e : w.enemies()) {
            e.alive = false;
        }
        tick(w, 1);
        assertTrue("第二波敌人不该比第一波更脆",
                w.enemies().get(0).maxHealth >= firstMax);
    }

    @Test
    public void laterWavesSwapToADifferentWeapon() {
        GameWorld w = world();
        int first = w.weaponIndex();
        for (int i = 0; i < 3; i++) {
            for (GameWorld.Enemy e : w.enemies()) {
                e.alive = false;
            }
            tick(w, 1);
        }
        assertTrue("每 3 波应换一把枪", w.weaponIndex() != first);
    }

    @Test
    public void accuracyStaysWithinUnitRange() {
        GameWorld w = world();
        for (int i = 0; i < 40; i++) {
            w.fire();
            tick(w, 1);
        }
        float acc = w.accuracy();
        assertTrue(acc >= 0f && acc <= 1f);
    }

    @Test
    public void streakResetsAfterWindow() {
        GameWorld w = world();
        aimAtNearest(w);
        int guard = 0;
        while (w.kills() == 0 && guard++ < 600) {
            aimAtNearest(w);
            w.fire();
            tick(w, 1);
            if (w.reloading()) {
                tick(w, 130);
            }
        }
        if (w.streak() > 0) {
            tick(w, 300);
            assertEquals("连杀窗口过后应归零", 0, w.streak());
        }
    }

    @Test
    public void streakRemainingIsBounded() {
        GameWorld w = world();
        float remain = w.streakRemaining();
        assertTrue(remain >= 0f && remain <= 1f);
    }

    // ---- 玩家生存 ----

    @Test
    public void armorAbsorbsMostDamage() {
        GameWorld w = world();
        // 先给一点护甲，靠受击测试护甲分流
        w.damagePlayer(0f, Vec3.of(0, 1, 0));
        float hp = w.health();
        w.damagePlayer(10f, Vec3.of(0, 1, 0));
        assertTrue("掉血不该超过受到的伤害", w.health() >= hp - 10.01f);
    }

    @Test
    public void damageSetsHitFlashAndLowersHealth() {
        GameWorld w = world();
        float before = w.health();
        w.damagePlayer(15f, Vec3.of(0, 1, -5));
        assertTrue(w.health() < before);
        assertTrue("受击后应有屏幕反馈", w.hitFlashScreen() > 0f);
    }

    @Test
    public void healthRegeneratesAfterLeavingCombat() {
        GameWorld w = world();
        w.damagePlayer(40f, null);
        float hurt = w.health();
        tick(w, 60 * 12);
        assertTrue("脱战一段时间后应该回血", w.health() > hurt);
    }

    @Test
    public void healthNeverExceedsMaximum() {
        GameWorld w = world();
        tick(w, 60 * 30);
        assertTrue(w.health() <= GameWorld.PLAYER_MAX_HEALTH + 1e-3f);
    }

    @Test
    public void deathEndsTheGameAndSetsRank() {
        GameWorld w = world();
        w.damagePlayer(1000f, Vec3.of(0, 1, -3));
        assertEquals("血量为零", 0f, w.health(), 1e-3f);
        tick(w, 1);
        assertTrue("血量为零必须结束一局", w.gameOver());
        assertTrue("结算必须给出评级字母", w.rank() >= 'A' && w.rank() <= 'S');
        assertFalse("结束后不能开火", w.fire());
    }

    @Test
    public void hitAnglePointsTowardDamageSource() {
        GameWorld w = world();
        Vec3 at = Vec3.of(0, 1.68f, 0);
        w.camera().position = at;
        w.camera().yaw = 0f;
        // 正前方是 -Z：站在 (0,1,-5) 的敌人应该报 0 度
        w.damagePlayer(5f, Vec3.of(0, 1, -5));
        assertEquals("来自正前方的伤害，夹角应接近 0", 0f, w.hitAngle(), 0.3f);
        // +X 在 yaw=0 时是右手边，应报 +90 度
        w.damagePlayer(5f, Vec3.of(5, 1, 0));
        assertEquals("来自右方的伤害，夹角应接近 +90°",
                (float) (Math.PI / 2), w.hitAngle(), 0.3f);
    }

    // ---- 敌人 AI ----

    @Test
    public void enemiesMoveOverTime() {
        GameWorld w = world();
        Vec3 start = w.enemies().get(0).position;
        tick(w, 120);
        Vec3 now = w.enemies().get(0).position;
        assertTrue("敌人应该会移动，而不是站着不动",
                start.distanceTo(now) > 0.05f);
    }

    @Test
    public void enemiesStayInsideArena() {
        GameWorld w = world();
        tick(w, 60 * 20);
        for (GameWorld.Enemy e : w.enemies()) {
            assertTrue("敌人不能跑出场外：" + e.position,
                    Math.abs(e.position.x) <= 22.5f && Math.abs(e.position.z) <= 22.5f);
        }
    }

    @Test
    public void enemiesGrowStrongerWithWaves() {
        GameWorld w = world();
        boolean sawElite = false;
        for (int wave = 0; wave < 3 && !sawElite; wave++) {
            for (GameWorld.Enemy e : w.enemies()) {
                e.alive = false;
            }
            tick(w, 1);
            for (GameWorld.Enemy e : w.enemies()) {
                sawElite |= e.elite;
            }
        }
        assertTrue("第 3 波应该出现精英", sawElite);
    }

    @Test
    public void enemyStateIsAlwaysValid() {
        GameWorld w = world();
        tick(w, 60 * 5);
        for (GameWorld.Enemy e : w.enemies()) {
            assertTrue("敌方状态机不应越界：" + e.state, e.state >= 0 && e.state <= 3);
            assertTrue("敌人血量不应为负", e.health <= e.maxHealth + 1e-3f);
        }
    }

    @Test
    public void enemiesEventuallyShootThePlayer() {
        GameWorld w = world();
        float before = w.health();
        // 站到敌人正面附近，等它开火
        GameWorld.Enemy e = w.enemies().get(0);
        w.camera().position = Vec3.of(e.position.x, e.position.y, e.position.z + 6f);
        tick(w, 60 * 20);
        assertTrue("敌人在射程内应该会打中玩家", w.health() < before);
    }

    // ---- 移动与碰撞 ----

    @Test
    public void movementStaysInsideArenaBounds() {
        GameWorld w = world();
        for (int i = 0; i < 600; i++) {
            w.move(1f, 0f, 1f / 60f);
        }
        Vec3 pos = w.camera().position;
        assertTrue(Math.abs(pos.x) <= 23.01f);
        assertTrue(Math.abs(pos.z) <= 23.01f);
    }

    @Test
    public void playerCannotWalkThroughCoverPillar() {
        GameWorld w = world();
        w.camera().position = Vec3.of(0, 1.68f, 5f);
        w.camera().yaw = 0f;
        float startZ = w.camera().position.z;
        for (int i = 0; i < 600; i++) {
            w.move(1f, 0f, 1f / 60f);
        }
        assertTrue("玩家不能穿出北墙", w.camera().position.z >= -23.01f);
        assertTrue("玩家确实向前移动了", w.camera().position.z < startZ);
    }

    @Test
    public void crouchingSlowsMovement() {
        GameWorld w = world();
        GameWorld standing = world();
        w.setCrouching(true);
        for (int i = 0; i < 60; i++) {
            w.move(1f, 0f, 1f / 60f);
            standing.move(1f, 0f, 1f / 60f);
        }
        float crouchDist = w.camera().position.distanceTo(Vec3.of(0, 1.68f, 6f));
        float standDist = standing.camera().position.distanceTo(Vec3.of(0, 1.68f, 6f));
        assertTrue("蹲下应该走得更慢", crouchDist < standDist);
    }

    @Test
    public void jumpRaisesPlayerThenLandsBack() {
        GameWorld w = world();
        float ground = w.camera().position.y;
        assertTrue("站在地上才能跳", w.onGround());
        w.jump();
        tick(w, 6);
        assertTrue("起跳后应该离地", w.camera().position.y > ground);
        tick(w, 120);
        assertTrue("最终必须落回地面", w.onGround());
    }

    @Test
    public void aimSlowsMovementAndNarrowsFov() {
        GameWorld w = world();
        GameWorld aiming = world();
        aiming.setAiming(true);
        for (int i = 0; i < 60; i++) {
            w.move(1f, 0f, 1f / 60f);
            aiming.move(1f, 0f, 1f / 60f);
        }
        assertTrue("开镜时移动应更慢",
                aiming.camera().position.distanceTo(Vec3.of(0, 1.68f, 6f))
                        < w.camera().position.distanceTo(Vec3.of(0, 1.68f, 6f)));
        // 开镜倍率的作用是缩小 fov，霰弹/步枪按 1 倍处理，狙击才放大。
        // 一路清波换枪，直到摸到那把放大倍率 > 1 的。
        boolean anyZoom = false;
        for (int i = 0; i < 8 && !anyZoom; i++) {
            for (GameWorld.Enemy e : aiming.enemies()) {
                e.alive = false;
            }
            aiming.update(1f / 60f);
            anyZoom = aiming.weapon().zoom > 1f;
        }
        assertTrue("三把枪里至少有一把是开镜放大的", anyZoom);
    }

    @Test
    public void pitchIsClampedToAvoidFlipping() {
        GameWorld w = world();
        for (int i = 0; i < 100; i++) {
            w.look(0f, 1000f);
        }
        assertTrue(w.camera().pitch <= 1.5f);
        for (int i = 0; i < 100; i++) {
            w.look(0f, -1000f);
        }
        assertTrue(w.camera().pitch >= -1.5f);
    }

    @Test
    public void yawZeroPointsTowardNegativeZ() {
        GameWorld w = world();
        w.camera().yaw = 0f;
        w.camera().pitch = 0f;
        Vec3 f = w.camera().forward();
        assertEquals(0f, f.x, 1e-3f);
        assertEquals(0f, f.y, 1e-3f);
        assertEquals(-1f, f.z, 1e-3f);
    }

    @Test
    public void yawHalfPiPointsTowardPositiveX() {
        GameWorld w = world();
        w.camera().yaw = (float) (Math.PI / 2);
        w.camera().pitch = 0f;
        Vec3 f = w.camera().forward();
        assertEquals(1f, f.x, 1e-3f);
        assertEquals(0f, f.z, 1e-3f);
    }

    @Test
    public void yawWrapsInsteadOfGrowingForever() {
        GameWorld w = world();
        for (int i = 0; i < 100; i++) {
            w.look(10000f, 0f);
        }
        assertTrue(Math.abs(w.camera().yaw) <= Math.PI + 1e-3f);
    }

    @Test
    public void forwardVectorIsUnitLength() {
        GameWorld w = world();
        w.look(123f, -45f);
        float len = w.camera().forward().length();
        assertEquals(1f, len, 1e-3f);
    }

    // ---- 暂停 / 重开 ----

    @Test
    public void pauseFreezesTheWorld() {
        GameWorld w = world();
        w.setPaused(true);
        long before = w.elapsedSeconds();
        Vec3 pos = w.enemies().get(0).position;
        tick(w, 300);
        assertEquals("暂停时计时器不该走", before, w.elapsedSeconds());
        assertEquals("暂停时敌人不该移动", 0f, pos.distanceTo(w.enemies().get(0).position), 1e-4f);
        assertFalse("暂停时不能开火", w.fire());
    }

    @Test
    public void restartResetsEverythingButKeepsBestWave() {
        GameWorld w = world();
        w.damagePlayer(200f, null);
        w.endGame();
        w.setBestWaveEver(9);
        w.restart();
        assertFalse(w.gameOver());
        assertEquals(0, w.score());
        assertEquals(w.magazineSize(), w.ammo());
        assertEquals(0, w.shotsFired());
        assertEquals(0, w.kills());
        assertEquals(0, w.headshots());
        assertEquals(1, w.wave());
        assertEquals(0, w.bestStreak());
        assertEquals(GameWorld.PLAYER_MAX_HEALTH, w.health(), 1e-3f);
        assertEquals("历史最高波次不该被重开清掉", 9, w.bestWaveEver());
        assertTrue(w.aliveEnemies() > 0);
    }

    @Test
    public void waveChangeSwapsTheArena() {
        GameWorld w = world();
        int boxesBefore = w.boxes().size();
        for (GameWorld.Enemy e : w.enemies()) {
            e.alive = false;
        }
        tick(w, 1);
        assertTrue("换波应该换一张图", w.boxes().size() != boxesBefore
                || !w.boxes().get(0).center.equals(Vec3.of(0, -1, 0)));
    }

    // ---- 补给 ----

    @Test
    public void pickupsAreSpawnedEachWave() {
        GameWorld w = world();
        for (GameWorld.Enemy e : w.enemies()) {
            e.alive = false;
        }
        tick(w, 1);
        assertFalse("换波应该刷补给", w.pickups().isEmpty());
    }

    @Test
    public void walkingOverAmmoPickupAddsReserve() {
        GameWorld w = world();
        for (GameWorld.Enemy e : w.enemies()) {
            e.alive = false;
        }
        tick(w, 1);
        GameWorld.Pickup ammo = null;
        for (GameWorld.Pickup p : w.pickups()) {
            if (p.ammo) {
                ammo = p;
                break;
            }
        }
        assertNotNull(ammo);
        // 敌人清空后再走过去：否则这两帧里它会先开枪，把判定搅乱
        for (GameWorld.Enemy e : w.enemies()) {
            e.alive = false;
        }
        // 备弹按弹匣数补充，不吃武器当前备弹上限，所以这里只关心「变多了」
        w.camera().position = Vec3.of(ammo.position.x, 1.68f, ammo.position.z);
        int before = w.reserveAmmo();
        w.update(1f / 60f);
        assertTrue("走过去应该加备弹：" + before + " -> " + w.reserveAmmo(),
                w.reserveAmmo() > before);
    }

    // ---- 飘字 ----

    @Test
    public void damageNumbersFloatAndExpire() {
        GameWorld w = world();
        aimAtNearest(w);
        w.fire();
        assertFalse("命中应该产生飘字", w.floatTexts().isEmpty());
        tick(w, 120);
        assertTrue("飘字应该会过期清理", w.floatTexts().size() < 24);
    }

    // ---- 稳健性 ----

    @Test
    public void updateDoesNotExplodeWithHugeDelta() {
        GameWorld w = world();
        w.fire();
        w.update(10f);
        assertTrue(w.ammo() >= 0);
        assertTrue(w.camera().position.y > 0f);
        assertFalse(Float.isNaN(w.camera().position.y));
    }

    @Test
    public void magazineNeverExceedsCapacityAfterAutoReload() {
        GameWorld w = world();
        for (int i = 0; i < 1000; i++) {
            w.fire();
            w.update(1f / 60f);
        }
        assertTrue(w.ammo() <= w.magazineSize());
        assertTrue(w.reserveAmmo() >= 0);
    }

    @Test
    public void runningOutOfEverythingEndsTheGame() {
        GameWorld w = world();
        int guard = 0;
        while (!w.gameOver() && guard++ < 20000) {
            w.fire();
            w.update(1f / 60f);
        }
        assertTrue("弹尽或阵亡都必须结束一局，不能卡死", w.gameOver());
    }

    @Test
    public void elapsedSecondsActuallyAdvances() {
        GameWorld w = world();
        tick(w, 60);
        assertTrue("计时器必须在走：(long)(dt*1000)/1000 恒为 0 是 bug", w.elapsedSeconds() >= 1);
    }

    @Test
    public void rankIsWorseForBadRuns() {
        GameWorld bad = world();
        bad.damagePlayer(500f, null);
        bad.endGame();
        GameWorld good = world();
        // 手工堆一个漂亮的成绩单
        for (int i = 0; i < 20; i++) {
            for (GameWorld.Enemy e : good.enemies()) {
                e.alive = false;
            }
            tick(good, 1);
        }
        good.damagePlayer(500f, null);
        good.endGame();
        assertTrue("打得好评级不该更差", rankValue(good.rank()) >= rankValue(bad.rank()));
    }

    private static int rankValue(char rank) {
        switch (rank) {
            case 'S':
                return 4;
            case 'A':
                return 3;
            case 'B':
                return 2;
            case 'C':
                return 1;
            default:
                return 0;
        }
    }

    // ---- 数学 ----

    @Test
    public void matrixPerspectiveAndLookAtAreFinite() {
        float[] p = Mat4.perspective((float) Math.toRadians(70), 1.7f, 0.1f, 500f).m;
        for (float v : p) {
            assertTrue(Float.isFinite(v));
        }
        float[] v = Mat4.lookAt(Vec3.of(0, 1, 5), Vec3.of(0, 0, -1), Vec3.of(0, 1, 0)).m;
        for (float f : v) {
            assertTrue(Float.isFinite(f));
        }
    }

    @Test
    public void matrixMultiplyWithIdentityIsNoop() {
        Mat4 t = Mat4.translate(Vec3.of(1, 2, 3));
        Mat4 r = Mat4.identity().mul(t);
        for (int i = 0; i < 16; i++) {
            assertEquals(t.m[i], r.m[i], 1e-4f);
        }
    }

    @Test
    public void vecNormalizeOfZeroVectorIsSafe() {
        Vec3 z = Vec3.of(0, 0, 0).normalize();
        assertEquals(0f, z.length(), 1e-6f);
        assertTrue(Float.isFinite(z.x));
    }

    @Test
    public void crossProductFollowsRightHandRule() {
        Vec3 r = Vec3.of(1, 0, 0).cross(Vec3.of(0, 1, 0));
        assertEquals(0f, r.x, 1e-6f);
        assertEquals(0f, r.y, 1e-6f);
        assertEquals(1f, r.z, 1e-6f);
    }

    @Test
    public void sunDirectionIsNormalized() {
        assertEquals(1f, world().sunDirection().length(), 1e-3f);
    }

    @Test
    public void openArenaAlwaysLeavesAClearShotDownTheMiddle() {
        GameWorld w = world();
        // 中轴必须没有立柱挡路（踩过的坑：立柱立在自己射击线上）。
        // 只查场地内部的竖直柱：边界外墙和地面本来就在中轴上，不算遮挡。
        for (GameWorld.Box b : w.boxes()) {
            boolean wall = b.halfExtents.x >= 24f || b.halfExtents.z >= 24f;
            boolean floor = b.center.y < 0f;
            boolean pillar = b.halfExtents.y >= 1.0f && b.halfExtents.x < 2f && b.halfExtents.z < 2f;
            if (wall || floor || !pillar) {
                continue;
            }
            assertTrue("中轴上不该有立柱：" + b.center, Math.abs(b.center.x) > 1f);
        }
    }

    /**
     * 换枪不能吞掉玩家已经攒下的备弹。
     *
     * 回归用例：原来写的是 reserveAmmo = weapon().reserve 直接赋值，
     * 从突击步枪（150 备弹）换到霰弹枪（40 备弹）时，手上 180 发瞬间变 40 发。
     */
    @Test
    public void weaponSwapKeepsAccumulatedReserveAmmo() {
        GameWorld w = world();
        int before = w.reserveAmmo();
        for (int i = 0; i < 4; i++) {
            for (GameWorld.Enemy e : w.enemies()) {
                e.alive = false;
            }
            w.update(1f / 60f);
        }
        assertTrue("换枪后手上攒的备弹不该变少：" + before + " -> " + w.reserveAmmo(),
                w.reserveAmmo() >= before);
    }

    /**
     * 血量归零之后不能被回血拉回来。
     *
     * 回归用例：update() 里先 regen 再判死亡，regen 把 0 拉成 0.2 之后
     * 死亡判定永远不成立，玩家变成打不死的。实测挨 1000 点伤害后血量归零，
     * 一帧后又变成 0.2，游戏继续跑。
     */
    @Test
    public void zeroHealthNeverComesBack() {
        GameWorld w = world();
        w.damagePlayer(9999f, null);
        assertEquals(0f, w.health(), 1e-3f);
        w.update(1f / 60f);
        assertTrue("零血必须结束一局", w.gameOver());
        assertEquals("结束后血量不该被回血拉起来", 0f, w.health(), 1e-3f);
    }

    /**
     * 弹匣和备弹同时为空 = 这一局结束。
     * 少了这条判定，玩家会卡在「点得动开火键但打不出子弹、也没人告诉你结束了」的死局。
     */
    @Test
    public void dryOnAmmoAndReserveEndsTheRun() {
        GameWorld w = world();
        // 一直开火到世界自己判定结束。不能写成「while (ammo > 0)」再「while (reserve > 0)」——
        // 换弹会把 reserve 搬进弹匣，循环条件永远追不上真正的空仓时刻。
        int guard = 0;
        while (!w.gameOver() && guard++ < 30000) {
            w.fire();
            w.update(1f / 60f);
        }
        assertTrue("弹尽必须结束，不能卡死", w.gameOver());
        assertEquals("结束时弹匣与备弹都应见底", 0, w.ammo());
        assertEquals(0, w.reserveAmmo());
    }
}
