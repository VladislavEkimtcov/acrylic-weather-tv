// Renders Acrylic Weather's sky loops on the Mac's GPU (Metal) and encodes them with ffmpeg.
//
//   swiftc -O -o build/skyrender tools/sky/SkyRender.swift
//   build/skyrender tools/sky/Sky.metal assets/sky                  every scene, HEVC Main10
//   build/skyrender tools/sky/Sky.metal /tmp/look --still 3 storm   one 16-bit PNG at t = 3 s
//   options: --scenes a,b,c  --crf 26  --still <seconds>  (bare words after the paths = scenes)
//
// Frames are 1920x1080, 30 fps, rgb48 straight into x265 as 10-bit 4:2:0 BT.709. The loop is
// seamless by construction (see Sky.metal); one IDR at the start, so the app's gapless looper
// seeks back to a keyframe.

import Foundation
import Metal

let W = 1920, H = 1080, FPS = 30

func rgb(_ hex: String) -> SIMD3<Float> {
    let v = UInt32(hex, radix: 16) ?? 0
    return SIMD3(Float((v >> 16) & 0xFF) / 255, Float((v >> 8) & 0xFF) / 255, Float(v & 0xFF) / 255)
}

struct Scene {
    var name: String
    var T: Float = 16, F: Float = 4, seed: Float = 0, night: Float = 0, wind: Float = 0.12
    var top = "000000", mid = "000000", midPos: Float = 0.55, bottom = "000000"
    var glowColor = "000000", glow: Float = 0, glowPos = SIMD2<Float>(0.7, 1.0)
    var sunAmt: Float = 0, moonAmt: Float = 0
    // Up in the free band between the city name and the clock, clear of the hero text.
    var sun = SIMD2<Float>(0.70, 0.075), moon = SIMD2<Float>(0.70, 0.08)
    var sunColor = "FFF2D2", stars: Float = 0
    var lit = "FFFFFF", cover: Float = 0.3, shade = "A0B0C4", dark: Float = 0
    var drift: Float = 16, rain: Float = 0, snow: Float = 0, fog: Float = 0
    var fogColor = "C0C8D0", storm: Float = 0
    var vignette: Float = 0.28, rays: Float = 0, moonPhase: Float = 0.41
    /// 0: no clouds in the video (the app draws them live, see Overlay.java). low: banks on the ground.
    var clouds: Float = 1, low: Float = 0

    init(_ name: String, _ configure: (inout Scene) -> Void) {
        self.name = name
        configure(&self)
    }

    /// The kernel's P[0...14]; see unpack() in Sky.metal.
    func pack(t: Float) -> [SIMD4<Float>] {
        func v(_ c: SIMD3<Float>, _ w: Float) -> SIMD4<Float> { SIMD4(c.x, c.y, c.z, w) }
        return [
            SIMD4(Float(W), Float(H), t, T),
            SIMD4(F, seed, night, wind),
            v(rgb(top), 0), v(rgb(mid), midPos), v(rgb(bottom), 0),
            v(rgb(glowColor), glow), SIMD4(glowPos.x, glowPos.y, sunAmt, moonAmt),
            SIMD4(sun.x, sun.y, moon.x, moon.y),
            v(rgb(sunColor), stars), v(rgb(lit), cover), v(rgb(shade), dark),
            SIMD4(drift, rain, snow, fog), v(rgb(fogColor), storm),
            SIMD4(vignette, rays, moonPhase, 0),
            SIMD4(clouds, low, 0, 0),
        ]
    }
}

// The same names as Scenes.java; top/bottom roughly match its colors() (the app's veil).
let scenes: [Scene] = [
    Scene("clear-day") { s in
        s.top = "1557B0"; s.mid = "3C86D8"; s.bottom = "8DBDE8"; s.sunAmt = 1; s.rays = 1; s.clouds = 0
        s.cover = 0.2; s.shade = "B4C6DC"; s.drift = 14; s.wind = 0.1; s.seed = 1
    },
    Scene("clear-night") { s in
        s.top = "030817"; s.mid = "0B1734"; s.midPos = 0.6; s.bottom = "1D3160"; s.night = 1
        s.clouds = 0; s.stars = 1; s.cover = 0.14; s.lit = "8E9CB8"; s.shade = "222C40"; s.drift = 10; s.seed = 2
    },
    Scene("partly-day") { s in
        s.top = "2160A8"; s.mid = "4C8ACB"; s.bottom = "9CC1E3"; s.sunAmt = 0.9; s.sun = SIMD2(0.72, 0.07)
        s.rays = 0.7; s.clouds = 0; s.cover = 0.5; s.shade = "A8B8CD"; s.drift = 18; s.seed = 3
    },
    Scene("partly-night") { s in
        s.top = "050B1B"; s.mid = "101C38"; s.bottom = "23355C"; s.night = 1; s.clouds = 0
        s.stars = 0.85; s.cover = 0.46; s.lit = "7F8DA8"; s.shade = "1E2638"
        s.drift = 14; s.seed = 4
    },
    Scene("cloudy-day") { s in
        s.top = "3E4D5F"; s.mid = "5D6C7E"; s.bottom = "84919F"; s.cover = 0.93; s.dark = 0.35
        s.lit = "E6EBF0"; s.shade = "7A8694"; s.drift = 22; s.seed = 5
    },
    Scene("cloudy-night") { s in
        s.top = "0C1119"; s.mid = "161D28"; s.bottom = "2A3340"; s.night = 1; s.cover = 0.9; s.dark = 0.3
        s.lit = "4C5666"; s.shade = "181E27"; s.drift = 18; s.seed = 6
    },
    Scene("fog-day") { s in
        s.top = "5D6873"; s.mid = "7A848E"; s.bottom = "9EA7B0"; s.low = 1; s.cover = 0.62; s.dark = 0.55
        s.lit = "F0F3F6"; s.shade = "6F7985"; s.fog = 0.28; s.fogColor = "C2C9D0"; s.drift = 6; s.seed = 7
    },
    Scene("fog-night") { s in
        s.top = "12171E"; s.mid = "1E252E"; s.bottom = "384049"; s.night = 1; s.low = 1; s.cover = 0.62; s.dark = 0.55
        s.lit = "5C6672"; s.shade = "15191F"; s.fog = 0.25; s.fogColor = "434B55"; s.drift = 5; s.seed = 8
    },
    Scene("rain-day") { s in
        s.top = "2F3B48"; s.mid = "46525F"; s.bottom = "66737F"; s.cover = 0.97; s.dark = 0.6; s.lit = "B6BFCA"
        s.shade = "46515E"; s.rain = 0.85; s.fog = 0.22; s.fogColor = "77828E"; s.wind = 0.18; s.drift = 30; s.seed = 9
    },
    Scene("rain-night") { s in
        s.top = "080C12"; s.mid = "121A23"; s.bottom = "232D39"; s.night = 1; s.cover = 0.97; s.dark = 0.55
        s.lit = "3C4653"; s.shade = "131920"; s.rain = 0.85; s.fog = 0.18; s.fogColor = "2A333E"; s.wind = 0.18
        s.drift = 26; s.seed = 10
    },
    Scene("snow-day") { s in
        s.top = "4A5A6E"; s.mid = "6A7A8E"; s.bottom = "93A1B2"; s.cover = 0.88; s.dark = 0.3; s.lit = "DDE3EA"
        s.shade = "98A5B5"; s.snow = 0.85; s.fog = 0.2; s.fogColor = "C8D1DA"; s.wind = 0.08; s.drift = 12; s.seed = 11
    },
    Scene("snow-night") { s in
        s.top = "111824"; s.mid = "1F2A3A"; s.bottom = "3A4860"; s.night = 1; s.cover = 0.88; s.dark = 0.2
        s.lit = "5B687E"; s.shade = "212B3A"; s.snow = 0.8; s.fog = 0.15; s.fogColor = "3C485A"; s.drift = 10; s.seed = 12
    },
    Scene("storm") { s in
        s.T = 20; s.top = "12161C"; s.mid = "1E242D"; s.bottom = "39424E"; s.cover = 1; s.dark = 0.85
        s.lit = "6C7582"; s.shade = "171C24"; s.rain = 1; s.storm = 1; s.wind = 0.3; s.drift = 40
        s.fog = 0.15; s.fogColor = "2E3640"; s.seed = 13
    },
    Scene("dusk") { s in
        s.top = "26346E"; s.mid = "6A4F86"; s.midPos = 0.5; s.bottom = "F09A62"; s.glow = 1; s.glowColor = "FF9C5A"
        s.sunAmt = 1; s.sun = SIMD2(0.72, 0.8); s.sunColor = "FFC27A"; s.rays = 0.5; s.clouds = 0; s.cover = 0.35
        s.lit = "FFC9A0"; s.shade = "6B4A6E"; s.drift = 12; s.vignette = 0.22; s.seed = 14
    },
]

// ---- arguments -------------------------------------------------------------------------------

var args = Array(CommandLine.arguments.dropFirst())
guard args.count >= 2 else {
    FileHandle.standardError.write("usage: skyrender <Sky.metal> <outdir> [--scenes a,b] [--crf N] [--still sec] [scene...]\n".data(using: .utf8)!)
    exit(2)
}
let metalPath = args.removeFirst(), outDir = args.removeFirst()
var wanted: [String] = [], crf = "26"
var still: Float? = nil
while !args.isEmpty {
    let a = args.removeFirst()
    switch a {
    case "--scenes": wanted += args.removeFirst().split(separator: ",").map(String.init)
    case "--crf": crf = args.removeFirst()
    case "--still": still = Float(args.removeFirst())
    default: wanted.append(a)
    }
}
let selected = wanted.isEmpty ? scenes : scenes.filter { wanted.contains($0.name) }
guard !selected.isEmpty else { print("no such scene: \(wanted)"); exit(2) }
try FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

// ---- Metal -----------------------------------------------------------------------------------

guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
    print("no Metal device"); exit(1)
}
let library: MTLLibrary
do {
    library = try device.makeLibrary(source: String(contentsOfFile: metalPath, encoding: .utf8), options: nil)
} catch {
    print("Metal compile failed:\n\(error)"); exit(1)
}
let pipeline = try device.makeComputePipelineState(function: library.makeFunction(name: "sky")!)
let frameBytes = W * H * 3 * 2
let buffer = device.makeBuffer(length: frameBytes, options: .storageModeShared)!

func render(_ scene: Scene, t: Float) {
    var p = scene.pack(t: t)
    let cb = queue.makeCommandBuffer()!
    let enc = cb.makeComputeCommandEncoder()!
    enc.setComputePipelineState(pipeline)
    enc.setBuffer(buffer, offset: 0, index: 0)
    enc.setBytes(&p, length: p.count * MemoryLayout<SIMD4<Float>>.stride, index: 1)
    enc.dispatchThreads(MTLSize(width: W, height: H, depth: 1), threadsPerThreadgroup: MTLSize(width: 16, height: 16, depth: 1))
    enc.endEncoding()
    cb.commit()
    cb.waitUntilCompleted()
}

func ffmpeg(_ outArgs: [String]) throws -> (Process, FileHandle) {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/opt/homebrew/bin/ffmpeg")
    p.arguments = ["-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb48le", "-s", "\(W)x\(H)",
                   "-r", "\(FPS)", "-i", "-"] + outArgs
    let pipe = Pipe()
    p.standardInput = pipe
    try p.run()
    return (p, pipe.fileHandleForWriting)
}

for scene in selected {
    let started = Date()
    if let t = still {
        let out = "\(outDir)/\(scene.name)-\(t).png"
        let (p, h) = try ffmpeg(["-frames:v", "1", out])
        render(scene, t: t)
        h.write(Data(bytesNoCopy: buffer.contents(), count: frameBytes, deallocator: .none))
        try h.close()
        p.waitUntilExit()
        print("\(out)")
        continue
    }
    let frames = Int(scene.T * Float(FPS))
    let out = "\(outDir)/\(scene.name).mp4"
    let (p, h) = try ffmpeg([
        "-vf", "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p10le",
        "-c:v", "libx265", "-preset", "slow", "-crf", crf, "-profile:v", "main10", "-tag:v", "hvc1",
        // aq-mode 3 spends bits on dark, flat areas (night skies block up otherwise); one IDR per loop.
        "-x265-params", "aq-mode=3:aq-strength=0.9:keyint=\(frames + 1):min-keyint=\(frames + 1):no-open-gop=1:bframes=4:deblock=-1,-1:log-level=error",
        "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
        "-movflags", "+faststart", out,
    ])
    for i in 0..<frames {
        render(scene, t: Float(i) / Float(FPS))
        h.write(Data(bytesNoCopy: buffer.contents(), count: frameBytes, deallocator: .none))
    }
    try h.close()
    p.waitUntilExit()
    let size = (try? FileManager.default.attributesOfItem(atPath: out)[.size] as? Int) ?? 0
    print(String(format: "%@  %d frames  %.1f MB  %.0f s", out, frames, Double(size) / 1e6, Date().timeIntervalSince(started)))
}
