package io.github.vladislavekimtcov.acrylicweather;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * What is drawn live over a sky video, in the same GL pass as the video frame (no extra surface,
 * no window redraw): the moon in tonight's real phase, now and then a shooting star, and
 * drifting clouds. Order: video, moon, shooting star, clouds.
 *
 * Clouds come and go at random, left to right. Off screen they are only moved, never drawn, and
 * the scenes without an overlay cost nothing at all. GL thread only.
 */
final class Overlay {
    private static final String TAG = "AcrylicSky";
    private static final int SPARSE = 1, PARTLY = 2;
    private static final float MOON_X = 0.70f, MOON_Y = 0.08f; // the free band above the hero

    /** GL objects shared by every overlay of one renderer. */
    static final class Gl {
        final int program, aPos, uScreen, uOrigin, uAxisU, uAxisV, uMode, uTex, uLit, uShade, uAlpha, uFlip,
            uPhase, uSouth;
        final int[] clouds = new int[4];
        final FloatBuffer unit;

        private Gl(int program) {
            this.program = program;
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            uScreen = GLES20.glGetUniformLocation(program, "uScreen");
            uOrigin = GLES20.glGetUniformLocation(program, "uOrigin");
            uAxisU = GLES20.glGetUniformLocation(program, "uAxisU");
            uAxisV = GLES20.glGetUniformLocation(program, "uAxisV");
            uMode = GLES20.glGetUniformLocation(program, "uMode");
            uTex = GLES20.glGetUniformLocation(program, "uTex");
            uLit = GLES20.glGetUniformLocation(program, "uLit");
            uShade = GLES20.glGetUniformLocation(program, "uShade");
            uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
            uFlip = GLES20.glGetUniformLocation(program, "uFlip");
            uPhase = GLES20.glGetUniformLocation(program, "uPhase");
            uSouth = GLES20.glGetUniformLocation(program, "uSouth");
            float[] v = {0, 0, 1, 0, 0, 1, 1, 1};
            unit = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            unit.put(v).position(0);
            GLES20.glGenTextures(clouds.length, clouds, 0);
            for (int i = 0; i < clouds.length; i++) {
                Bitmap b = cloudBitmap(101 + i * 37);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, clouds[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                b.recycle();
            }
        }

        /** Null when the shaders don't build; the sky then plays without its overlay. */
        static Gl create() {
            int p = program(VERTEX, FRAGMENT);
            return p == 0 ? null : new Gl(p);
        }

        void release() {
            GLES20.glDeleteTextures(clouds.length, clouds, 0);
            GLES20.glDeleteProgram(program);
        }
    }

    private final int kind;
    private final boolean moon, shooting, testShots;
    private final float[] lit, shade;
    private volatile boolean south;
    private final Random rnd = new Random();

    // Clouds: position, size, speed (px/s), opacity, texture, mirrored.
    private final float[] cx, cy, cw, cv, ca;
    private final int[] ct;
    private final boolean[] cf;
    private int w, h;
    private long lastMs;

    // Shooting star.
    private long nextShot, shotStart = -1;
    private float sx, sy, sdx, sdy, sTravel, sLen, sDur;

    private Overlay(int kind, boolean moon, boolean shooting, float[] lit, float[] shade, boolean south, boolean testShots) {
        this.kind = kind;
        this.moon = moon;
        this.shooting = shooting;
        this.lit = lit;
        this.shade = shade;
        this.south = south;
        this.testShots = testShots;
        int n = kind == PARTLY ? 8 : 3;
        cx = new float[n];
        cy = new float[n];
        cw = new float[n];
        cv = new float[n];
        ca = new float[n];
        ct = new int[n];
        cf = new boolean[n];
    }

    /** The overlay for a scene, or null for the skies whose clouds are in the video. */
    static Overlay forScene(String scene, boolean south, boolean testShots) {
        switch (scene) {
            case "clear-day":
                return new Overlay(SPARSE, false, false, rgb(1f, 1f, 1f), rgb(0.70f, 0.77f, 0.86f), south, testShots);
            case "clear-night":
                return new Overlay(SPARSE, true, true, rgb(0.40f, 0.45f, 0.56f), rgb(0.10f, 0.13f, 0.19f), south, testShots);
            case "dusk":
                return new Overlay(SPARSE, false, false, rgb(1f, 0.78f, 0.62f), rgb(0.40f, 0.29f, 0.44f), south, testShots);
            case "partly-day":
                return new Overlay(PARTLY, false, false, rgb(0.98f, 0.99f, 1f), rgb(0.52f, 0.58f, 0.67f), south, testShots);
            case "partly-night":
                return new Overlay(PARTLY, true, true, rgb(0.34f, 0.39f, 0.50f), rgb(0.07f, 0.09f, 0.14f), south, testShots);
            default:
                return null;
        }
    }

    void setSouth(boolean on) {
        south = on;
    }

    /** Draws over the frame just drawn; blending is set up by the caller. */
    void draw(Gl gl, int width, int height, float alpha, long nowMs) {
        if (width != w || height != h) {
            w = width;
            h = height;
            for (int i = 0; i < cx.length; i++) spawn(i, true);
            nextShot = nowMs + delay();
            lastMs = nowMs;
        }
        float dt = Math.min(0.2f, Math.max(0f, (nowMs - lastMs) / 1000f));
        lastMs = nowMs;

        GLES20.glUseProgram(gl.program);
        GLES20.glUniform2f(gl.uScreen, w, h);
        gl.unit.position(0);
        GLES20.glVertexAttribPointer(gl.aPos, 2, GLES20.GL_FLOAT, false, 0, gl.unit);
        GLES20.glEnableVertexAttribArray(gl.aPos);
        GLES20.glUniform1i(gl.uTex, 2);

        if (moon) {
            float s = h * 0.25f, mx = w * MOON_X, my = h * MOON_Y;
            GLES20.glUniform1f(gl.uMode, 1f);
            GLES20.glUniform1f(gl.uPhase, (float) Moon.phase(System.currentTimeMillis() / 1000));
            GLES20.glUniform1f(gl.uSouth, south ? -1f : 1f);
            GLES20.glUniform1f(gl.uAlpha, alpha);
            quad(gl, mx - s, my - s, 2 * s, 0, 0, 2 * s);
        }

        if (shooting) {
            if (shotStart < 0 && nowMs >= nextShot) startShot(nowMs);
            if (shotStart >= 0) {
                float p = (nowMs - shotStart) / sDur;
                if (p >= 1f) {
                    shotStart = -1;
                    nextShot = nowMs + delay();
                } else {
                    float e = 1f - (1f - p) * (1f - p); // fast, then slowing as it burns out
                    float hx = sx + sdx * sTravel * e, hy = sy + sdy * sTravel * e;
                    float thick = 12f * h / 1080f;
                    // Quad from the tail to the head; v across the streak.
                    float tx = hx - sdx * sLen, ty = hy - sdy * sLen;
                    float px = -sdy * thick, py = sdx * thick;
                    GLES20.glUniform1f(gl.uMode, 2f);
                    GLES20.glUniform1f(gl.uAlpha, alpha * (float) Math.sin(Math.PI * p));
                    quad(gl, tx - px / 2, ty - py / 2, sdx * sLen, sdy * sLen, px, py);
                }
            }
        }

        GLES20.glUniform1f(gl.uMode, 0f);
        GLES20.glUniform3f(gl.uLit, lit[0], lit[1], lit[2]);
        GLES20.glUniform3f(gl.uShade, shade[0], shade[1], shade[2]);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        for (int i = 0; i < cx.length; i++) {
            cx[i] += cv[i] * dt;
            if (cx[i] > w) spawn(i, false);
            float ch = cw[i] * 0.5f;
            // Off screen: moved, not drawn.
            if (cx[i] + cw[i] <= 0 || cx[i] >= w || cy[i] + ch <= 0 || cy[i] >= h) continue;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gl.clouds[ct[i]]);
            GLES20.glUniform1f(gl.uFlip, cf[i] ? 1f : 0f);
            GLES20.glUniform1f(gl.uAlpha, alpha * ca[i]);
            quad(gl, cx[i], cy[i], cw[i], 0, 0, ch);
        }
    }

    private void quad(Gl gl, float ox, float oy, float ux, float uy, float vx, float vy) {
        GLES20.glUniform2f(gl.uOrigin, ox, oy);
        GLES20.glUniform2f(gl.uAxisU, ux, uy);
        GLES20.glUniform2f(gl.uAxisV, vx, vy);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** A new cloud: anywhere on screen at the start, otherwise waiting off the left edge. */
    private void spawn(int i, boolean initial) {
        boolean sparse = kind == SPARSE;
        float scale = w / 1920f;
        cw[i] = w * (sparse ? 0.24f + 0.18f * rnd.nextFloat() : 0.42f + 0.36f * rnd.nextFloat());
        cy[i] = h * (sparse ? 0.02f + 0.30f * rnd.nextFloat() : -0.10f + 0.50f * rnd.nextFloat());
        cv[i] = scale * (sparse ? 5f + 5f * rnd.nextFloat() : 7f + 6f * rnd.nextFloat());
        ca[i] = sparse ? 0.45f + 0.3f * rnd.nextFloat() : 0.8f + 0.2f * rnd.nextFloat();
        ct[i] = rnd.nextInt(4);
        cf[i] = rnd.nextBoolean();
        if (initial) {
            cx[i] = -cw[i] + rnd.nextFloat() * (w + cw[i]);
            // A clear sky starts mostly clear.
            if (sparse && rnd.nextFloat() < 0.5f) cx[i] = -cw[i] - rnd.nextFloat() * w;
        } else {
            // Sparse skies wait a long, random while (minutes at these speeds) before the next one.
            cx[i] = -cw[i] - rnd.nextFloat() * (sparse ? w * 1.2f : w * 0.3f);
        }
    }

    /** Every 3 to 5 minutes (5 to 10 s with the test file present). */
    private long delay() {
        return testShots ? 5000 + rnd.nextInt(5000) : 180_000 + rnd.nextInt(120_001);
    }

    private void startShot(long nowMs) {
        shotStart = nowMs;
        sDur = 650f + rnd.nextFloat() * 450f;
        sx = w * (0.35f + 0.55f * rnd.nextFloat());
        sy = h * (0.02f + 0.14f * rnd.nextFloat());
        double down = Math.toRadians(18 + rnd.nextFloat() * 22);
        boolean left = rnd.nextBoolean();
        sdx = (float) Math.cos(down) * (left ? -1 : 1);
        sdy = (float) Math.sin(down);
        sTravel = w * (0.16f + 0.12f * rnd.nextFloat());
        sLen = w * (0.07f + 0.05f * rnd.nextFloat());
    }

    private static float[] rgb(float r, float g, float b) {
        return new float[] {r, g, b};
    }

    /**
     * A soft cumulus sprite: overlapping radial puffs heaped in the middle, flat-ish base. Alpha
     * is density; the colour channels carry lighting (bright top, shaded base), tinted per scene.
     */
    private static Bitmap cloudBitmap(int seed) {
        int bw = 640, bh = 320;
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Random r = new Random(seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int puffs = 20 + r.nextInt(8);
        for (int i = 0; i < puffs; i++) {
            float x = bw * (0.14f + r.nextFloat() * 0.72f);
            float d = (x - bw * 0.5f) / (bw * 0.38f);
            float bump = (1 - d * d) * bh * 0.30f * (0.55f + r.nextFloat() * 0.45f);
            float y = bh * 0.64f - bump;
            float rad = bh * (0.13f + r.nextFloat() * 0.15f) * (1.15f - 0.4f * Math.abs(d));
            p.setShader(new RadialGradient(x, y, rad, new int[] {0xE6FFFFFF, 0x99FFFFFF, 0x00FFFFFF},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, rad, p);
        }
        // Lighting into the colour channels, keeping the alpha: lit top, shaded base.
        p.setShader(new LinearGradient(0, bh * 0.18f, 0, bh * 0.82f, 0xFFFFFFFF, 0xFF6E6E6E, Shader.TileMode.CLAMP));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        c.drawRect(0, 0, bw, bh, p);
        // Soften the flat base.
        p.setShader(new LinearGradient(0, bh * 0.68f, 0, bh, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawRect(0, bh * 0.68f, bw, bh, p);
        return b;
    }

    // ---- shaders ---------------------------------------------------------------------------

    private static final String VERTEX = ""
            + "attribute vec2 aPos;\n"
            + "uniform vec2 uScreen;\n"
            + "uniform vec2 uOrigin;\n"
            + "uniform vec2 uAxisU;\n"
            + "uniform vec2 uAxisV;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  vec2 p = uOrigin + aPos.x * uAxisU + aPos.y * uAxisV;\n"
            + "  vUv = aPos;\n"
            + "  gl_Position = vec4(p.x / uScreen.x * 2.0 - 1.0, 1.0 - p.y / uScreen.y * 2.0, 0.0, 1.0);\n"
            + "}\n";

    // Output is premultiplied (blend ONE, ONE_MINUS_SRC_ALPHA). Modes: 0 cloud, 1 moon, 2 shooting star.
    private static final String FRAGMENT = ""
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "uniform float uMode;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec3 uLit;\n"
            + "uniform vec3 uShade;\n"
            + "uniform float uAlpha;\n"
            + "uniform float uFlip;\n"
            + "uniform float uPhase;\n"
            + "uniform float uSouth;\n"
            + "varying vec2 vUv;\n"
            + "float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
            + "float noise(vec2 p) {\n"
            + "  vec2 i = floor(p), f = fract(p), u = f * f * (3.0 - 2.0 * f);\n"
            + "  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);\n"
            + "}\n"
            + "void main() {\n"
            + "  if (uMode < 0.5) {\n"
            + "    vec4 t = texture2D(uTex, vec2(uFlip > 0.5 ? 1.0 - vUv.x : vUv.x, vUv.y));\n"
            + "    float lit = t.a > 0.004 ? t.r / t.a : 0.0;\n"
            + "    float a = t.a * uAlpha;\n"
            + "    gl_FragColor = vec4(mix(uShade, uLit, lit) * a, a);\n"
            + "  } else if (uMode < 1.5) {\n"
            // The moon: a lit sphere in the real phase, a cool glow, a faint earthshine veil.
            + "    vec2 p = (vUv - 0.5) * 2.0;\n"
            + "    float d = length(p), R = 0.16;\n"
            + "    float g = 0.28 * exp(-d * 5.0) + 0.22 * exp(-d * 16.0);\n"
            + "    vec3 c = vec3(0.62, 0.70, 0.88) * g;\n"
            + "    float a = g * 0.55;\n"
            + "    if (d < R * 1.04) {\n"
            + "      vec2 n2 = p / R;\n"
            + "      float z = sqrt(max(0.0, 1.0 - dot(n2, n2)));\n"
            + "      vec3 n = vec3(n2.x * uSouth, -n2.y, z);\n"
            + "      float ph = uPhase * 6.2831853;\n"
            + "      float lam = smoothstep(-0.04, 0.12, dot(n, normalize(vec3(sin(ph), 0.0, -cos(ph)))));\n"
            + "      float maria = 0.85 + 0.10 * noise(n2 * 2.2 + 4.0) + 0.04 * noise(n2 * 5.0 + 1.0);\n"
            + "      float edge = smoothstep(R * 1.04, R * 0.96, d);\n"
            + "      float da = edge * (lam + 0.14 * (1.0 - lam));\n"
            + "      vec3 dc = mix(vec3(0.05, 0.06, 0.09), vec3(0.97, 0.95, 0.88) * maria, lam);\n"
            + "      c = dc * da + c * (1.0 - da);\n"
            + "      a = da + a * (1.0 - da);\n"
            + "    }\n"
            + "    gl_FragColor = vec4(c, a) * uAlpha;\n"
            + "  } else {\n"
            // Shooting star: u along the streak (head at 1), v across.
            + "    float u = vUv.x, v = (vUv.y - 0.5) * 2.0;\n"
            + "    float core = (exp(-v * v * 30.0) + 0.35 * exp(-v * v * 6.0)) * u * u;\n"
            + "    float head = 1.4 * exp(-(1.0 - u) * (1.0 - u) * 700.0 - v * v * 14.0);\n"
            + "    float i = clamp(core + head, 0.0, 1.0) * uAlpha;\n"
            + "    gl_FragColor = vec4(vec3(0.92, 0.96, 1.0) * i, i);\n"
            + "  }\n"
            + "}\n";

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.w(TAG, "overlay link: " + GLES20.glGetProgramInfoLog(p));
            return 0;
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.w(TAG, "overlay compile: " + GLES20.glGetShaderInfoLog(s));
            return 0;
        }
        return s;
    }
}
