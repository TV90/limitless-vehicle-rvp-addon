#version 150
// 程序大爆炸：不读取火焰/烟图片，逐像素沿视线累积自发光、烟吸收及空气折射。
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D DistantDepth;
// 与同帧深度配对的投影；DH深度先还原到自己的空间，再变换到近景观察空间。
uniform mat4 InverseProjection;
uniform mat4 Projection;
uniform mat4 DistantProjection;
uniform mat4 InverseDistantProjection;
uniform mat4 DistantToView;
uniform mat4 ViewToDistant;
uniform mat4 ViewToWorld;
uniform int HasDistantDepth;
uniform float NearEmptyDepth;
uniform float DistantEmptyDepth;
uniform float HandDepthLimit;
uniform vec2 ScreenSize;
// 观察空间爆心xyz、保守世界包围半径；世界坐标在Java先减相机，避免远坐标精度损失。
uniform vec4 ExplosionCenter;
// 当前年龄、总寿命、膨胀时间、明火时间，全部单位秒。
uniform vec4 Timeline;
// 火球峰值半径、竖向倍率、轮廓分瓣、归一化噪声频率。
uniform vec4 Shape;
// 翻卷速度、黑烟后期膨胀、固定形态种子、白热核心占比。
uniform vec4 Motion;
// 火焰亮度、外焰绿分量、外焰蓝分量；红分量固定为1。
uniform vec3 FireColor;
// 黑烟浓度、基础灰度。
uniform vec2 Smoke;
// 闪光强度、闪光持续秒数、折射像素位移、空气壳扩散秒数。
uniform vec4 Air;
// 每个像素的最大积分次数，硬限制在16至64。
uniform int RaySteps;
in vec2 screenUV;
out vec4 fragColor;

vec3 viewPosition(vec2 uv, float depth) {
    vec4 p = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / (abs(p.w) < 0.000001 ? 0.000001 : p.w);
}

// w=0是天空，w=-1是光影提前画出的手持物；近景与远景选同一观察空间的最近表面。
vec4 scenePosition(vec2 uv) {
    float depth = texture(SceneDepth, uv).r;
    float ordered = NearEmptyDepth > 0.5 ? depth : 1.0 - depth;
    if (HandDepthLimit > 0.0 && ordered < HandDepthLimit) { return vec4(0.0, 0.0, 0.0, -1.0); }
    bool found = abs(depth - NearEmptyDepth) > 0.0000001;
    vec3 position = vec3(0.0);
    if (found) {
        position = viewPosition(uv, depth);
        found = !any(isnan(position)) && !any(isinf(position)) && position.z < 0.0;
    }
    if (HasDistantDepth != 0) {
        vec4 distantClip = DistantProjection * ViewToDistant * vec4(viewPosition(uv, 0.5), 0.0);
        if (distantClip.w > 0.000001) {
            vec2 distantUV = distantClip.xy / distantClip.w * 0.5 + 0.5;
            if (all(greaterThanEqual(distantUV, vec2(0.0))) && all(lessThanEqual(distantUV, vec2(1.0)))) {
                float distantDepth = texture(DistantDepth, distantUV).r;
                if (abs(distantDepth - DistantEmptyDepth) > 0.0000001) {
                    vec4 distantPoint = InverseDistantProjection * vec4(distantUV * 2.0 - 1.0, distantDepth * 2.0 - 1.0, 1.0);
                    if (abs(distantPoint.w) > 0.0000001) {
                        vec3 candidate = (DistantToView * vec4(distantPoint.xyz / distantPoint.w, 1.0)).xyz;
                        if (!any(isnan(candidate)) && !any(isinf(candidate)) && candidate.z < 0.0
                                && (!found || candidate.z > position.z)) {
                            position = candidate; found = true;
                        }
                    }
                }
            }
        }
    }
    return vec4(position, found ? 1.0 : 0.0);
}

// 稳定三维值噪声；同一种子和年龄必定复现同一形态，不依赖屏幕像素随机数。
float hash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x),
                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}

// 三层连续噪声组成大瓣、中块和细节；不为每次采样循环任意增加层数。
float cloudNoise(vec3 p) {
    return noise(p) * 0.58 + noise(p * 2.03 + vec3(13.2, 7.1, 19.7)) * 0.28
            + noise(p * 4.07 + vec3(4.8, 23.1, 8.3)) * 0.14;
}

// 平滑合并圆鼓包；保留一团相连的体积，避免用方向噪声把火球拉成放射状尖刺。
float mergeLobe(float a, float b) {
    float h = clamp(0.5 + 0.5 * (b - a) / 0.16, 0.0, 1.0);
    return mix(b, a, h) - 0.16 * h * (1.0 - h);
}

float lobedSurface(vec3 q) {
    float shape = length(q) - 0.64;
    shape = mergeLobe(shape, length(q - vec3(0.5, 0.12, 0.0)) - 0.43);
    shape = mergeLobe(shape, length(q - vec3(-0.46, 0.26, 0.08)) - 0.45);
    shape = mergeLobe(shape, length(q - vec3(0.12, 0.53, -0.12)) - 0.42);
    shape = mergeLobe(shape, length(q - vec3(-0.02, -0.42, 0.26)) - 0.44);
    shape = mergeLobe(shape, length(q - vec3(0.08, 0.12, 0.5)) - 0.43);
    return mergeLobe(shape, length(q - vec3(-0.12, 0.03, -0.5)) - 0.45);
}

// 只在球体的真实可见光路上积分，镜头进入爆炸内部也使用正确的近端。
bool sphereInterval(vec3 ray, float radius, out vec2 range) {
    float b = dot(ray, ExplosionCenter.xyz);
    float d = b * b - dot(ExplosionCenter.xyz, ExplosionCenter.xyz) + radius * radius;
    if (d <= 0.0) { return false; }
    float root = sqrt(d);
    range = vec2(max(0.0, b - root), b + root);
    return range.y > range.x;
}

void main() {
    float age = Timeline.x;
    float life = Timeline.y;
    if (age < 0.0 || age >= life) { discard; }
    vec4 opaque = scenePosition(screenUV);
    if (opaque.w < -0.5) { discard; }
    vec3 ray = normalize(viewPosition(screenUV, 0.5));
    float cool = 1.0 - smoothstep(Timeline.w * 0.18, Timeline.w, age);
    float smokeAmount = smoothstep(Timeline.w * 0.12, Timeline.w * 0.8, age);
    float grow = 0.06 + 0.94 * (1.0 - exp(-5.0 * age / Timeline.z));
    float radius = Shape.x * (grow + Motion.y * smokeAmount * age / life);
    float waveProgress = clamp(age / Air.w, 0.0, 1.0);
    float waveRadius = Shape.x * (0.1 + 1.7 * sqrt(waveProgress));
    float waveStrength = (1.0 - waveProgress) * (1.0 - waveProgress);
    // 使用当帧实际大小缩短积分区间；Java传入的更大范围只用于保守裁剪。
    float bound = min(ExplosionCenter.w, max(radius * max(1.0, Shape.y),
            waveStrength > 0.0 ? waveRadius + Shape.x * 0.085 : 0.0) * 1.02);
    vec2 range;
    if (!sphereInterval(ray, bound, range)) { discard; }
    float sceneDistance = opaque.w > 0.5 ? max(0.0, length(opaque.xyz) - 0.01) : 1.0e19;
    range.y = min(range.y, sceneDistance);
    if (range.y <= range.x) { discard; }
    int steps = clamp(RaySteps, 16, 64);
    float stepLength = (range.y - range.x) / float(steps);
    float fade = 1.0 - smoothstep(life * 0.62, life, age);
    float flash = Air.x * exp(-6.0 * age * age / (Air.y * Air.y));
    float clock = age * Motion.x;
    vec3 seed = vec3(Motion.z * 0.731, Motion.z * 0.317, Motion.z * 0.193);
    vec3 accumulated = vec3(0.0);
    float transmittance = 1.0;
    float wave = 0.0;
    float heat = 0.0;
    for (int i = 0; i < 64; i++) {
        if (i >= steps || transmittance < 0.008) { break; }
        vec3 point = ray * (range.x + (float(i) + 0.5) * stepLength);
        vec3 relative = (ViewToWorld * vec4(point - ExplosionCenter.xyz, 0.0)).xyz;
        float distanceToCenter = length(relative);
        float shellWidth = max(Shape.x * 0.085, stepLength * 0.8);
        float shell = 1.0 - smoothstep(0.0, shellWidth, abs(distanceToCenter - waveRadius));
        wave = max(wave, shell * waveStrength * transmittance * fade);
        vec3 q = relative / (radius * vec3(1.0, Shape.y, 1.0));
        float radial = length(q);
        if (radial >= 1.0) { continue; }
        vec3 direction = q / max(radial, 0.001);
        float lobeAngle = Motion.z * 0.39 + clock * 0.12;
        vec3 lobePoint = q;
        lobePoint.xz = mat2(cos(lobeAngle), -sin(lobeAngle), sin(lobeAngle), cos(lobeAngle)) * q.xz;
        float surface = mix(radial - 1.0, lobedSurface(lobePoint), clamp(Shape.z * 2.2, 0.0, 1.0));
        float shapedRadius = clamp(surface + 1.0, 0.0, 1.0);
        vec3 flow = q * Shape.w + seed - vec3(0.0, clock * 0.65, 0.0);
        // 连续空间扭动让火舌向上翻起；关闭翻卷速度时内部完全冻结。
        flow += vec3(sin(q.y * 3.0 - clock), sin(q.z * 2.7 + clock * 0.7),
                sin(q.x * 3.1 + clock * 0.8)) * 0.3;
        float field = cloudNoise(flow);
        // 噪声同时侵蚀轮廓和热区，避免沿视线平均后只剩一颗均匀发黄的软球。
        float erodedSurface = surface + (0.5 - field) * Shape.z * 0.55;
        float edge = 1.0 - smoothstep(-0.2, 0.0, erodedSurface);
        float pockets = smoothstep(0.28, 0.68, field);
        float temperature = clamp((1.0 - shapedRadius) * 0.3 + field * 1.5 - 0.35
                + Motion.w * 0.3 - (1.0 - cool) * 0.65, 0.0, 1.0);
        float fireDensity = edge * pockets * 2.2 * cool;
        float smokeDensity = edge * (0.35 + pockets * 0.8) * Smoke.x * smokeAmount;
        float flashDensity = edge * exp(-radial * radial * 10.0) * flash * 4.0;
        float density = (fireDensity + smokeDensity + flashDensity) * fade / max(radius, 0.05);
        if (density < 0.00001) { continue; }
        vec3 warm = vec3(1.0, FireColor.y, FireColor.z);
        vec3 hot = mix(warm * vec3(0.7, 0.16, 0.025), warm, smoothstep(0.3, 0.72, temperature));
        hot = mix(hot, vec3(1.0, 0.88, 0.6), smoothstep(0.72, 1.0, temperature) * Motion.w);
        vec3 flame = 1.0 - exp(-hot * FireColor.x * pow(cool, 0.8) * temperature * 1.7);
        // 暗缝与亮瓣共享世界噪声；低温细节变红变暗，核心仍可达到白热峰值。
        flame *= 0.3 + 0.7 * smoothstep(0.23, 0.62, field);
        float topLight = clamp(0.5 + direction.y * 0.5 + pockets * 1.25, 0.2, 2.0);
        vec3 smokeColor = vec3(Smoke.y * topLight)
                + warm * cool * cool * exp(-shapedRadius * 2.0) * 0.24;
        vec3 sampleColor = (flame * fireDensity + smokeColor * smokeDensity + vec3(1.0, 0.94, 0.82) * flashDensity)
                / max(fireDensity + smokeDensity + flashDensity, 0.00001);
        float opacity = 1.0 - exp(-density * stepLength * 2.3);
        accumulated += transmittance * opacity * sampleColor;
        heat = max(heat, cool * edge * transmittance * (1.0 - opacity) * fade);
        transmittance *= 1.0 - opacity;
    }
    vec4 original = texture(SceneColor, screenUV);
    vec2 warpedUV = screenUV;
    if (Air.z > 0.0 && (wave > 0.001 || heat > 0.001)) {
        vec3 middle = (ViewToWorld * vec4(ray * ((range.x + range.y) * 0.5) - ExplosionCenter.xyz, 0.0)).xyz;
        vec2 ripple = vec2(noise(middle * 0.8 + seed + clock),
                noise(middle * 0.8 + seed + vec3(9.2, 3.7, 12.1) - clock)) * 2.0 - 1.0;
        vec4 centerClip = Projection * vec4(ExplosionCenter.xyz, 1.0);
        vec2 radialDirection = centerClip.w > 0.001 ? screenUV - (centerClip.xy / centerClip.w * 0.5 + 0.5) : vec2(0.0);
        radialDirection /= max(length(radialDirection), 0.001);
        vec2 offset = (radialDirection * wave + ripple * heat * 0.65) * Air.z / ScreenSize;
        warpedUV = clamp(screenUV + offset, vec2(0.001), vec2(0.999));
        // 折射后若采到火球前面的墙或手持物则撤销偏移，避免前景轮廓被拉进火焰。
        vec4 shifted = scenePosition(warpedUV);
        if (shifted.w < -0.5 || shifted.w > 0.5 && length(shifted.xyz) < range.x) { warpedUV = screenUV; }
    }
    // 背景副本已经包含本帧光照和尾烟；全颜色合成同时保留原场景Alpha。
    vec3 background = texture(SceneColor, warpedUV).rgb;
    fragColor = vec4(accumulated + background * transmittance, original.a);
}
