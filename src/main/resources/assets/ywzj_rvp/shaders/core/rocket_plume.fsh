#version 150
// RVP 自有程序喷焰：不读取本体贴图，不写场景深度，所有发光参数由 Java 代码提供。
uniform sampler2D SceneDepth;
uniform sampler2D DistantDepth;
uniform mat4 InverseProjection;
uniform mat4 Projection;
uniform mat4 DistantProjection;
uniform mat4 InverseDistantProjection;
uniform mat4 DistantToView;
uniform mat4 ViewToDistant;
uniform int HasDistantDepth;
uniform float NearEmptyDepth;
uniform float DistantEmptyDepth;
uniform float HandDepthLimit;
uniform vec2 ScreenSize;
// 喷口观察空间位置与实际长度；轴向单位向量与实际最大半径。
uniform vec4 PlumeOriginLength;
uniform vec4 PlumeAxisRadius;
// HDR 核心上限、橙焰强度、逐弹稳定相位、仅光晕输入的软压缩峰值。
uniform vec4 PlumeEmission;
uniform float Time;
uniform int OutputMode;
in vec2 screenUV;
out vec4 fragColor;

vec3 viewPosition(vec2 uv, float depth) {
    vec4 point = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return point.xyz / (abs(point.w) < 0.000001 ? 0.000001 : point.w);
}

// 分别逆投影近景/DH，再在同一玩家观察空间选择最近表面；w=0 表示天空，w=-1 表示手持物。
vec4 scenePosition(vec2 uv) {
    float depth = texture(SceneDepth, uv).r;
    float nearOrderedDepth = NearEmptyDepth > 0.5 ? depth : 1.0 - depth;
    if (HandDepthLimit > 0.0 && nearOrderedDepth < HandDepthLimit) { return vec4(0.0, 0.0, 0.0, -1.0); }
    vec3 position = vec3(0.0);
    bool found = abs(depth - NearEmptyDepth) > 0.0000001;
    if (found) {
        position = viewPosition(uv, depth);
        found = !any(isnan(position)) && !any(isinf(position)) && position.z < 0.0;
    }
    if (HasDistantDepth != 0) {
        // 从主画面射线换算 DH 的 UV，允许分辨率、视野、矩阵抖动和深度方向不同。
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
                            position = candidate;
                            found = true;
                        }
                    }
                }
            }
        }
    }
    return vec4(position, found ? 1.0 : 0.0);
}


// 封闭有限圆柱的视线区间，包住收尖喷流；轴向/径向接近平行时分别处理，防止除零。
bool plumeInterval(vec3 ray, vec3 origin, vec3 axis, float extent, float radius, out vec2 interval) {
    vec3 relative = -origin;
    float axialOrigin = dot(relative, axis);
    float axialRay = dot(ray, axis);
    vec3 radialOrigin = relative - axis * axialOrigin;
    vec3 radialRay = ray - axis * axialRay;
    float a = dot(radialRay, radialRay);
    float b = dot(radialOrigin, radialRay);
    float c = dot(radialOrigin, radialOrigin) - radius * radius;
    float first = -1.0e20;
    float last = 1.0e20;
    if (a < 0.000001) {
        if (c > 0.0) { return false; }
    } else {
        float discriminant = b * b - a * c;
        if (discriminant <= 0.0) { return false; }
        float root = sqrt(discriminant);
        first = (-b - root) / a;
        last = (-b + root) / a;
    }
    if (abs(axialRay) < 0.000001) {
        if (axialOrigin < 0.0 || axialOrigin > extent) { return false; }
    } else {
        float end0 = -axialOrigin / axialRay;
        float end1 = (extent - axialOrigin) / axialRay;
        first = max(first, min(end0, end1));
        last = min(last, max(end0, end1));
    }
    interval = vec2(max(0.0, first), last);
    return last > interval.x;
}

// 整条喷流只用已配对的近景/DH 深度裁剪；不因喷口被弹体挡住而丢弃实际露出的尾段。
vec3 plumeRadiance(vec3 ray, float sceneDistance) {
    vec3 origin = PlumeOriginLength.xyz;
    float extent = PlumeOriginLength.w;
    vec3 axis = normalize(PlumeAxisRadius.xyz);
    float radius = PlumeAxisRadius.w;
    if (extent <= 0.0 || radius <= 0.0 || PlumeEmission.x <= 0.0) { return vec3(0.0); }
    vec3 center = origin + axis * extent * 0.5;
    float bound = length(vec2(extent * 0.5, radius));
    if (center.z - bound >= 0.0) { return vec3(0.0); }
    float pixelWorld = 2.0 * max(abs(center.z), 0.01) / max(ScreenSize.y * abs(Projection[1][1]), 0.0001);
    // 亚像素喷流只补屏幕采样覆盖，不扩大参与墙体遮挡的实际三维尺寸。
    if (bound < pixelWorld * 1.5) {
        vec4 clip = Projection * vec4(center, 1.0);
        if (clip.w <= 0.00001) { return vec3(0.0); }
        vec2 pixelDelta = (screenUV - (clip.xy / clip.w * 0.5 + 0.5)) * ScreenSize;
        float coverage = exp(-dot(pixelDelta, pixelDelta) * 0.7);
        float distanceAlong = dot(ray, center);
        float axialFacing = abs(dot(ray, axis));
        float frontExtent = extent * 0.5 * axialFacing + radius * sqrt(max(0.0, 1.0 - axialFacing * axialFacing));
        if (distanceAlong <= 0.0 || sceneDistance < distanceAlong - frontExtent - 0.01) { return vec3(0.0); }
        return vec3(1.0, 0.38, 0.07) * PlumeEmission.x * coverage;
    }
    vec2 interval;
    if (!plumeInterval(ray, origin, axis, extent, radius, interval)) { return vec3(0.0); }
    interval.y = min(interval.y, sceneDistance - 0.008);
    if (interval.y <= interval.x) { return vec3(0.0); }
    float stepLength = (interval.y - interval.x) / 20.0;
    float core = 0.0;
    float outer = 0.0;
    float phase = Time * 13.0 + PlumeEmission.z * 6.283185;
    for (int step = 0; step < 20; step++) {
        vec3 relative = ray * (interval.x + (float(step) + 0.5) * stepLength) - origin;
        float axial = dot(relative, axis);
        float u = clamp(axial / extent, 0.0, 1.0);
        // 喷口细、稍后膨胀、末端收尖；几何长宽只受 flame_scale 的统一缩放。
        float width = radius * max(0.01, (0.22 + 0.78 * sin(3.141593 * sqrt(u))) * pow(1.0 - u, 0.45));
        float wave = 0.94 + 0.06 * sin(u * 31.0 - phase);
        width *= wave;
        vec3 perpendicular = relative - axis * axial;
        float r2 = dot(perpendicular, perpendicular) / max(width * width, 0.00000001);
        float envelope = smoothstep(0.0, 0.025, u) * pow(1.0 - u, 0.65);
        // 轴向明暗结节模拟火箭喷流的激波菱形；不读取纹理，不跟粒子数量累积。
        float shock = pow(0.5 + 0.5 * cos(u * 43.0 - phase * 0.22), 6.0);
        float hot = pow(max(0.0, 1.0 - r2 / 0.12), 4.0) * envelope * (0.45 + 0.55 * shock);
        core = max(core, min(1.0, hot * 3.0));
        outer += pow(max(0.0, 1.0 - r2), 2.0) * envelope * (0.65 + 0.35 * shock);
    }
    outer *= stepLength / max(radius * 2.0, 0.000001);
    // HDR 内保留橙色比例，显示响应让高亮核心达到白点；光晕提取仍得到橙色。
    return vec3(1.0, 0.38, 0.07) * (core * PlumeEmission.x + outer * PlumeEmission.y);
}

void main() {
    vec4 surface = scenePosition(screenUV);
    if (surface.w < -0.5) { fragColor = vec4(0.0); return; }
    vec3 ray = normalize(viewPosition(screenUV, 0.5));
    // 天空不使用近景远平面裁掉远程克隆喷流；有表面时仍严格按真实深度截断。
    float sceneDistance = surface.w > 0.5 ? length(surface.xyz) : 1.0e20;
    vec3 emission = clamp(plumeRadiance(ray, sceneDistance), vec3(0.0), vec3(60000.0));
    if (OutputMode == 0) {
        // 原始辐射量供显卡诊断回读，生产显示使用模式 2，光晕输入使用模式 1。
        fragColor = vec4(emission, dot(emission, vec3(0.2126, 0.7152, 0.0722)));
    } else if (OutputMode == 1) {
        // 白热核心与光晕输入分开：60000 直接模糊会让有限核的边界也饱和成方框。
        float peak = max(emission.r, max(emission.g, emission.b));
        float limit = max(PlumeEmission.w, 0.001);
        vec3 bloomInput = emission * (limit * (1.0 - exp(-peak / limit)) / max(peak, 0.0001));
        fragColor = vec4(bloomInput, dot(bloomInput, vec3(0.2126, 0.7152, 0.0722)));
    } else {
        fragColor = vec4(1.0 - exp(-emission), 0.0);
    }
}
