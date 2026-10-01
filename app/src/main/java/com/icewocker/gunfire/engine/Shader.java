package com.icewocker.gunfire.engine;

import android.opengl.GLES20;
import android.util.Log;

/** 极简 GLSL 程序封装：编译、链接、uniform 定位。 */
public final class Shader {
    private static final String TAG = "Shader";
    private final int program;

    private Shader(int program) {
        this.program = program;
    }

    public static Shader create(String vertexSrc, String fragmentSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(prog));
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return new Shader(prog);
    }

    private static int compile(int type, String src) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, src);
        GLES20.glCompileShader(id);
        int[] status = new int[1];
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(id));
        }
        return id;
    }

    public void use() {
        GLES20.glUseProgram(program);
    }

    public int uniform(String name) {
        return GLES20.glGetUniformLocation(program, name);
    }

    public int attrib(String name) {
        return GLES20.glGetAttribLocation(program, name);
    }

    public void dispose() {
        GLES20.glDeleteProgram(program);
    }
}
