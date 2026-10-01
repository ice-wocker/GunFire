package com.icewocker.gunfire.engine;

/**
 * 4x4 矩阵，列主序，与 OpenGL 一致。
 * 只提供渲染需要的：投影、视图、模型变换、法线矩阵。
 */
public final class Mat4 {
    public final float[] m = new float[16];

    private Mat4() {
    }

    public static Mat4 identity() {
        Mat4 r = new Mat4();
        r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1f;
        return r;
    }

    /** 透视投影。fovY 为纵向视场角（弧度）。 */
    public static Mat4 perspective(float fovY, float aspect, float near, float far) {
        Mat4 r = new Mat4();
        float f = 1f / (float) Math.tan(fovY / 2f);
        r.m[0] = f / aspect;
        r.m[5] = f;
        r.m[10] = (far + near) / (near - far);
        r.m[11] = -1f;
        r.m[14] = 2f * far * near / (near - far);
        return r;
    }

    /** 第一人称视图矩阵：从 eye 看向 eye+forward。 */
    public static Mat4 lookAt(Vec3 eye, Vec3 forward, Vec3 up) {
        Vec3 f = forward.normalize();
        Vec3 s = f.cross(up).normalize();
        Vec3 u = s.cross(f);
        Mat4 r = new Mat4();
        r.m[0] = s.x; r.m[4] = s.y; r.m[8] = s.z;
        r.m[1] = u.x; r.m[5] = u.y; r.m[9] = u.z;
        r.m[2] = -f.x; r.m[6] = -f.y; r.m[10] = -f.z;
        r.m[12] = -s.dot(eye);
        r.m[13] = -u.dot(eye);
        r.m[14] = f.dot(eye);
        r.m[15] = 1f;
        return r;
    }

    public static Mat4 translate(Vec3 t) {
        Mat4 r = identity();
        r.m[12] = t.x;
        r.m[13] = t.y;
        r.m[14] = t.z;
        return r;
    }

    public static Mat4 scale(Vec3 s) {
        Mat4 r = new Mat4();
        r.m[0] = s.x;
        r.m[5] = s.y;
        r.m[10] = s.z;
        r.m[15] = 1f;
        return r;
    }

    /** 绕 Y 轴旋转（偏航）。 */
    public static Mat4 rotateY(float radians) {
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        Mat4 r = identity();
        r.m[0] = c; r.m[2] = -s;
        r.m[8] = s; r.m[10] = c;
        return r;
    }

    /** 绕 X 轴旋转（俯仰）。 */
    public static Mat4 rotateX(float radians) {
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        Mat4 r = identity();
        r.m[5] = c; r.m[6] = s;
        r.m[9] = -s; r.m[10] = c;
        return r;
    }

    public Mat4 mul(Mat4 o) {
        Mat4 r = new Mat4();
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += m[k * 4 + row] * o.m[col * 4 + k];
                }
                r.m[col * 4 + row] = sum;
            }
        }
        return r;
    }
}
