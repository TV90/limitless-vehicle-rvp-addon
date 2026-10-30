#version 150
// RVP 自有程序喷焰：不读取本体贴图，不写场景深度，所有发光参数由 Java 代码提供。
uniform sampler2D SceneDepth;
uniform sampler2D DistantDepth;
// 每条喷流顺序复用的一像素可见度，写入可见度模式时绑定其它有效纹理，避免附件反馈。
uniform sampler2D PlumeVisibility;
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
// 由既有毫米口径计算的世界光晕半径，与发动机环境照明共同限制在 20～500 格。
uniform float PlumeHaloRadius;
// 广域橙晕亮度独立于喷焰外围橙焰，推进阶段由 Java 统一乘一次。
uniform float PlumeHaloIntensity;
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
vec3 plumeRadiance(vec3 ray, float sceneDistance, vec2 rayUV, vec2 intensities) {
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
        vec2 pixelDelta = (rayUV - (clip.xy / clip.w * 0.5 + 0.5)) * ScreenSize;
        float coverage = exp(-dot(pixelDelta, pixelDelta) * 0.7);
        float distanceAlong = dot(ray, center);
        float axialFacing = abs(dot(ray, axis));
        float frontExtent = extent * 0.5 * axialFacing + radius * sqrt(max(0.0, 1.0 - axialFacing * axialFacing));
        if (distanceAlong <= 0.0 || sceneDistance < distanceAlong - frontExtent - 0.01) { return vec3(0.0); }
        return vec3(1.0, 0.38, 0.07) * intensities.x * coverage;
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
    return vec3(1.0, 0.38, 0.07) * (core * intensities.x + outer * intensities.y);
}

// 只在一像素目标中执行：沿喷流七个轴向位置、每处三个横向样点，复用真实二十步辐射和近景/DH 裁剪。
// 不能按“半径五百格的大球在墙前有一部分”判断可见，否则整条尾焰在墙后也会漏出巨型光晕。
float visiblePlumeEmission() {
    if (PlumeEmission.x <= 0.0 || PlumeOriginLength.w <= 0.0 || PlumeAxisRadius.w <= 0.0) { return 0.0; }
    vec3 origin = PlumeOriginLength.xyz;
    vec3 axis = normalize(PlumeAxisRadius.xyz);
    vec3 center = origin + axis * PlumeOriginLength.w * 0.5;
    vec3 toward = dot(center, center) > 0.000001 ? normalize(center) : vec3(0.0, 0.0, -1.0);
    vec3 lateral = cross(axis, toward);
    if (dot(lateral, lateral) < 0.000001) { lateral = cross(axis, vec3(0.0, 1.0, 0.0)); }
    if (dot(lateral, lateral) < 0.000001) { lateral = vec3(1.0, 0.0, 0.0); }
    lateral = normalize(lateral);
    float visible = 0.0;
    for (int sampleIndex = 0; sampleIndex < 7; sampleIndex++) {
        float along = (float(sampleIndex) + 0.5) / 7.0;
        float slice = 0.0;
        for (int side = -1; side <= 1; side++) {
            vec3 point = origin + axis * PlumeOriginLength.w * along
                    + lateral * (float(side) * PlumeAxisRadius.w * 0.5);
            vec4 clip = Projection * vec4(point, 1.0);
            if (clip.w <= 0.000001) { continue; }
            vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
            if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) { continue; }
            vec4 surface = scenePosition(uv);
            if (surface.w < -0.5) { continue; }
            float limit = surface.w > 0.5 ? length(surface.xyz) : 1.0e20;
            // 使用单位辐射求真实几何覆盖，不让外围橙焰亮度串改广域光晕的可见比例。
            vec3 emission = plumeRadiance(normalize(point), limit, uv, vec2(1.0));
            float peak = max(emission.r, max(emission.g, emission.b));
            slice = max(slice, clamp(peak, 0.0, 1.0));
        }
        visible += slice;
    }
    return visible / 7.0;
}

// 在喷口深度的相机朝向平面上按世界半径求径向扩散，保留橙色色比，边缘平滑归零。
// 这是可见发光源的光晕，不是额外的五百格烟雾体积；只有源本身可见时才叠加。
vec3 worldHalo(vec3 ray) {
    float visible = texture(PlumeVisibility, vec2(0.5)).r;
    if (visible <= 0.0001 || PlumeHaloIntensity <= 0.0 || PlumeOriginLength.z >= -0.0001 || ray.z >= -0.0001) { return vec3(0.0); }
    vec3 point = ray * (PlumeOriginLength.z / ray.z);
    float radius = clamp(PlumeHaloRadius, 20.0, 500.0);
    vec2 delta = point.xy - PlumeOriginLength.xy;
    float radialSq = dot(delta, delta) / (radius * radius);
    if (radialSq >= 1.0) { return vec3(0.0); }
    // 世界半径决定轮廓，亮度/阶段只改变强度；指数中心和平方边缘避免硬圆盘边界。
    float falloff = exp(-6.0 * radialSq) * pow(1.0 - radialSq, 2.0);
    float response = 1.0 - exp(-PlumeHaloIntensity * visible * falloff);
    return vec3(1.0, 0.38, 0.07) * response;
}

void main() {
    // 模式 3 不使用当前一像素目标的 screenUV 查询主世界，所有遮挡由上面的实际样点位置决定。
    if (OutputMode == 3) { fragColor = vec4(visiblePlumeEmission(), 0.0, 0.0, 1.0); return; }
    vec4 surface = scenePosition(screenUV);
    if (surface.w < -0.5) { fragColor = vec4(0.0); return; }
    vec3 ray = normalize(viewPosition(screenUV, 0.5));
    if (OutputMode == 4) { fragColor = vec4(worldHalo(ray), 0.0); return; }
    // 天空不使用近景远平面裁掉远程克隆喷流；有表面时仍严格按真实深度截断。
    float sceneDistance = surface.w > 0.5 ? length(surface.xyz) : 1.0e20;
    vec3 emission = clamp(plumeRadiance(ray, sceneDistance, screenUV, PlumeEmission.xy), vec3(0.0), vec3(60000.0));
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
