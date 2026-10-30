#version 150
// 纯程序体积尾烟：世界烟段提供密度，逐像素沿视线积分，不采样任何烟雾图片。
uniform sampler2D SceneDepth;
uniform sampler2D DistantDepth;
// 近景与 DH 分别使用各自深度/投影，统一到玩家观察空间后再比较距离。
uniform mat4 InverseProjection;
uniform mat4 Projection;
uniform mat4 DistantProjection;
uniform mat4 InverseDistantProjection;
uniform mat4 DistantToView;
uniform mat4 ViewToDistant;
// 只用其旋转部分把观察空间差向量转回世界，噪声细节不跟着相机旋转。
uniform mat4 ViewToWorld;
uniform int HasDistantDepth;
uniform float NearEmptyDepth;
uniform float DistantEmptyDepth;
uniform float HandDepthLimit;
uniform vec2 ScreenSize;
// 两端的观察空间位置与世界半径，单位格。
uniform vec4 TrailStart;
uniform vec4 TrailEnd;
// 两端剩余浓度，独立于半径，淡出不会突然缩成细线。
uniform vec2 TrailDensity;
// 基础灰度、平均年龄（tick）、来源稳定相位、世界噪声频率。
uniform vec4 TrailColor;
// double 世界坐标预先求余后的噪声原点，避免远坐标精度损失。
uniform vec3 TrailNoiseOrigin;
uniform int RaySteps;
uniform int LightCount;
// 最多四盏本帧实际活动光源；位置/半径与 RGB/强度成对出现。
uniform vec4 LightPositionRadius0;
uniform vec4 LightColorIntensity0;
uniform vec4 LightPositionRadius1;
uniform vec4 LightColorIntensity1;
uniform vec4 LightPositionRadius2;
uniform vec4 LightColorIntensity2;
uniform vec4 LightPositionRadius3;
uniform vec4 LightColorIntensity3;
in vec2 screenUV;
out vec4 fragColor;

vec3 viewPosition(vec2 uv, float depth) {
    vec4 point = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return point.xyz / (abs(point.w) < 0.000001 ? 0.000001 : point.w);
}

// w=0 为天空，w=-1 为光影提前绘制的手持物；只读取不透明场景深度，烟自身不挡住后续烟。
vec4 scenePosition(vec2 uv) {
    float depth = texture(SceneDepth, uv).r;
    float ordered = NearEmptyDepth > 0.5 ? depth : 1.0 - depth;
    if (HandDepthLimit > 0.0 && ordered < HandDepthLimit) { return vec4(0.0, 0.0, 0.0, -1.0); }
    vec3 position = vec3(0.0);
    bool found = abs(depth - NearEmptyDepth) > 0.0000001;
    if (found) {
        position = viewPosition(uv, depth);
        found = !any(isnan(position)) && !any(isinf(position)) && position.z < 0.0;
    }
    if (HasDistantDepth != 0) {
        vec3 ray = viewPosition(uv, 0.5);
        vec4 distantClip = DistantProjection * ViewToDistant * vec4(ray, 0.0);
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

// 连续三维值噪声，完全由数学函数生成；没有 PNG、噪声贴图或逐帧随机重掷。
float hash(vec3 point) {
    // 与 Java 噪声原点的 4096 周期配对，跨周期的相邻烟段也能连续接上。
    point = mod(point, 4096.0);
    point = fract(point * 0.1031);
    point += dot(point, point.yzx + 33.33);
    return fract((point.x + point.y) * point.z);
}

float noise(vec3 point) {
    vec3 cell = floor(point);
    vec3 fraction = fract(point);
    fraction = fraction * fraction * (3.0 - 2.0 * fraction);
    return mix(mix(mix(hash(cell), hash(cell + vec3(1,0,0)), fraction.x),
                   mix(hash(cell + vec3(0,1,0)), hash(cell + vec3(1,1,0)), fraction.x), fraction.y),
               mix(mix(hash(cell + vec3(0,0,1)), hash(cell + vec3(1,0,1)), fraction.x),
                   mix(hash(cell + vec3(0,1,1)), hash(cell + vec3(1,1,1)), fraction.x), fraction.y), fraction.z);
}

// 先求有界圆柱/球的视线区间，侧看细烟也不会在大包围球的空白处浪费全部采样。
bool interval(vec3 ray, vec3 axis, float extent, float radius, out vec2 result) {
    vec3 relative = -TrailStart.xyz;
    if (extent < 0.0001) {
        float b = dot(relative, ray);
        float discriminant = b * b - dot(relative, relative) + radius * radius;
        if (discriminant <= 0.0) { return false; }
        float root = sqrt(discriminant);
        result = vec2(-b - root, -b + root); return true;
    }
    float axialOrigin = dot(relative, axis);
    float axialRay = dot(ray, axis);
    vec3 radialOrigin = relative - axis * axialOrigin;
    vec3 radialRay = ray - axis * axialRay;
    float a = dot(radialRay, radialRay);
    float b = dot(radialOrigin, radialRay);
    float c = dot(radialOrigin, radialOrigin) - radius * radius;
    float first = -1.0e20;
    float last = 1.0e20;
    if (a < 0.0000001) {
        if (c > 0.0) { return false; }
    } else {
        float discriminant = b * b - a * c;
        if (discriminant <= 0.0) { return false; }
        float root = sqrt(discriminant);
        first = max(first, (-b - root) / a);
        last = min(last, (-b + root) / a);
    }
    if (abs(axialRay) < 0.000001) {
        if (axialOrigin < -radius || axialOrigin > extent + radius) { return false; }
    } else {
        float firstCap = (-radius - axialOrigin) / axialRay;
        float lastCap = (extent + radius - axialOrigin) / axialRay;
        first = max(first, min(firstCap, lastCap));
        last = min(last, max(firstCap, lastCap));
    }
    result = vec2(first, last); return last > first;
}

// 光路按当前屏幕不透明深度近似裁剪，不提供完整的烟内多次散射或屏幕外世界阴影。
float lightVisibility(vec3 position, vec3 light) {
    for (int step = 1; step <= 4; step++) {
        vec3 point = mix(position, light, float(step) / 5.0);
        vec4 clip = Projection * vec4(point, 1.0);
        if (clip.w <= 0.00001) { return 0.0; }
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) { return 0.0; }
        vec4 opaque = scenePosition(uv);
        if (opaque.w > 0.5 && opaque.z > point.z + clamp(abs(point.z) * 0.001, 0.08, 0.25)) { return 0.0; }
    }
    return 1.0;
}

vec3 illumination(vec3 position) {
    vec4 positions[4] = vec4[4](LightPositionRadius0, LightPositionRadius1, LightPositionRadius2, LightPositionRadius3);
    vec4 colors[4] = vec4[4](LightColorIntensity0, LightColorIntensity1, LightColorIntensity2, LightColorIntensity3);
    vec3 result = vec3(0.0);
    for (int index = 0; index < 4; index++) {
        if (index >= LightCount) { break; }
        if (positions[index].w <= 0.0 || colors[index].a <= 0.0) { continue; }
        float radial = distance(position, positions[index].xyz) / positions[index].w;
        if (radial >= 1.0) { continue; }
        float falloff = pow(max(0.0, 1.0 - radial * radial), 2.0);
        result += colors[index].rgb * colors[index].a * falloff * lightVisibility(position, positions[index].xyz);
    }
    return 1.0 - exp(-clamp(result, vec3(0.0), vec3(60000.0)) * 0.45);
}

void main() {
    if (max(TrailDensity.x, TrailDensity.y) <= 0.00001 || max(TrailStart.w, TrailEnd.w) <= 0.0) { discard; }
    vec4 opaque = scenePosition(screenUV);
    if (opaque.w < -0.5) { discard; }
    vec3 ray = normalize(viewPosition(screenUV, 0.5));
    vec3 delta = TrailEnd.xyz - TrailStart.xyz;
    float extent = length(delta);
    vec3 axis = extent > 0.0001 ? delta / extent : vec3(0.0, 1.0, 0.0);
    // 亚像素烟用保能量的像素覆盖近似，避免远处细线随像素中心跳闪；仍由场景深度裁剪。
    float footprint = max(abs(TrailStart.z), abs(TrailEnd.z))
            / max(ScreenSize.y * abs(Projection[1][1]), 1.0);
    float boundRadius = max(TrailStart.w, TrailEnd.w) + footprint * 2.0;
    vec2 range;
    if (!interval(ray, axis, extent, boundRadius, range)) { discard; }
    float begin = max(0.0, range.x);
    float sceneDistance = opaque.w > 0.5 ? max(0.0, length(opaque.xyz) - 0.008) : 1.0e19;
    float end = min(range.y, sceneDistance);
    if (end <= begin) { discard; }
    int steps = clamp(RaySteps, 4, 24);
    float stepLength = (end - begin) / float(steps);
    // 光照在当前像素穿过的可见烟区间取代表点，不拿背景方块当作烟的空间位置。
    vec3 light = illumination(ray * (begin + end) * 0.5);
    vec3 accumulated = vec3(0.0);
    float transmittance = 1.0;
    for (int index = 0; index < 24; index++) {
        if (index >= steps || transmittance < 0.01) { break; }
        vec3 point = ray * (begin + (float(index) + 0.5) * stepLength);
        float axial = dot(point - TrailStart.xyz, axis);
        float along = extent > 0.0001 ? clamp(axial / extent, 0.0, 1.0) : 0.5;
        vec3 center = mix(TrailStart.xyz, TrailEnd.xyz, along);
        float physicalRadius = mix(TrailStart.w, TrailEnd.w, along);
        // 像素覆盖扩展只用于抗闪烁；真实烟的前缘在墙后时不能借此扩展穿到墙前。
        if (sceneDistance < dot(center, ray) - physicalRadius) { continue; }
        float radius = physicalRadius + footprint * 2.0;
        vec3 noisePoint = TrailNoiseOrigin + (ViewToWorld * vec4(point - TrailStart.xyz, 0.0)).xyz * TrailColor.w;
        noisePoint += vec3(TrailColor.z * 37.0, TrailColor.y * 0.012, TrailColor.z * 19.0);
        float billow = noise(noisePoint);
        float detail = noise(noisePoint * 2.03 + vec3(13.1, 7.7, 19.4));
        float shapeRadius = radius * (0.76 + 0.24 * billow);
        float radial = length(point - center) / max(shapeRadius, 0.0001);
        if (radial >= 1.0) { continue; }
        float longitudinal = 1.0;
        if (extent > 0.0001) {
            // 相邻段的圆端各贡献半份密度，减少接缝处重复叠加形成的串珠。
            longitudinal = smoothstep(-radius, min(radius, extent * 0.5), axial)
                    * (1.0 - smoothstep(max(extent - radius, extent * 0.5), extent + radius, axial));
        }
        float coverage = physicalRadius / max(radius, 0.0001);
        coverage *= coverage;
        if (extent < physicalRadius) { coverage *= physicalRadius / max(radius, 0.0001); }
        float density = mix(TrailDensity.x, TrailDensity.y, along) * pow(max(0.0, 1.0 - radial * radial), 1.4)
                * (0.42 + 0.58 * billow) * (0.62 + 0.38 * detail) * longitudinal * coverage;
        float opacity = 1.0 - exp(-density * stepLength * 1.7 / max(physicalRadius, 0.025));
        // 程序明暗只是烟内部层次的近似；动态光照独立叠入散射颜色，烟自身不会发出 Bloom。
        float grey = TrailColor.x * (0.72 + 0.28 * detail);
        vec3 color = clamp(vec3(grey) + light * (1.0 - grey * 0.4), 0.0, 1.0);
        accumulated += transmittance * opacity * color;
        transmittance *= 1.0 - opacity;
    }
    float alpha = clamp(1.0 - transmittance, 0.0, 1.0);
    if (alpha < 0.00001) { discard; }
    // 预乘 RGB + 独立保留目标 Alpha 的混合；绝不写 gl_FragDepth 或采样正在写入的颜色附件。
    fragColor = vec4(accumulated, alpha);
}
