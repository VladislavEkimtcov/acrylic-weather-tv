package io.github.vladislavekimtcov.acrylicweather;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The sky: pre-rendered loops (assets/sky/&lt;scene&gt;.mp4, or sky/&lt;scene&gt;.mp4 in the app's media
 * folder to override one) decoded by the TV's hardware video decoder.
 *
 * Why not straight onto a SurfaceView: this TV's display service gives such a surface its own
 * video window, then never finishes configuring it ("DispLink waitConfigInfo ... timeout") and
 * keeps it muted: black, with MediaCodec and MediaPlayer alike. So each decoder writes into a
 * SurfaceTexture and one GL quad per video frame draws it on this view's surface, which the
 * compositor shows as an ordinary graphics plane. That blit also
 * <ul>
 * <li>crossfades two skies (two decoders for the length of the fade), and</li>
 * <li>adds a 1-LSB triangular dither, so the smooth gradients don't band at 8 bits per channel.</li>
 * </ul>
 * The window itself never redraws for the sky. Looping is gapless: at the end of the file the
 * extractor seeks back and the timestamps carry on, without flushing the decoder.
 */
final class Backdrop extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "AcrylicSky";
    private static final long FADE_MS = 900;
    /** A daytime sky shown while it's dark where the TV is plays at this brightness. */
    static final float NIGHT_DIM = 0.7f;

    interface Listener {
        /** A scene's first frame is on screen. */
        void onShown(String scene);

        /** The scene could not be played; whatever was showing stays. */
        void onFailed(String scene, String why);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File mediaDir;
    private Listener listener;
    private Renderer renderer;
    private String scene;
    /** The place is south of the equator: the moon's lit side is mirrored there. */
    private volatile boolean south;
    /** It's dark where the TV probably is: daytime skies are dimmed so they don't glare. */
    private volatile boolean dark;
    private boolean want, hasSurface;

    Backdrop(Context c, File mediaDir) {
        super(c);
        this.mediaDir = mediaDir;
        getHolder().addCallback(this);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Crossfades to a scene; a no-op when it is already the one playing or coming. */
    void play(String next, boolean southern) {
        south = southern;
        scene = next;
        if (renderer != null) renderer.request(next);
    }

    void setDark(boolean on) {
        dark = on;
    }

    /** How bright a scene plays now: daytime skies are dimmed while it's dark at the TV. */
    static float dimFor(String scene, boolean darkHere) {
        return darkHere && scene != null && scene.endsWith("-day") ? NIGHT_DIM : 1f;
    }

    void setRunning(boolean on) {
        want = on;
        update();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        hasSurface = true;
        update();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (renderer != null) renderer.resize(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
        update(); // the surface must be free when this returns
    }

    private void update() {
        if (want && hasSurface) {
            if (renderer == null) {
                renderer = new Renderer(getHolder().getSurface(), getWidth(), getHeight());
                renderer.start();
                if (scene != null) renderer.request(scene);
            }
        } else if (renderer != null) {
            renderer.quit();
            renderer = null;
        }
    }

    private void open(MediaExtractor ex, String name) throws IOException {
        File f = mediaDir == null ? null : new File(mediaDir, "sky/" + name + ".mp4");
        if (f != null && f.isFile()) {
            ex.setDataSource(f.getPath());
            return;
        }
        // aapt stores .mp4 uncompressed, so the extractor reads it in place inside the APK.
        try (AssetFileDescriptor afd = getContext().getAssets().openFd("sky/" + name + ".mp4")) {
            ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        }
    }

    private void shown(String name) {
        ui.post(() -> {
            if (listener != null && name.equals(scene)) listener.onShown(name);
        });
    }

    private void failed(String name, String why) {
        Log.w(TAG, name + ": " + why);
        ui.post(() -> {
            if (listener != null && name.equals(scene)) listener.onFailed(name, why);
        });
    }

    // ---- GL thread --------------------------------------------------------------------------

    private static final String VERTEX = ""
            + "attribute vec2 aPos;\n"
            + "uniform mat4 uMatA;\n"
            + "uniform mat4 uMatB;\n"
            + "varying vec2 vA;\n"
            + "varying vec2 vB;\n"
            + "void main() {\n"
            + "  vec4 uv = vec4(aPos * 0.5 + 0.5, 0.0, 1.0);\n"
            + "  vA = (uMatA * uv).xy;\n"
            + "  vB = (uMatB * uv).xy;\n"
            + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "}\n";

    private static final String FRAGMENT = ""
            + "#extension GL_OES_EGL_image_external : require\n"
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "uniform samplerExternalOES uA;\n"
            + "uniform samplerExternalOES uB;\n"
            + "uniform float uMix;\n"
            + "uniform float uSeed;\n"
            + "uniform float uDimA;\n"
            + "uniform float uDimB;\n"
            + "varying vec2 vA;\n"
            + "varying vec2 vB;\n"
            // Interleaved gradient noise (Jimenez 2014): cheap, no sin(), stable in precision.
            + "float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }\n"
            // Night dimming darkens the sky but keeps the sun's disc white, so it doesn't turn gray.
            + "vec3 dimmed(vec3 c, float k) {\n"
            + "  return k >= 1.0 ? c : c * mix(k, 1.0, smoothstep(0.82, 1.0, max(c.r, max(c.g, c.b))));\n"
            + "}\n"
            + "void main() {\n"
            + "  vec3 c = dimmed(texture2D(uA, vA).rgb, uDimA);\n"
            + "  if (uMix > 0.0) c = mix(c, dimmed(texture2D(uB, vB).rgb, uDimB), uMix);\n"
            // Triangular dither of +-1 LSB: hides 8-bit banding in the smooth sky.
            + "  vec2 p = gl_FragCoord.xy + vec2(uSeed, uSeed * 1.618);\n"
            + "  float n = ign(p) + ign(p + vec2(47.0, 13.0)) - 1.0;\n"
            + "  gl_FragColor = vec4(c + n / 255.0, 1.0);\n"
            + "}\n";

    /**
     * One decoder feeding one texture. Callback mode on its own looper: no polling. Output
     * buffers are held and released at their due time (postAtTime), which paces the loop.
     */
    private final class Channel extends MediaCodec.Callback implements SurfaceTexture.OnFrameAvailableListener {
        final String name;
        /** Live moon, shooting stars and clouds over this sky, or null. */
        Overlay overlay;
        final int tex;
        final SurfaceTexture st;
        final Surface surface;
        final float[] matrix = new float[16];
        private final HandlerThread thread;
        private final Handler handler;
        private MediaExtractor ex;
        private MediaCodec codec;
        private long frameUs = 33_333, offsetUs, maxPtsUs, firstPtsUs = -1, baseMs;
        private boolean any;
        volatile boolean halt;
        volatile boolean fresh;
        boolean hasFrame;
        /** Brightness, eased toward {@link #dimFor} on the GL thread. */
        float dim = 1f;

        Channel(String name, int tex, Handler frames) {
            this.name = name;
            this.tex = tex;
            st = new SurfaceTexture(tex);
            st.setOnFrameAvailableListener(this, frames);
            surface = new Surface(st);
            android.opengl.Matrix.setIdentityM(matrix, 0);
            thread = new HandlerThread("sky-" + name, android.os.Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        void start() {
            handler.post(this::open);
            handler.postDelayed(() -> {
                if (!any && !halt) failed(name, "the decoder produced no frame");
            }, 4000);
        }

        private void open() {
            try {
                ex = new MediaExtractor();
                Backdrop.this.open(ex, name);
                int track = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        track = i;
                        break;
                    }
                }
                if (track < 0) throw new IOException("no video track");
                ex.selectTrack(track);
                MediaFormat format = ex.getTrackFormat(track);
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    frameUs = 1_000_000L / Math.max(1, format.getInteger(MediaFormat.KEY_FRAME_RATE));
                }
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                codec.setCallback(this, handler);
                codec.configure(format, surface, null, 0);
                codec.start();
            } catch (IOException | RuntimeException e) {
                if (!halt) failed(name, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        @Override
        public void onInputBufferAvailable(MediaCodec mc, int index) {
            if (halt) return;
            try {
                ByteBuffer buf = mc.getInputBuffer(index);
                int size = buf == null ? -1 : ex.readSampleData(buf, 0);
                if (size < 0) {
                    // End of the file: round again, timestamps carrying on (no flush, no gap).
                    ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    offsetUs = maxPtsUs + frameUs;
                    size = buf == null ? -1 : ex.readSampleData(buf, 0);
                }
                if (size > 0) {
                    long pts = ex.getSampleTime() + offsetUs;
                    maxPtsUs = Math.max(maxPtsUs, pts);
                    mc.queueInputBuffer(index, 0, size, pts, 0);
                    ex.advance();
                } else {
                    mc.queueInputBuffer(index, 0, 0, maxPtsUs, 0);
                }
            } catch (RuntimeException e) {
                if (!halt) failed(name, "input: " + e);
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec mc, int index, MediaCodec.BufferInfo info) {
            if (halt) return;
            if (firstPtsUs < 0) {
                firstPtsUs = info.presentationTimeUs;
                baseMs = SystemClock.uptimeMillis();
            }
            long due = baseMs + (info.presentationTimeUs - firstPtsUs) / 1000;
            long now = SystemClock.uptimeMillis();
            if (now - due > 2 * frameUs / 1000) {
                release(mc, index, false); // more than two frames late: skip, keep the clock
            } else if (due - now > 1) {
                handler.postAtTime(() -> release(mc, index, true), due);
            } else {
                release(mc, index, true);
            }
        }

        private void release(MediaCodec mc, int index, boolean render) {
            if (halt) return;
            try {
                mc.releaseOutputBuffer(index, render);
                any = true;
            } catch (RuntimeException e) {
                // codec stopping
            }
        }

        @Override
        public void onError(MediaCodec mc, MediaCodec.CodecException e) {
            if (!halt) failed(name, "decoder: " + e.getDiagnosticInfo());
        }

        @Override
        public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
        }

        @Override
        public void onFrameAvailable(SurfaceTexture t) {
            fresh = true;
            Renderer r = renderer;
            if (r != null) r.wake();
        }

        void stop() {
            halt = true;
            final Object done = new Object();
            final boolean[] finished = {false};
            handler.removeCallbacksAndMessages(null);
            handler.post(() -> {
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (RuntimeException e) {
                        // already broken
                    }
                    codec.release();
                }
                if (ex != null) ex.release();
                synchronized (done) {
                    finished[0] = true;
                    done.notifyAll();
                }
            });
            synchronized (done) {
                long until = SystemClock.uptimeMillis() + 1500;
                while (!finished[0] && SystemClock.uptimeMillis() < until) {
                    try {
                        done.wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            thread.quitSafely();
        }

        /** GL thread, after stop(). */
        void release() {
            surface.release();
            st.release();
            GLES20.glDeleteTextures(1, new int[] {tex}, 0);
        }
    }

    private final class Renderer extends Thread {
        private final Surface target;
        private final Object lock = new Object();
        private volatile boolean halt;
        private boolean woken;
        private String pending;
        private int width, height;
        private HandlerThread framesThread;
        private Handler frames;

        private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        private EGLSurface win = EGL14.EGL_NO_SURFACE;
        private int program, aPos, uA, uB, uMix, uSeed, uDimA, uDimB, uMatA, uMatB;
        private long lastStepMs;
        private Overlay.Gl overlayGl;
        private FloatBuffer quad;

        /** Showing, and fading in over it (or null). */
        private Channel cur, next;
        private long fadeStart;
        private boolean curShown;
        private int frame;

        Renderer(Surface target, int w, int h) {
            super("sky-gl");
            this.target = target;
            width = w;
            height = h;
        }

        void request(String name) {
            synchronized (lock) {
                pending = name;
                woken = true;
                lock.notifyAll();
            }
        }

        void resize(int w, int h) {
            synchronized (lock) {
                width = w;
                height = h;
                woken = true;
                lock.notifyAll();
            }
        }

        void wake() {
            synchronized (lock) {
                woken = true;
                lock.notifyAll();
            }
        }

        void quit() {
            halt = true;
            wake();
            try {
                join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            framesThread = new HandlerThread("sky-frames");
            framesThread.start();
            frames = new Handler(framesThread.getLooper());
            try {
                if (!setup()) return;
                while (!halt) {
                    String want;
                    int w, h;
                    synchronized (lock) {
                        boolean fading = next != null && next.hasFrame;
                        while (!halt && !woken && !fading) {
                            try {
                                lock.wait(250);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        woken = false;
                        want = pending;
                        pending = null;
                        w = width;
                        h = height;
                    }
                    if (halt) break;
                    if (want != null) switchTo(want);
                    step(w, h);
                }
            } finally {
                if (next != null) {
                    next.stop();
                    next.release();
                }
                if (cur != null) {
                    cur.stop();
                    cur.release();
                }
                teardown();
                framesThread.quitSafely();
            }
        }

        private void switchTo(String name) {
            Channel showingOrComing = next != null ? next : cur;
            if (showingOrComing != null && showingOrComing.name.equals(name)) {
                if (showingOrComing.overlay != null) showingOrComing.overlay.setSouth(south);
                return;
            }
            if (next != null) { // a fade not finished: drop the one coming in
                next.stop();
                next.release();
                next = null;
            }
            Channel c = new Channel(name, texture(), frames);
            // Test hook: with sky/shooting-star-test in the media folder, a shooting star every 5-10 s.
            boolean testShots = mediaDir != null && new File(mediaDir, "sky/shooting-star-test").exists();
            c.overlay = Overlay.forScene(name, south, testShots);
            c.dim = dimFor(name, dark);
            c.start();
            if (cur == null) {
                cur = c;
                curShown = false;
            } else {
                next = c;
                fadeStart = 0;
            }
        }

        private void step(int w, int h) {
            boolean draw = false;
            if (cur != null && cur.fresh) {
                cur.fresh = false;
                cur.st.updateTexImage();
                cur.st.getTransformMatrix(cur.matrix);
                cur.hasFrame = true;
                draw = true;
            }
            if (next != null && next.fresh) {
                next.fresh = false;
                next.st.updateTexImage();
                next.st.getTransformMatrix(next.matrix);
                if (!next.hasFrame) {
                    next.hasFrame = true;
                    fadeStart = SystemClock.uptimeMillis();
                    shown(next.name);
                }
                draw = true;
            }
            float mix = 0;
            if (next != null && next.hasFrame) {
                mix = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS);
                mix = mix * mix * (3 - 2 * mix);
                draw = true;
            }
            if (!draw || cur == null || !cur.hasFrame) return;

            // Dimming follows dusk at the TV over a couple of seconds, not in one jump.
            long t = SystemClock.uptimeMillis();
            float k = lastStepMs == 0 ? 1f : Math.min(1f, (t - lastStepMs) / 1500f);
            lastStepMs = t;
            boolean night = dark;
            cur.dim += (dimFor(cur.name, night) - cur.dim) * k;
            if (next != null) next.dim += (dimFor(next.name, night) - next.dim) * k;

            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cur.tex);
            GLES20.glUniform1i(uA, 0);
            GLES20.glUniformMatrix4fv(uMatA, 1, false, cur.matrix, 0);
            Channel b = next != null && next.hasFrame ? next : cur;
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, b.tex);
            GLES20.glUniform1i(uB, 1);
            GLES20.glUniformMatrix4fv(uMatB, 1, false, b.matrix, 0);
            GLES20.glUniform1f(uMix, b == cur ? 0f : mix);
            GLES20.glUniform1f(uDimA, cur.dim);
            GLES20.glUniform1f(uDimB, b.dim);
            GLES20.glUniform1f(uSeed, (frame++ % 64) * 7f);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // Live layer over the video, faded with it: moon, shooting star, clouds.
            float outMix = b == cur ? 0f : mix;
            boolean over = overlayGl != null && (cur.overlay != null || (b != cur && b.overlay != null));
            if (over) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                long now = SystemClock.uptimeMillis();
                if (cur.overlay != null) cur.overlay.draw(overlayGl, w, h, 1f - outMix, cur.dim, now);
                if (b != cur && b.overlay != null) b.overlay.draw(overlayGl, w, h, outMix, b.dim, now);
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            EGL14.eglSwapBuffers(dpy, win);

            if (!curShown) {
                curShown = true;
                shown(cur.name);
            }
            if (next != null && next.hasFrame && mix >= 1f) {
                // Faded over: the old decoder goes, freeing the chip.
                Channel old = cur;
                cur = next;
                next = null;
                old.stop();
                old.release();
            }
        }

        private int texture() {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return t[0];
        }

        private boolean setup() {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(dpy, version, 0, version, 1)) return fail("eglInitialize");
            int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) return fail("eglChooseConfig");
            ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext");
            win = EGL14.eglCreateWindowSurface(dpy, configs[0], target, new int[] {EGL14.EGL_NONE}, 0);
            if (win == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface");
            if (!EGL14.eglMakeCurrent(dpy, win, win, ctx)) return fail("eglMakeCurrent");
            program = program();
            if (program == 0) return fail("shader");
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            uA = GLES20.glGetUniformLocation(program, "uA");
            uB = GLES20.glGetUniformLocation(program, "uB");
            uMix = GLES20.glGetUniformLocation(program, "uMix");
            uSeed = GLES20.glGetUniformLocation(program, "uSeed");
            uDimA = GLES20.glGetUniformLocation(program, "uDimA");
            uDimB = GLES20.glGetUniformLocation(program, "uDimB");
            uMatA = GLES20.glGetUniformLocation(program, "uMatA");
            uMatB = GLES20.glGetUniformLocation(program, "uMatB");
            float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
            quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(v).position(0);
            overlayGl = Overlay.Gl.create(); // null: skies play without the live layer
            return true;
        }

        private boolean fail(String what) {
            failed(pendingOrCurrent(), what + " failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }

        private String pendingOrCurrent() {
            synchronized (lock) {
                return pending != null ? pending : scene == null ? "" : scene;
            }
        }

        private int program() {
            int vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX), fs = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            if (vs == 0 || fs == 0) return 0;
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.w(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
                return 0;
            }
            return p;
        }

        private int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.w(TAG, "compile: " + GLES20.glGetShaderInfoLog(s));
                return 0;
            }
            return s;
        }

        private void teardown() {
            if (overlayGl != null) {
                overlayGl.release();
                overlayGl = null;
            }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win);
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglReleaseThread();
            }
        }
    }
}
