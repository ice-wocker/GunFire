package com.icewocker.gunfire.engine;

/** 三维向量。纯值对象，所有运算返回新实例，避免共享可变状态。 */
public final class Vec3 {
    public final float x, y, z;

    public Vec3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static Vec3 of(float x, float y, float z) {
        return new Vec3(x, y, z);
    }

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(float s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public float dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /** 归一化。零向量返回零向量，不产生 NaN。 */
    public Vec3 normalize() {
        float len = length();
        if (len < 1e-6f) {
            return new Vec3(0f, 0f, 0f);
        }
        return scale(1f / len);
    }

    public Vec3 lerp(Vec3 o, float t) {
        return new Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    public float distanceTo(Vec3 o) {
        return sub(o).length();
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "(%.2f, %.2f, %.2f)", x, y, z);
    }
}
