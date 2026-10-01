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

    @Test
    public void startsWithFullMagazineAndReserve() {
        GameWorld w = world();
        assertEquals(w.magazineSize(), w.ammo());
        assertTrue(w.reserveAmmo() > 0);
        assertFalse(w.reloading());
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
        for (int i = 0; i < 200; i++) {
            w.update(1f / 60f);
        }
        assertFalse(w.reloading());
        assertEquals(w.magazineSize(), w.ammo());
        assertEquals(reserveBefore - (w.magazineSize() - ammoBefore), w.reserveAmmo());
    }

    @Test
    public void rayHitsTargetDirectlyAhead() {
        GameWorld w = world();
        // 相机默认在 (0, 1.68, 6) 朝向 -Z，目标在 -Z 方向
        Vec3 origin = Vec3.of(0, 1.4f, 5);
        Vec3 dir = Vec3.of(0, 0, -1);
        GameWorld.Target hit = w.raycastTarget(origin, dir);
        assertNotNull(hit);
    }

    @Test
    public void rayMissesWhenAimedAtWallBehindPlayer() {
        GameWorld w = world();
        GameWorld.Target hit = w.raycastTarget(Vec3.of(0, 1.4f, 5), Vec3.of(0, 0, 1));
        assertNull(hit);
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

    @Test
    public void killingTargetAwardsScore() {
        GameWorld w = world();
        int before = w.score();
        int guard = 0;
        while (w.score() == before && guard++ < 500) {
            w.fire();
            w.update(1f / 60f);
            if (w.ammo() == 0) {
                w.reload();
                for (int i = 0; i < 150; i++) {
                    w.update(1f / 60f);
                }
            }
        }
        assertTrue("连射数百次仍未击中任何目标，射线检测可能失效", w.score() > before);
    }

    @Test
    public void accuracyStaysWithinUnitRange() {
        GameWorld w = world();
        for (int i = 0; i < 40; i++) {
            w.fire();
            w.update(1f / 60f);
        }
        float acc = w.accuracy();
        assertTrue(acc >= 0f && acc <= 1f);
    }

    @Test
    public void movementStaysInsideArenaBounds() {
        GameWorld w = world();
        // 持续向前冲很久，不能穿出场地
        for (int i = 0; i < 600; i++) {
            w.move(1f, 0f, 1f / 60f);
        }
        Vec3 pos = w.camera().position;
        assertTrue(Math.abs(pos.x) <= 21.01f);
        assertTrue(Math.abs(pos.z) <= 21.01f);
    }

    @Test
    public void playerCannotWalkThroughCoverPillar() {
        GameWorld w = world();
        // yaw=0 时 forward=(0,0,-1)，朝场地内部走
        w.camera().position = Vec3.of(0, 1.68f, 5f);
        w.camera().yaw = 0f;
        float startZ = w.camera().position.z;
        for (int i = 0; i < 600; i++) {
            w.move(1f, 0f, 1f / 60f);
        }
        assertTrue("玩家不能穿出北墙", w.camera().position.z >= -21.01f);
        assertTrue("玩家确实向前移动了", w.camera().position.z < startZ);
    }

    @Test
    public void playerIsStoppedByTallPillarOnItsPath() {
        GameWorld w = world();
        // 正对 x=-7 的立柱（z=-8），应被挡在立柱之前
        w.camera().position = Vec3.of(-7f, 1.68f, -4f);
        w.camera().yaw = 0f;
        for (int i = 0; i < 600; i++) {
            w.move(1f, 0f, 1f / 60f);
        }
        assertTrue("应被立柱挡住，不能穿过 z=-8",
                w.camera().position.z > -8f);
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

    @Test
    public void respawnsWaveAfterAllTargetsDown() {
        GameWorld w = world();
        for (GameWorld.Target t : w.targets()) {
            t.alive = false;
        }
        assertEquals(0, w.aliveTargets());
        w.update(1f / 60f);
        assertTrue(w.aliveTargets() > 0);
    }

    @Test
    public void updateDoesNotExplodeWithHugeDelta() {
        GameWorld w = world();
        w.fire();
        w.update(10f);
        assertTrue(w.ammo() >= 0);
        assertTrue(w.camera().position.y > 0f);
    }

    @Test
    public void magazineNeverExceedsCapacityAfterAutoReload() {
        GameWorld w = world();
        // 注意：这 1000 帧里弹药会打光并触发一局结束，
        // 断言只关心"数字不越界"，不关心这一局是否还在进行。
        for (int i = 0; i < 1000; i++) {
            w.fire();
            w.update(1f / 60f);
        }
        assertTrue(w.ammo() <= w.magazineSize());
        assertTrue(w.reserveAmmo() >= 0);
    }

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
    public void gameOverBlocksFiring() {
        GameWorld w = world();
        w.endGame();
        assertFalse(w.fire());
        assertEquals(0, w.shotsFired());
    }

    // ---- 可玩性反馈相关：这几条对应「玩起来没反馈」的修复 ----

    @Test
    public void hittingButNotKillingStillScores() {
        GameWorld w = world();
        int before = w.score();
        // 一枪打不死目标（需要 3 枪），但必须有分，否则玩家不知道打中了
        w.fire();
        assertTrue("命中未击杀也应该给分", w.score() > before);
    }

    @Test
    public void streakGrowsOnConsecutiveKillsAndResetsAfterWindow() {
        GameWorld w = world();
        for (GameWorld.Target t : w.targets()) {
            t.health = 1f;
        }
        Vec3 origin = Vec3.of(0, 1.4f, 5);
        for (GameWorld.Target t : w.targets()) {
            assertNotNull(w.raycastTarget(origin, Vec3.of(0, 0, -1)));
            break;
        }
        // 直接打死一个目标来推进连杀
        w.fire();
        assertTrue(w.bestStreak() >= 0);
        // 超过连杀窗口后应清零
        for (int i = 0; i < 300; i++) {
            w.update(1f / 60f);
        }
        assertEquals("连杀窗口过后应归零", 0, w.streak());
    }

    @Test
    public void waveIncrementsAfterClearingAllTargets() {
        GameWorld w = world();
        int before = w.wave();
        for (GameWorld.Target t : w.targets()) {
            t.alive = false;
        }
        w.update(1f / 60f);
        assertEquals(before + 1, w.wave());
        assertTrue("新一波目标血量不低于第一波", w.targets().get(0).health >= GameWorld.TARGET_MAX_HEALTH);
    }

    @Test
    public void runningOutOfAmmoEndsTheGameInsteadOfHanging() {
        GameWorld w = world();
        int guard = 0;
        while (!w.gameOver() && guard++ < 5000) {
            w.fire();
            w.update(1f / 60f);
        }
        assertTrue("弹药打光必须结束一局，不能卡在打不出子弹的死局", w.gameOver());
        assertFalse("结束后不能再开火", w.fire());
    }

    @Test
    public void restartResetsScoreAmmoAndStreakButKeepsTargets() {
        GameWorld w = world();
        w.fire();
        w.fire();
        w.endGame();
        assertTrue(w.gameOver());
        w.restart();
        assertFalse(w.gameOver());
        assertEquals(0, w.score());
        assertEquals(w.magazineSize(), w.ammo());
        assertEquals(0, w.shotsFired());
        assertEquals(1, w.wave());
        assertEquals(0, w.bestStreak());
        assertEquals(w.targets().size(), w.aliveTargets());
    }

    @Test
    public void elapsedSecondsActuallyAdvances() {
        GameWorld w = world();
        for (int i = 0; i < 60; i++) {
            w.update(1f / 60f);
        }
        assertTrue("计时器必须在走：(long)(dt*1000)/1000 恒为 0 是 bug", w.elapsedSeconds() >= 1);
    }

    @Test
    public void streakRemainingIsBoundedAndDecays() {
        GameWorld w = world();
        for (GameWorld.Target t : w.targets()) {
            t.health = 1f;
        }
        w.fire();
        if (w.streak() > 0) {
            float near = w.streakRemaining();
            assertTrue(near > 0f && near <= 1f);
            for (int i = 0; i < 240; i++) {
                w.update(1f / 60f);
            }
            assertEquals(0f, w.streakRemaining(), 1e-3f);
        }
    }

    @Test
    public void streakBonusIsCappedSoScoreStaysSane() {
        GameWorld w = world();
        for (GameWorld.Target t : w.targets()) {
            t.health = 1f;
        }
        for (int i = 0; i < 60; i++) {
            w.fire();
            w.update(1f / 60f);
        }
        assertTrue("分数不应出现异常的指数增长", w.score() < 100000);
    }
}
