package com.icewocker.gunfire.engine;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import com.icewocker.gunfire.game.GameWorld;
import com.icewocker.gunfire.game.TouchControls;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 场景渲染器。
 *
 * 每帧做三件事：清屏、按相机画世界、画 HUD 用的准星。
 * 光照是单方向光 + 环境光的 Lambert 模型——够用，且不需要材质文件。
 */
public final class Renderer implements GLSurfaceView.Renderer {

    private static final String VERTEX_SRC =
            "uniform mat4 uMvp;\n"
            + "uniform mat4 uModel;\n"
            + "attribute vec3 aPos;\n"
            + "attribute vec3 aNormal;\n"
            + "varying vec3 vNormal;\n"
            + "varying vec3 vWorld;\n"
            + "void main() {\n"
            + "  vec4 world = uModel * vec4(aPos, 1.0);\n"
            + "  vWorld = world.xyz;\n"
            + "  vNormal = normalize(mat3(uModel) * aNormal);\n"
            + "  gl_Position = uMvp * vec4(aPos, 1.0);\n"
            + "}\n";

    private static final String FRAGMENT_SRC =
            "precision mediump float;\n"
            + "uniform vec3 uColor;\n"
            + "uniform vec3 uLightDir;\n"
            + "uniform vec3 uEye;\n"
            + "varying vec3 vNormal;\n"
            + "varying vec3 vWorld;\n"
            + "void main() {\n"
            + "  vec3 n = normalize(vNormal);\n"
            + "  float diff = max(dot(n, normalize(-uLightDir)), 0.0);\n"
            + "  float rim = pow(1.0 - max(dot(n, normalize(uEye - vWorld)), 0.0), 3.0) * 0.25;\n"
            + "  vec3 lit = uColor * (0.35 + 0.65 * diff) + vec3(rim);\n"
            + "  gl_FragColor = vec4(lit, 1.0);\n"
            + "}\n";

    private Shader shader;
    private Mesh cube;
    private Mesh cylinder;
    private int uMvp;
    private int uModel;
    private int uColor;
    private int uLightDir;
    private int uEye;
    private int aPos;
    private int aNormal;

    private GameWorld world;
    private TouchControls controls;
    private long lastFrameNanos;
    private int viewportW = 1;
    private int viewportH = 1;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];

    /** 渲染耗时采样，用于 HUD 显示帧率。 */
    private float fps;

    public void attachWorld(GameWorld world) {
        this.world = world;
    }

    /**
     * 游戏循环的执行点。GL 渲染线程本身就是每帧回调的，
     * 所以这里既推进世界状态，又画这一帧——不需要额外线程。
     */
    public void attachControls(TouchControls controls) {
        this.controls = controls;
    }

    private float consumeDelta() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1f / 60f;
        }
        float dt = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        return GameWorld.clamp(dt, 0f, 0.05f);
    }

    public float fps() {
        return fps;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.06f, 0.07f, 0.09f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);

        shader = Shader.create(VERTEX_SRC, FRAGMENT_SRC);
        uMvp = shader.uniform("uMvp");
        uModel = shader.uniform("uModel");
        uColor = shader.uniform("uColor");
        uLightDir = shader.uniform("uLightDir");
        uEye = shader.uniform("uEye");
        aPos = shader.attrib("aPos");
        aNormal = shader.attrib("aNormal");

        cube = Mesh.unitCube();
        cylinder = Mesh.cylinder(12);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewportW = Math.max(1, width);
        viewportH = Math.max(1, height);
        GLES20.glViewport(0, 0, viewportW, viewportH);
        float aspect = (float) viewportW / viewportH;
        System.arraycopy(Mat4.perspective((float) Math.toRadians(70), aspect, 0.1f, 500f).m, 0,
                proj, 0, 16);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long start = System.nanoTime();
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (world == null) {
            return;
        }

        float dt = consumeDelta();
        if (controls != null) {
            controls.applyMovement(dt);
        }
        world.update(dt);

        Vec3 eye = world.camera().eye();
        Vec3 forward = world.camera().forward();
        System.arraycopy(Mat4.lookAt(eye, forward, Vec3.of(0, 1, 0)).m, 0, view, 0, 16);

        shader.use();
        GLES20.glUniform3f(uLightDir, world.sunDirection().x, world.sunDirection().y,
                world.sunDirection().z);
        GLES20.glUniform3f(uEye, eye.x, eye.y, eye.z);

        for (GameWorld.Box b : world.boxes()) {
            drawBox(b);
        }
        for (GameWorld.Target t : world.targets()) {
            if (t.alive) {
                drawTarget(t);
            }
        }
        if (world.muzzleFlash() > 0.01f) {
            drawMuzzleFlash(world.camera());
        }

        long elapsed = System.nanoTime() - start;
        float frame = elapsed / 1_000_000f;
        fps = fps <= 0f ? 1000f / Math.max(frame, 0.01f)
                : fps * 0.9f + (1000f / Math.max(frame, 0.01f)) * 0.1f;
    }

    private void drawBox(GameWorld.Box b) {
        float[] mvp = new float[16];
        float[] model = new float[16];
        Mat4 t = Mat4.translate(b.center).mul(Mat4.scale(b.halfExtents));
        System.arraycopy(t.m, 0, model, 0, 16);
        float[] combined = new float[16];
        premultiply(proj, view, combined);
        Matrix.multiplyMM(mvp, 0, combined, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform3f(uColor, b.colorR, b.colorG, b.colorB);
        cube.draw(aPos, aNormal);
    }

    private void drawTarget(GameWorld.Target t) {
        float[] mvp = new float[16];
        float[] model = new float[16];
        float bob = (float) Math.sin(t.phase) * 0.05f;
        Vec3 pos = Vec3.of(t.position.x, t.position.y + bob, t.position.z);
        Mat4 transform = Mat4.translate(pos).mul(Mat4.scale(Vec3.of(0.35f, 0.35f, 0.2f)));
        System.arraycopy(transform.m, 0, model, 0, 16);
        float[] combined = new float[16];
        premultiply(proj, view, combined);
        Matrix.multiplyMM(mvp, 0, combined, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        float hp = t.health / GameWorld.TARGET_MAX_HEALTH;
        if (t.hitFlash > 0f) {
            GLES20.glUniform3f(uColor, 1f, 1f, 1f);
        } else {
            GLES20.glUniform3f(uColor, 1f - hp * 0.35f, 0.25f + hp * 0.55f, 0.2f);
        }
        cube.draw(aPos, aNormal);
    }

    /** 枪口火光：相机前方一小块亮面，不做发光，靠纯色够看。 */
    private void drawMuzzleFlash(GameWorld.Camera cam) {
        Vec3 origin = cam.eye();
        Vec3 pos = origin.add(cam.forward().scale(0.9f)).add(
                cam.right().scale(0.22f)).add(Vec3.of(0, -0.16f, 0));
        float size = 0.06f + world.muzzleFlash() * 0.06f;
        float[] model = new float[16];
        Mat4 transform = Mat4.translate(pos).mul(Mat4.scale(Vec3.of(size, size, size)));
        System.arraycopy(transform.m, 0, model, 0, 16);
        float[] combined = new float[16];
        premultiply(proj, view, combined);
        float[] mvp = new float[16];
        Matrix.multiplyMM(mvp, 0, combined, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform3f(uColor, 1f, 0.85f, 0.4f);
        cube.draw(aPos, aNormal);
    }

    private static void premultiply(float[] a, float[] b, float[] out) {
        Matrix.multiplyMM(out, 0, a, 0, b, 0);
    }
}
