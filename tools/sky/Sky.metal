// Acrylic Weather's skies, rendered offline on a Mac GPU and shipped as looping video.
// One compute thread per pixel. Everything is a function of (pixel, t) with t in [0, T):
//   - rain, snow, stars, sun rays, cloud morphing and lightning are exactly periodic in T
//     (whole numbers of cycles per loop), so they need no help at the seam;
//   - cloud and fog drift is not periodic, so the last F seconds fade into the first
//     (the soft layer at t + T blended into the one at t), making the loop seamless.
// Parameters arrive as float4s; the index map is in SkyRender.swift (P[...]).

#include <metal_stdlib>
using namespace metal;

constant float TAU = 6.28318530718;

static float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

static float vnoise(float2 p) {
    float2 i = floor(p), f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i), b = hash12(i + float2(1, 0)), c = hash12(i + float2(0, 1)), d = hash12(i + float2(1, 1));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

static float fbm(float2 p, int octaves) {
    float v = 0.0, a = 0.5;
    const float2x2 rot = float2x2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < octaves; i++) {
        v += a * vnoise(p);
        p = rot * p * 2.03 + 17.13;
        a *= 0.5;
    }
    return v;
}

struct Sky {
    float W, H, t, T, F, seed, night, wind;
    float3 top, mid, bottom; float midPos;
    float3 glowColor; float glow; float2 glowPos;
    float sunAmt, moonAmt; float2 sun, moon;
    float3 sunColor; float stars;
    float3 lit; float cover;
    float3 shade; float dark;
    float drift, rain, snow, fog;
    float3 fogColor; float storm;
    float vignette, rays, moonPhase;
    float clouds, low; // clouds 0: none baked in (the app draws them live); low 1: heavy banks near the ground
};

static Sky unpack(constant float4 *P) {
    Sky s;
    s.W = P[0].x; s.H = P[0].y; s.t = P[0].z; s.T = P[0].w;
    s.F = P[1].x; s.seed = P[1].y; s.night = P[1].z; s.wind = P[1].w;
    s.top = P[2].xyz; s.mid = P[3].xyz; s.midPos = P[3].w; s.bottom = P[4].xyz;
    s.glowColor = P[5].xyz; s.glow = P[5].w; s.glowPos = P[6].xy; s.sunAmt = P[6].z; s.moonAmt = P[6].w;
    s.sun = P[7].xy; s.moon = P[7].zw;
    s.sunColor = P[8].xyz; s.stars = P[8].w;
    s.lit = P[9].xyz; s.cover = P[9].w;
    s.shade = P[10].xyz; s.dark = P[10].w;
    s.drift = P[11].x; s.rain = P[11].y; s.snow = P[11].z; s.fog = P[11].w;
    s.fogColor = P[12].xyz; s.storm = P[12].w;
    s.vignette = P[13].x; s.rays = P[13].y; s.moonPhase = P[13].z;
    s.clouds = P[14].x; s.low = P[14].y;
    return s;
}

// ---- lightning (periodic: flash times inside the loop) ------------------------------------

#define FLASHES 6
constant float FLASH_T[FLASHES] = {2.30, 2.46, 9.10, 14.60, 14.74, 17.85};
constant float FLASH_A[FLASHES] = {1.00, 0.70, 0.80, 1.00, 0.65, 0.55};
constant int FLASH_BOLT[FLASHES] = {1, 0, 1, 1, 0, 0};

static float flashAt(float t, float T, thread int &boltIndex, thread float &boltA) {
    float f = 0.0;
    boltIndex = -1; boltA = 0.0;
    for (int i = 0; i < FLASHES; i++) {
        float ti = FLASH_T[i] * T / 20.0;
        float dt = t - ti;
        if (dt < 0.0) dt += T; // the tail of a flash near the end wraps (keeps it periodic)
        float e = FLASH_A[i] * exp(-dt * 11.0) * step(0.0, dt) * step(dt, 1.2);
        f += e;
        if (FLASH_BOLT[i] == 1 && dt < 0.22) { boltIndex = i; boltA = max(boltA, exp(-dt * 16.0)); }
    }
    return f;
}

// ---- the soft layer: gradient, glow, sun, moon, stars, clouds, fog --------------------------

// puff 0: long wisps (high cirrus); 1: rounded heaps (cumulus).
static float cloudField(float2 uv, float driftUV, float t, float T, float scale, float seed, int oct, float puff) {
    float a = TAU * t / T;
    float2 morph = float2(cos(a), sin(a)) * 0.35; // a circle in noise space: periodic morphing
    float2 p = float2((uv.x - driftUV) * scale, uv.y * scale * mix(2.3, 1.25, puff)) + seed;
    float2 q = float2(fbm(p * 0.55 + morph + 3.1, 4), fbm(p * 0.55 - morph.yx + 7.7, 4));
    float d = fbm(p + q * mix(1.6, 0.55, puff), oct);
    // Heaps: a broad base shape lifts the billows so they round off instead of streaking.
    return mix(d, d * 0.75 + 0.3 * fbm(p * 0.45 + 5.0 + morph * 0.5, 3), puff);
}

static float4 cloudLayer(float2 uv, float tt, thread const Sky &s, float scale, float speed, float y0, float y1,
                         float alpha, float seed, float flash, float puff) {
    float driftUV = s.drift * speed * tt / s.H;
    float d = cloudField(uv, driftUV, tt, s.T, scale, seed + s.seed, 6, puff);
    float band = smoothstep(y0 - 0.18, y0 + 0.08, uv.y) * (1.0 - smoothstep(y1 - 0.12, y1 + 0.22, uv.y));
    float th = mix(0.74, 0.30, s.cover);
    float c = smoothstep(th, th + mix(0.24, 0.15, puff), d) * band;
    if (c <= 0.001) return float4(0);
    // Light from above (and toward the sun): thinner density up there means a lit edge.
    float2 toward = normalize(float2((s.sun.x * s.W / s.H) - uv.x, -0.9));
    float dl = cloudField(uv + toward * 0.035, driftUV, tt, s.T, scale, seed + s.seed, 4, puff);
    float below = cloudField(uv + float2(0.0, 0.03), driftUV, tt, s.T, scale, seed + s.seed, 3, puff);
    float lit = clamp(0.62 + (d - dl) * 3.2, 0.0, 1.0);
    lit *= 1.0 - 0.35 * puff * smoothstep(-0.05, 0.15, below - d); // heaps: flat, darker bases
    lit *= 1.0 - s.dark * 0.6 * smoothstep(th + 0.05, th + 0.35, d); // thick parts: dark bellies
    float3 col = mix(s.shade, s.lit, lit);
    col += flash * float3(0.55, 0.6, 0.75) * (0.4 + 0.6 * lit);
    // Silver lining near the sun / moon.
    float sunD = distance(uv, float2(s.sun.x * s.W / s.H, s.sun.y));
    col += s.sunColor * s.sunAmt * 0.35 * exp(-sunD * 6.0) * (1.0 - smoothstep(th, th + 0.3, d));
    float moonD = distance(uv, float2(s.moon.x * s.W / s.H, s.moon.y));
    col += float3(0.55, 0.62, 0.8) * s.moonAmt * 0.25 * exp(-moonD * 7.0) * (1.0 - smoothstep(th, th + 0.3, d));
    return float4(col, c * alpha);
}

static float3 soft(float2 px, float tt, thread const Sky &s, float flash) {
    float2 uv = px / s.H;          // x in 0..W/H, y in 0..1
    float y = px.y / s.H;
    float3 col = y < s.midPos ? mix(s.top, s.mid, smoothstep(0.0, s.midPos, y))
                              : mix(s.mid, s.bottom, smoothstep(s.midPos, 1.0, y));
    // Horizon glow (dusk).
    float2 g = (uv - float2(s.glowPos.x * s.W / s.H, s.glowPos.y)) * float2(0.55, 1.6);
    col += s.glowColor * s.glow * exp(-dot(g, g) * 3.0) * 0.85;

    // Stars, hidden later by cloud.
    if (s.stars > 0.0) {
        float2 cell = floor(px / 22.0);
        float h = hash12(cell + 3.7);
        if (h < 0.42) {
            float2 c = (cell + 0.2 + 0.6 * float2(hash12(cell + 1.1), hash12(cell + 9.4))) * 22.0;
            float r = 0.55 + 1.1 * pow(hash12(cell + 5.5), 3.0);
            float k = floor(1.0 + 5.0 * hash12(cell + 2.2)); // whole twinkles per loop
            float tw = 0.65 + 0.35 * sin(TAU * (k * tt / s.T + hash12(cell + 7.7)));
            float b = exp(-dot(px - c, px - c) / (r * r)) * tw * (0.35 + 0.65 * hash12(cell + 4.4));
            b *= 1.0 - smoothstep(0.45, 0.8, y);
            col += float3(0.92, 0.95, 1.0) * b * s.stars;
        }
    }

    // Sun: glow, disc, and slow rays that turn exactly one 30-degree symmetry step per loop.
    if (s.sunAmt > 0.0) {
        float2 sp = float2(s.sun.x * s.W, s.sun.y * s.H);
        float2 dv = (px - sp) / s.H;
        float d = length(dv);
        col += s.sunColor * s.sunAmt * (0.55 * exp(-d * 4.5) + 0.35 * exp(-d * 14.0));
        float disc = smoothstep(0.047, 0.042, d);
        float3 face = mix(float3(1.0, 0.985, 0.94), min(float3(1.0), s.sunColor * 1.25), 0.45);
        col = mix(col, max(col, face), disc * s.sunAmt); // never darker than its own glow
        if (s.rays > 0.0) {
            float ang = atan2(dv.y, dv.x) - TAU * tt / (12.0 * s.T);
            float r = pow(max(0.0, cos(ang * 12.0)), 6.0) * 0.6 + pow(max(0.0, cos(ang * 12.0 + 1.3)), 10.0) * 0.4;
            col += s.sunColor * s.sunAmt * s.rays * r * 0.065 * exp(-d * 1.8) * smoothstep(0.04, 0.12, d);
        }
    }

    // Moon: a lit sphere (waxing gibbous) with soft maria, and a cool glow.
    if (s.moonAmt > 0.0) {
        float R = 0.036;
        float2 mp = float2(s.moon.x * s.W, s.moon.y * s.H);
        float2 dv = (px - mp) / s.H;
        float d = length(dv);
        col += float3(0.6, 0.68, 0.85) * s.moonAmt * (0.25 * exp(-d * 6.0) + 0.2 * exp(-d * 18.0));
        if (d < R * 1.02) {
            float2 n2 = dv / R;
            float z = sqrt(max(0.0, 1.0 - dot(n2, n2)));
            float3 n = float3(n2.x, -n2.y, z);
            float ph = s.moonPhase * TAU;
            float3 L = normalize(float3(sin(ph), 0.0, -cos(ph)));
            float lambert = smoothstep(-0.05, 0.12, dot(n, L));
            float maria = 0.82 + 0.18 * fbm(n2 * 3.0 + 4.0, 4);
            float3 mc = float3(0.97, 0.95, 0.88) * maria;
            float edge = smoothstep(R * 1.02, R * 0.96, d);
            col = mix(col, col * 0.85 + float3(0.05, 0.06, 0.08), edge * s.moonAmt); // earthshine
            col = mix(col, mc, edge * lambert * s.moonAmt);
        }
    }

    // Three cloud layers, far to near, each drifting a little faster (parallax). With `low`,
    // they become heavy banks sitting on the ground, piled toward the bottom of the screen.
    if (s.clouds > 0.0) {
        float lo = s.low;
        float4 far = cloudLayer(uv, tt, s, mix(2.4, 1.6, lo), 0.45, mix(0.02, 0.42, lo), mix(0.46, 0.9, lo), 0.6, 11.0, flash, lo);
        col = mix(col, far.rgb, far.a);
        float4 mid = cloudLayer(uv, tt, s, mix(1.7, 1.1, lo), 1.0, mix(-0.02, 0.52, lo), mix(0.62, 1.05, lo), mix(0.85, 0.95, lo), 37.0, flash, 0.85 + 0.15 * lo);
        col = mix(col, mid.rgb, mid.a);
        float4 near = cloudLayer(uv, tt, s, mix(1.15, 0.8, lo), 1.8, mix(-0.12, 0.62, lo), mix(0.52, 1.2, lo), 0.95, 71.0, flash, 1.0);
        col = mix(col, near.rgb, near.a);
    }

    // Fog banks: long, slow, thicker toward the ground.
    if (s.fog > 0.0) {
        float acc = 0.0;
        for (int i = 0; i < 3; i++) {
            float fi = float(i);
            float2 p = float2(uv.x * (1.2 + fi * 0.5) - s.drift * (0.25 + 0.2 * fi) * tt / s.H * (i == 1 ? -1.0 : 1.0),
                              uv.y * (7.0 + fi * 3.0)) + fi * 19.0 + s.seed;
            float n = fbm(p + float2(0.0, 0.3 * sin(TAU * tt / s.T + fi)), 5);
            float band = smoothstep(0.25 + fi * 0.12, 0.75 + fi * 0.08, y);
            acc += smoothstep(0.38, 0.72, n) * band * (0.55 - fi * 0.1);
        }
        col = mix(col, s.fogColor, clamp(acc * s.fog, 0.0, 0.85));
    }
    return col;
}

// ---- rain and snow (exactly periodic) -------------------------------------------------------

static float rainLayer(float2 px, thread const Sky &s, float colW, float cycles, float len, float thick,
                       float density, float seed) {
    float2 dir = normalize(float2(s.wind * 0.45, 1.0));
    float2 perp = float2(dir.y, -dir.x);
    float u = dot(px, perp), v = dot(px, dir);
    float vmin = min(0.0, s.W * dir.x), vmax = max(0.0, s.W * dir.x) + s.H * dir.y;
    float wrap = vmax - vmin + 2.0 * len;
    float col = floor(u / colW);
    float acc = 0.0;
    for (int dc = -1; dc <= 1; dc++) {
        float c = col + float(dc);
        for (int k = 0; k < 2; k++) {
            float2 key = float2(c, float(k) + seed);
            if (hash12(key + 0.37) > density * s.rain) continue;
            float h = hash12(key), h2 = hash12(key + 17.3);
            float uc = (c + 0.15 + 0.7 * h2) * colW;
            float cyc = floor(cycles * (0.85 + 0.3 * h)); // whole laps per loop: periodic
            float head = vmin - len + fract(h * 7.31 + cyc * s.t / s.T) * wrap;
            float dv = v - clamp(v, head - len, head);
            float d = length(float2(u - uc, dv));
            float tail = clamp((v - (head - len)) / len, 0.0, 1.0); // brighter at the head
            acc += smoothstep(thick, 0.0, d) * tail;
        }
    }
    return acc;
}

static float snowLayer(float2 px, thread const Sky &s, float colW, float cycles, float r, float sway,
                       float density, float seed) {
    float col = floor(px.x / colW);
    float acc = 0.0;
    for (int dc = -1; dc <= 1; dc++) {
        float c = col + float(dc);
        for (int k = 0; k < 2; k++) {
            float2 key = float2(c * 1.3, float(k) + seed);
            if (hash12(key + 0.71) > density * s.snow) continue;
            float h = hash12(key), h2 = hash12(key + 11.1), h3 = hash12(key + 5.3);
            float cyc = floor(cycles * (0.75 + 0.5 * h2));
            float y = fract(h3 + cyc * s.t / s.T) * (s.H + 4.0 * r) - 2.0 * r;
            float m = floor(1.0 + 3.0 * h);
            float x = (c + 0.5) * colW + sway * sin(TAU * (m * s.t / s.T + h2)) + s.wind * 40.0 * sin(TAU * s.t / s.T + h);
            float d = length(px - float2(x, y));
            acc += smoothstep(r, r * 0.2, d);
        }
    }
    return acc;
}

kernel void sky(device ushort *out [[buffer(0)]],
                constant float4 *P [[buffer(1)]],
                uint2 gid [[thread_position_in_grid]]) {
    Sky s = unpack(P);
    if (gid.x >= uint(s.W) || gid.y >= uint(s.H)) return;
    float2 px = float2(gid) + 0.5;

    int boltIndex; float boltA;
    float flash = s.storm > 0.0 ? flashAt(s.t, s.T, boltIndex, boltA) * s.storm : 0.0;
    if (s.storm <= 0.0) { boltIndex = -1; boltA = 0.0; }

    float3 col = soft(px, s.t, s, flash);
    if (s.t < s.F) {
        // The seam: the soft layer one loop later fades into this one.
        float w = smoothstep(0.0, 1.0, s.t / s.F);
        col = mix(soft(px, s.t + s.T, s, flash), col, w);
    }

    if (s.rain > 0.0) {
        float r = rainLayer(px, s, 7.0, 16.0, 50.0, 0.9, 0.7, 1.0) * 0.22
                + rainLayer(px, s, 12.0, 20.0, 84.0, 1.15, 0.65, 2.0) * 0.32
                + rainLayer(px, s, 22.0, 25.0, 140.0, 1.6, 0.5, 3.0) * 0.42;
        float3 rc = mix(float3(0.78, 0.84, 0.92), float3(1.0), flash * 0.5);
        col = mix(col, rc, clamp(r, 0.0, 0.8));
    }
    if (s.snow > 0.0) {
        float f = snowLayer(px, s, 20.0, 3.0, 2.0, 8.0, 0.9, 4.0) * 0.6
                + snowLayer(px, s, 36.0, 4.0, 3.4, 14.0, 0.8, 5.0) * 0.85
                + snowLayer(px, s, 70.0, 6.0, 7.0, 26.0, 0.6, 6.0) * 0.6;
        col = mix(col, float3(0.97, 0.98, 1.0), clamp(f, 0.0, 0.95));
    }

    // Lightning: the whole sky brightens; strong flashes show a bolt.
    if (flash > 0.0) {
        col += float3(0.10, 0.11, 0.15) * flash;
        if (boltIndex >= 0) {
            float bx = (0.35 + 0.55 * hash12(float2(float(boltIndex), 3.0))) * s.W;
            float yEnd = (0.45 + 0.25 * hash12(float2(float(boltIndex), 8.0))) * s.H;
            float y = px.y;
            float xb = bx + (fbm(float2(y / s.H * 7.0, float(boltIndex) * 13.0), 5) - 0.5) * 0.28 * s.H
                          + (vnoise(float2(y / s.H * 40.0, float(boltIndex))) - 0.5) * 0.03 * s.H;
            float d = abs(px.x - xb);
            float on = step(y, yEnd) * smoothstep(yEnd, yEnd - 0.05 * s.H, y);
            col += float3(0.85, 0.88, 1.0) * boltA * on * (smoothstep(1.8, 0.0, d) + 0.35 * exp(-d / 14.0));
        }
    }

    // For the UI: a little darker toward the bottom and the sidebar on the left.
    float y = px.y / s.H, x = px.x / s.W;
    col *= 1.0 - s.vignette * (0.55 * smoothstep(0.35, 1.0, y) + 0.35 * (1.0 - smoothstep(0.0, 0.28, x)));

    col = clamp(col, 0.0, 1.0);
    uint i = (gid.y * uint(s.W) + gid.x) * 3;
    out[i] = ushort(col.r * 65535.0 + 0.5);
    out[i + 1] = ushort(col.g * 65535.0 + 0.5);
    out[i + 2] = ushort(col.b * 65535.0 + 0.5);
}
