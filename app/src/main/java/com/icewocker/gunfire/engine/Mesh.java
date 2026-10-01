package com.icewocker.gunfire.engine;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 静态三角网格。顶点格式：位置(3) + 法线(3)，交错存放。
 * 所有几何体共用一份着色器，靠模型矩阵和颜色区分。
 */
public final class Mesh {
    public static final int FLOATS_PER_VERTEX = 6;

    private final int vbo;
    private final int vertexCount;

    private Mesh(int vbo, int vertexCount) {
        this.vbo = vbo;
        this.vertexCount = vertexCount;
    }

    public static Mesh from(float[] data) {
        FloatBuffer buf = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buf.put(data);
        buf.position(0);
        int[] handle = new int[1];
        GLES20.glGenBuffers(1, handle, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, handle[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.length * 4, buf, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        return new Mesh(handle[0], data.length / FLOATS_PER_VERTEX);
    }

    /** 轴对齐立方体，边长为 2，中心在原点。可直接用 scale 调整尺寸。 */
    public static Mesh unitCube() {
        // 每个面 2 个三角形，法线朝外
        float[] v = {
                // -Z
                -1, -1, -1, 0, 0, -1,  1, -1, -1, 0, 0, -1,  1, 1, -1, 0, 0, -1,
                -1, -1, -1, 0, 0, -1,  1, 1, -1, 0, 0, -1,  -1, 1, -1, 0, 0, -1,
                // +Z
                -1, -1, 1, 0, 0, 1,  1, 1, 1, 0, 0, 1,  1, -1, 1, 0, 0, 1,
                -1, -1, 1, 0, 0, 1,  -1, 1, 1, 0, 0, 1,  1, 1, 1, 0, 0, 1,
                // -X
                -1, -1, -1, -1, 0, 0,  -1, 1, -1, -1, 0, 0,  -1, 1, 1, -1, 0, 0,
                -1, -1, -1, -1, 0, 0,  -1, 1, 1, -1, 0, 0,  -1, -1, 1, -1, 0, 0,
                // +X
                1, -1, -1, 1, 0, 0,  1, 1, 1, 1, 0, 0,  1, 1, -1, 1, 0, 0,
                1, -1, -1, 1, 0, 0,  1, -1, 1, 1, 0, 0,  1, 1, 1, 1, 0, 0,
                // -Y
                -1, -1, -1, 0, -1, 0,  1, -1, 1, 0, -1, 0,  1, -1, -1, 0, -1, 0,
                -1, -1, -1, 0, -1, 0,  -1, -1, 1, 0, -1, 0,  1, -1, 1, 0, -1, 0,
                // +Y
                -1, 1, -1, 0, 1, 0,  1, 1, -1, 0, 1, 0,  1, 1, 1, 0, 1, 0,
                -1, 1, -1, 0, 1, 0,  1, 1, 1, 0, 1, 0,  -1, 1, 1, 0, 1, 0,
        };
        return from(v);
    }

    /** 圆柱（用于枪管、立柱）。沿 Y 轴，高 2，半径 1。segments 越大越圆。 */
    public static Mesh cylinder(int segments) {
        float[] tmp = new float[segments * 6 * 2 * 6];
        int p = 0;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * 2 * Math.PI / segments);
            float a1 = (float) ((i + 1) * 2 * Math.PI / segments);
            float x0 = (float) Math.cos(a0); float z0 = (float) Math.sin(a0);
            float x1 = (float) Math.cos(a1); float z1 = (float) Math.sin(a1);
            // 侧面
            p = quad(tmp, p, x0, -1, z0, x0, 1, z0, x1, 1, z1, x1, -1, z1);
            // 顶盖 / 底盖
            p = tri(tmp, p, 0, 1, 0, x0, 1, z0, x1, 1, z1);
            p = tri(tmp, p, 0, -1, 0, x1, -1, z1, x0, -1, z0);
        }
        return from(java.util.Arrays.copyOf(tmp, p));
    }

    private static int tri(float[] out, int p, float ax, float ay, float az,
                           float bx, float by, float bz, float cx, float cy, float cz) {
        Vec3 n = Vec3.of(bx - ax, by - ay, bz - az)
                .cross(Vec3.of(cx - ax, cy - ay, cz - az))
                .normalize();
        out[p++] = ax; out[p++] = ay; out[p++] = az;
        out[p++] = n.x; out[p++] = n.y; out[p++] = n.z;
        out[p++] = bx; out[p++] = by; out[p++] = bz;
        out[p++] = n.x; out[p++] = n.y; out[p++] = n.z;
        out[p++] = cx; out[p++] = cy; out[p++] = cz;
        out[p++] = n.x; out[p++] = n.y; out[p++] = n.z;
        return p;
    }

    /** 四边形按 a,b,c / a,c,d 拆成两个三角形。 */
    private static int quad(float[] out, int p,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz, float dx, float dy, float dz) {
        p = tri(out, p, ax, ay, az, bx, by, bz, cx, cy, cz);
        p = tri(out, p, ax, ay, az, cx, cy, cz, dx, dy, dz);
        return p;
    }

    public void draw(int posAttrib, int normalAttrib) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(posAttrib);
        GLES20.glVertexAttribPointer(posAttrib, 3, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 0);
        GLES20.glEnableVertexAttribArray(normalAttrib);
        GLES20.glVertexAttribPointer(normalAttrib, 3, GLES20.GL_FLOAT, false,
                FLOATS_PER_VERTEX * 4, 3 * 4);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
        GLES20.glDisableVertexAttribArray(posAttrib);
        GLES20.glDisableVertexAttribArray(normalAttrib);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    public void dispose() {
        GLES20.glDeleteBuffers(1, new int[]{vbo}, 0);
    }
}
