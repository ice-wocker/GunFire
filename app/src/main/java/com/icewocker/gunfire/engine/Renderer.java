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
 * 每帧做三件事：清屏、按相机画世界、画程序生成的效果层。
 *
 * 视觉上刻意只做三件事，但它们决定了「像不像一个游戏」：
 *   1. 天空是一个真正的大球（渐变着色），不是纯色清屏——有了地平线就有空间感
 *   2. 距离雾把远处收进背景色——避免几何体边缘在远处硬切
 *   3. 每个物体脚下有一块贴地的深色四边形当阴影——比任何贴图都便宜，但立刻有落点
 *
 * 光照是单方向光 + 环境光的 Lambert 模型，够用，且不需要材质文件。
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
            + "uniform float uEmissive;\n"
            + "uniform float uFogDensity;\n"
            + "uniform vec3 uFogColor;\n"
            + "varying vec3 vNormal;\n"
            + "varying vec3 vWorld;\n"
            + "void main() {\n"
            + "  vec3 n = normalize(vNormal);\n"
            + "  float diff = max(dot(n, normalize(-uLightDir)), 0.0);\n"
            + "  vec3 viewDir = normalize(uEye - vWorld);\n"
            + "  float rim = pow(1.0 - max(dot(n, viewDir), 0.0), 3.0) * 0.25 * (1.0 - uEmissive);\n"
            + "  vec3 lit = uColor * (0.35 + 0.65 * diff) + vec3(rim);\n"
            + "  lit = mix(lit, uColor, uEmissive);\n"
            + "  float dist = length(uEye - vWorld);\n"
            + "  float fog = 1.0 - exp(-dist * dist * uFogDensity * uFogDensity);\n"
            + "  fog *= (1.0 - uEmissive);\n"
            + "  gl_FragColor = vec4(mix(lit, uFogColor, clamp(fog, 0.0, 1.0)), 1.0);\n"
            + "}\n";

    private static final String SKY_VERTEX_SRC =
            "uniform mat4 uMvp;\n"
            + "attribute vec3 aPos;\n"
            + "varying vec3 vDir;\n"
            + "void main() {\n"
            + "  vDir = normalize(aPos);\n"
            + "  gl_Position = uMvp * vec4(aPos, 1.0);\n"
            + "}\n";

    private static final String SKY_FRAGMENT_SRC =
            "precision mediump float;\n"
            + "varying vec3 vDir;\n"
            + "uniform vec3 uTop;\n"
            + "uniform vec3 uBottom;\n"
            + "uniform vec3 uSun;\n"
            + "void main() {\n"
            + "  float h = clamp(vDir.y * 0.5 + 0.5, 0.0, 1.0);\n"
            + "  vec3 col = mix(uBottom, uTop, pow(h, 0.8));\n"
            + "  float sun = pow(max(dot(normalize(vDir), normalize(-uSun)), 0.0), 64.0);\n"
            + "  col += vec3(1.0, 0.85, 0.6) * sun * 0.7;\n"
            + "  gl_FragColor = vec4(col, 1.0);\n"
            + "}\n";

    /** 天空颜色：比纯黑有信息量，又不会把 HUD 的对比吃掉。 */
    private static final Vec3 SKY_TOP = Vec3.of(0.05f, 0.08f, 0.14f);
    private static final Vec3 SKY_BOTTOM = Vec3.of(0.13f, 0.15f, 0.20f);

    private Shader shader;
    private Shader skyShader;
    private Mesh cube;
    private Mesh sphere;
    private Mesh quad;
    private int uMvp;
    private int uModel;
    private int uColor;
    private int uLightDir;
    private int uEye;
    private int uEmissive;
    private int uFogDensity;
    private int uFogColor;
    private int aPos;
    private int aNormal;
    private int skyMvp;
    private int skyAPos;
    private int skyTop;
    private int skyBottom;
    private int skySun;

    private GameWorld world;
    private TouchControls controls;
    private long lastFrameNanos;
    private int viewportW = 1;
    private int viewportH = 1;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] viewProj = new float[16];
    private final float[] mvp = new float[16];
    private final float[] model = new float[16];

    private float fps;

    public void attachWorld(GameWorld world) {
        this.world = world;
    }

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

    /** 切回前台时重置帧时钟，否则第一帧的 dt 会是切走那一刻的间隔。 */
    public void resetFrameClock() {
        lastFrameNanos = 0L;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(SKY_BOTTOM.x, SKY_BOTTOM.y, SKY_BOTTOM.z, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);

        shader = Shader.create(VERTEX_SRC, FRAGMENT_SRC);
        uMvp = shader.uniform("uMvp");
        uModel = shader.uniform("uModel");
        uColor = shader.uniform("uColor");
        uLightDir = shader.uniform("uLightDir");
        uEye = shader.uniform("uEye");
        uEmissive = shader.uniform("uEmissive");
        uFogDensity = shader.uniform("uFogDensity");
        uFogColor = shader.uniform("uFogColor");
        aPos = shader.attrib("aPos");
        aNormal = shader.attrib("aNormal");

        skyShader = Shader.create(SKY_VERTEX_SRC, SKY_FRAGMENT_SRC);
        skyMvp = skyShader.uniform("uMvp");
        skyAPos = skyShader.attrib("aPos");
        skyTop = skyShader.uniform("uTop");
        skyBottom = skyShader.uniform("uBottom");
        skySun = skyShader.uniform("uSun");

        cube = Mesh.unitCube();
        sphere = Mesh.sphere(12, 8);
        quad = Mesh.unitQuad();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewportW = Math.max(1, width);
        viewportH = Math.max(1, height);
        GLES20.glViewport(0, 0, viewportW, viewportH);
        float aspect = (float) viewportW / viewportH;
        float fov = fieldOfView();
        System.arraycopy(Mat4.perspective((float) Math.toRadians(fov), aspect, 0.1f, 500f).m, 0,
                proj, 0, 16);
    }

    /** 开镜时缩小视场角，画面自然被"推近"。 */
    private float fieldOfView() {
        // world 可能还没 attach（GL 线程的 onSurfaceChanged 可能先于 attachWorld 回调），
        // 这里必须判空，否则一进游戏就是 NPE。
        if (world != null) {
            return 70f / world.weapon().zoom;
        }
        return 70f;
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

        float fov = fieldOfView();
        float aspect = (float) viewportW / viewportH;
        System.arraycopy(Mat4.perspective((float) Math.toRadians(fov), aspect, 0.1f, 500f).m, 0,
                proj, 0, 16);

        Vec3 eye = world.camera().eye();
        Vec3 forward = world.camera().forward();
        System.arraycopy(Mat4.lookAt(eye, forward, Vec3.of(0, 1, 0)).m, 0, view, 0, 16);
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0);

        // 雾色取天空下缘，远处几何体自然融进背景，不会出现硬边
        Vec3 fog = SKY_BOTTOM;

        drawSky(eye);

        shader.use();
        GLES20.glUniform1f(uFogDensity, 0.0085f);
        GLES20.glUniform3f(uFogColor, fog.x, fog.y, fog.z);
        GLES20.glUniform3f(uLightDir, world.sunDirection().x, world.sunDirection().y,
                world.sunDirection().z);
        GLES20.glUniform3f(uEye, eye.x, eye.y, eye.z);
        GLES20.glUniform1f(uEmissive, 0f);

        // 静态世界与敌人共用一次 mvp 上传
        for (GameWorld.Box b : world.boxes()) {
            drawBox(b);
        }
        drawShadows();
        for (GameWorld.Enemy e : world.enemies()) {
            if (e.alive) {
                drawEnemy(e);
            }
        }
        drawPickups();
        if (world.muzzleFlash() > 0.01f) {
            drawMuzzleFlash(world.camera());
        }

        long elapsed = System.nanoTime() - start;
        float frame = elapsed / 1_000_000f;
        float instant = 1000f / Math.max(frame, 0.01f);
        fps = fps <= 0f ? instant : fps * 0.9f + instant * 0.1f;
    }

    /** 天空球：跟随相机平移，半径永远在远裁剪面内侧，所以看上去无限远。 */
    private void drawSky(Vec3 eye) {
        skyShader.use();
        float[] skyModel = new float[16];
        Mat4 t = Mat4.translate(eye).mul(Mat4.scale(Vec3.of(300f, 300f, 300f)));
        System.arraycopy(t.m, 0, skyModel, 0, 16);
        float[] skyM = new float[16];
        Matrix.multiplyMM(skyM, 0, viewProj, 0, skyModel, 0);
        GLES20.glUniformMatrix4fv(skyMvp, 1, false, skyM, 0);
        GLES20.glUniform3f(skyTop, SKY_TOP.x, SKY_TOP.y, SKY_TOP.z);
        GLES20.glUniform3f(skyBottom, SKY_BOTTOM.x, SKY_BOTTOM.y, SKY_BOTTOM.z);
        Vec3 sun = world.sunDirection();
        GLES20.glUniform3f(skySun, sun.x, sun.y, sun.z);
        // 天空球从里面看，必须关掉背面剔除，否则整个天空消失
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDepthMask(false);
        sphere.drawSky(skyAPos);
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
    }

    private void setModel(Vec3 translate, Vec3 scale, float[] out) {
        Mat4 t = Mat4.translate(translate).mul(Mat4.scale(scale));
        System.arraycopy(t.m, 0, out, 0, 16);
    }

    private void drawMesh(Mesh mesh, Vec3 translate, Vec3 scale, float r, float g, float b) {
        setModel(translate, scale, model);
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform3f(uColor, r, g, b);
        mesh.draw(aPos, aNormal);
    }

    private void drawBox(GameWorld.Box b) {
        drawMesh(cube, b.center, b.halfExtents, b.colorR, b.colorG, b.colorB);
    }

    /**
     * 敌人：躯干 + 头 + 双腿，比一个大方块有辨识度。
     * 精英加一层橙色的"护板"，远看就能区分。
     */
    private void drawEnemy(GameWorld.Enemy e) {
        float bob = (float) Math.sin(e.walkPhase) * 0.06f;
        float scale = e.elite ? 1.25f : 1f;
        Vec3 base = Vec3.of(e.position.x, e.position.y + bob, e.position.z);
        float flash = e.hitFlash > 0f ? 1f : 0f;

        if (flash > 0f) {
            drawMesh(cube, base, Vec3.of(0.4f * scale, 0.55f * scale, 0.26f * scale),
                    1f, 1f, 1f);
            drawMesh(cube, base.add(Vec3.of(0, 0.62f * scale, 0)),
                    Vec3.of(0.24f * scale, 0.22f * scale, 0.24f * scale), 1f, 1f, 1f);
            return;
        }

        // 血量越低越红，一眼能看出该先打谁
        float hp = GameWorld.clamp(e.health / e.maxHealth, 0f, 1f);
        float r = 1f - hp * 0.4f;
        float g = 0.22f + hp * 0.5f;
        float bl = 0.18f + hp * 0.1f;
        if (e.elite) {
            r = Math.min(1f, r + 0.2f);
            g = Math.min(1f, g + 0.25f);
        }
        drawMesh(cube, base, Vec3.of(0.4f * scale, 0.55f * scale, 0.26f * scale), r, g, bl);
        drawMesh(cube, base.add(Vec3.of(0, 0.62f * scale, 0)),
                Vec3.of(0.24f * scale, 0.22f * scale, 0.24f * scale), r * 0.95f, g * 1.05f, bl);
        float legSwing = (float) Math.sin(e.walkPhase) * 0.12f;
        drawMesh(cube, base.add(Vec3.of(-0.18f * scale, -0.72f * scale, legSwing)),
                Vec3.of(0.13f * scale, 0.4f * scale, 0.15f * scale), 0.25f, 0.26f, 0.30f);
        drawMesh(cube, base.add(Vec3.of(0.18f * scale, -0.72f * scale, -legSwing)),
                Vec3.of(0.13f * scale, 0.4f * scale, 0.15f * scale), 0.25f, 0.26f, 0.30f);
        if (e.elite) {
            drawMesh(cube, base.add(Vec3.of(0, 0.1f * scale, 0)),
                    Vec3.of(0.44f * scale, 0.2f * scale, 0.3f * scale), 0.9f, 0.6f, 0.2f);
        }
        // 开火状态在头顶挂一小块警示色
        if (e.state == 2) {
            drawMesh(cube, base.add(Vec3.of(0, 0.95f * scale, 0)),
                    Vec3.of(0.08f, 0.08f, 0.08f), 1f, 0.4f, 0.3f);
        }
    }

    /**
     * 假阴影：每个实体脚下贴一块深色四边形。
     * 不是真的投影，但给出了「这东西站在地上」的信息，成本几乎为零。
     */
    private void drawShadows() {
        GLES20.glDepthMask(false);
        for (GameWorld.Enemy e : world.enemies()) {
            if (!e.alive) {
                continue;
            }
            float scale = e.elite ? 1.25f : 1f;
            drawMesh(quad, Vec3.of(e.position.x, 0.02f, e.position.z),
                    Vec3.of(0.55f * scale, 1f, 0.55f * scale), 0.10f, 0.11f, 0.13f);
        }
        GLES20.glDepthMask(true);
    }

    private void drawPickups() {
        for (GameWorld.Pickup p : world.pickups()) {
            float bob = (float) Math.sin(p.phase * 2.2f) * 0.1f;
            Vec3 at = Vec3.of(p.position.x, p.position.y + 0.35f + bob, p.position.z);
            Vec3 small = Vec3.of(0.18f, 0.18f, 0.18f);
            Vec3 big = Vec3.of(0.26f, 0.26f, 0.26f);
            // 自发光让补给在暗场里也显眼，不用加光源
            GLES20.glUniform1f(uEmissive, 1f);
            if (p.ammo) {
                drawMesh(cube, at, small, 0.35f, 0.75f, 1f);
                drawMesh(cube, at, big, 0.15f, 0.35f, 0.6f);
                drawMesh(cube, at, Vec3.of(0.26f, 0.09f, 0.09f), 0.9f, 0.95f, 1f);
            } else {
                drawMesh(cube, at, small, 0.4f, 1f, 0.55f);
                drawMesh(cube, at, Vec3.of(0.24f, 0.09f, 0.09f), 1f, 1f, 1f);
                drawMesh(cube, at, Vec3.of(0.09f, 0.24f, 0.09f), 0.4f, 1f, 0.55f);
            }
            GLES20.glUniform1f(uEmissive, 0f);
        }
    }

    /** 枪口火光：相机前方一小块亮面，自发光，不受雾影响。 */
    private void drawMuzzleFlash(GameWorld.Camera cam) {
        GLES20.glUniform1f(uEmissive, 1f);
        Vec3 pos = cam.eye().add(cam.forward().scale(0.9f))
                .add(cam.right().scale(0.22f)).add(Vec3.of(0, -0.16f, 0));
        float size = 0.06f + world.muzzleFlash() * 0.06f;
        drawMesh(cube, pos, Vec3.of(size, size, size), 1f, 0.85f, 0.4f);
        GLES20.glUniform1f(uEmissive, 0f);
    }
}
