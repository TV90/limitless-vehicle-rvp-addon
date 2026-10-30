#version 150
// 原世界颜色只读；整个管线只修改最终 RGB，保持原有 Alpha。
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
// DH 深度使用独立投影；不能直接与近景的 0～1 深度值比较。
uniform sampler2D DistantDepth;
// 和天气阶段深度配对的矩阵，位置都在观察空间中计算。
uniform mat4 InverseProjection;
uniform mat4 Projection;
uniform mat4 DistantProjection;
uniform mat4 InverseDistantProjection;
uniform mat4 DistantToView;
uniform mat4 ViewToDistant;
uniform int HasDistantDepth;
uniform float NearEmptyDepth;
uniform float DistantEmptyDepth;
// 活动光影提前绘制的手持物使用压缩深度，只作为遮罩，不当作真实场景几何。
uniform float HandDepthLimit;
uniform vec2 ScreenSize;
uniform int LightCount;
uniform int OutputMode;
// 每盏光独立的喷口参数：核心半径、橙色气团半径、核心 HDR 峰值、气团 HDR 峰值。
// 普通爆炸和炮口槽位清零，不用扩大环境光半径伪造喷焰。
uniform vec4 LightEmission0;
uniform vec4 LightEmission1;
uniform vec4 LightEmission2;
uniform vec4 LightEmission3;
uniform vec4 LightEmission4;
uniform vec4 LightEmission5;
uniform vec4 LightEmission6;
uniform vec4 LightEmission7;
uniform vec4 LightPositionRadius0;
uniform vec4 LightColorIntensity0;
uniform vec4 LightShape0;
uniform vec4 LightDirection0;
uniform vec4 LightPositionRadius1;
uniform vec4 LightColorIntensity1;
uniform vec4 LightShape1;
uniform vec4 LightDirection1;
uniform vec4 LightPositionRadius2;
uniform vec4 LightColorIntensity2;
uniform vec4 LightShape2;
uniform vec4 LightDirection2;
uniform vec4 LightPositionRadius3;
uniform vec4 LightColorIntensity3;
uniform vec4 LightShape3;
uniform vec4 LightDirection3;
uniform vec4 LightPositionRadius4;
uniform vec4 LightColorIntensity4;
uniform vec4 LightShape4;
uniform vec4 LightDirection4;
uniform vec4 LightPositionRadius5;
uniform vec4 LightColorIntensity5;
uniform vec4 LightShape5;
uniform vec4 LightDirection5;
uniform vec4 LightPositionRadius6;
uniform vec4 LightColorIntensity6;
uniform vec4 LightShape6;
uniform vec4 LightDirection6;
uniform vec4 LightPositionRadius7;
uniform vec4 LightColorIntensity7;
uniform vec4 LightShape7;
uniform vec4 LightDirection7;
in vec2 screenUV;
out vec4 fragColor;

// 通过深度还原当前像素对应的可见表面；不改变 Minecraft 方块光。
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

// 天空/手持物邻居不参与表面法线估计，避免 DH 交界处出现无穷大或黑边。
vec3 neighborPosition(vec2 uv, vec3 fallback) {
    vec4 samplePosition = scenePosition(uv);
    return samplePosition.w > 0.5 ? samplePosition.xyz : fallback;
}

// 喷口独立自发光：天空也能看见，默认关闭的空气散射不影响它。
// 光心和当前像素都检查近景/DH 遮挡，完整藏在墙后的光源不能向墙外画一个光团。
vec3 sourceEmission(vec3 ray, float sceneDistance, vec4 light, vec3 color, vec4 emission) {
    if (emission.x <= 0.0 || emission.z <= 0.0 || light.z >= 0.0) { return vec3(0.0); }
    vec4 clip = Projection * vec4(light.xyz, 1.0);
    if (clip.w <= 0.00001) { return vec3(0.0); }
    vec2 centerUV = clip.xy / clip.w * 0.5 + 0.5;
    if (any(lessThan(centerUV, vec2(0.0))) || any(greaterThan(centerUV, vec2(1.0)))) { return vec3(0.0); }
    vec4 centerSurface = scenePosition(centerUV);
    if (centerSurface.w < -0.5) { return vec3(0.0); }
    if (centerSurface.w > 0.5 && centerSurface.z > light.z + emission.x + 0.04) { return vec3(0.0); }
    float along = dot(ray, light.xyz);
    if (along <= 0.0 || sceneDistance < along - emission.x - 0.04) { return vec3(0.0); }
    // 远距离保留至少两个像素半径，半分辨率提取时不能因采样位置而让明亮喷口忽明忽灭。
    float pixelRadius = 4.0 * abs(light.z) / max(ScreenSize.y * abs(Projection[1][1]), 0.0001);
    float coreRadius = max(emission.x, pixelRadius);
    float haloRadius = max(emission.y, coreRadius * 3.5);
    // 用向量差计算垂距，避免远距离两个大数相减丢失喷口尺度的精度。
    vec3 delta = light.xyz - ray * along;
    float radialSquared = dot(delta, delta);
    if (radialSquared > haloRadius * haloRadius * 4.0) { return vec3(0.0); }
    float core = exp(-4.0 * radialSquared / (coreRadius * coreRadius)) * emission.z;
    float halo = exp(-4.0 * radialSquared / (haloRadius * haloRadius)) * emission.w;
    return color * (core + halo);
}

// 深度邻域中选同侧较连续的边，减少轮廓处将背景误当法线的问题。
vec3 surfaceNormal(vec2 uv, vec3 position) {
    vec2 pixel = 1.0 / ScreenSize;
    vec2 left = clamp(uv - vec2(pixel.x, 0.0), pixel * 0.5, 1.0 - pixel * 0.5);
    vec2 right = clamp(uv + vec2(pixel.x, 0.0), pixel * 0.5, 1.0 - pixel * 0.5);
    vec2 down = clamp(uv - vec2(0.0, pixel.y), pixel * 0.5, 1.0 - pixel * 0.5);
    vec2 up = clamp(uv + vec2(0.0, pixel.y), pixel * 0.5, 1.0 - pixel * 0.5);
    vec3 l = neighborPosition(left, position);
    vec3 r = neighborPosition(right, position);
    vec3 d = neighborPosition(down, position);
    vec3 u = neighborPosition(up, position);
    vec3 dx = abs(l.z - position.z) < abs(r.z - position.z) ? position - l : r - position;
    vec3 dy = abs(d.z - position.z) < abs(u.z - position.z) ? position - d : u - position;
    vec3 normal = cross(dx, dy);
    if (dot(normal, normal) < 0.00000001) { return normalize(-position); }
    normal = normalize(normal);
    return dot(normal, -position) >= 0.0 ? normal : -normal;
}

// 沿表面到光源在本帧深度上检查遮挡；CPU 还会剔除被真实方块藏住的灯。
// 只能利用可见深度，离屏区域采取保守关闭，不能宣称拥有完整三维阴影图。
float visibility(vec3 surface, vec3 normal, vec3 light) {
    vec3 start = surface + normal * 0.08;
    for (int step = 1; step <= 8; ++step) {
        vec3 point = mix(start, light, float(step) / 9.0);
        vec4 clip = Projection * vec4(point, 1.0);
        if (clip.w <= 0.00001) { return 0.0; }
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) { return 0.0; }
        vec4 sampled = scenePosition(uv);
        if (sampled.w <= 0.5) { continue; }
        vec3 blocker = sampled.xyz;
        // 采样点明显处在可见表面后方才遮挡，容差不会随远距离无限增大。
        float bias = clamp(abs(point.z) * 0.001, 0.08, 0.25);
        if (blocker.z > point.z + bias) { return 0.0; }
    }
    return 1.0;
}

// 可选球形参与介质：在相机射线穿过光球的可见片段上积分。
// 强度默认 0，普通表面光不承担逐步雾光的额外成本。
float volumeIntegral(vec3 ray, float sceneDistance, vec3 center, float radius) {
    float along = dot(ray, center);
    float discriminant = along * along - dot(center, center) + radius * radius;
    if (discriminant <= 0.0) { return 0.0; }
    float root = sqrt(discriminant);
    float nearT = max(0.0, along - root);
    float farT = min(sceneDistance, along + root);
    if (farT <= nearT) { return 0.0; }
    float total = 0.0;
    for (int step = 0; step < 12; ++step) {
        float distanceAlong = mix(nearT, farT, (float(step) + 0.5) / 12.0);
        float radial = length(ray * distanceAlong - center) / radius;
        total += pow(max(0.0, 1.0 - radial * radial), 2.0);
    }
    return total * (farT - nearT) / (12.0 * max(radius, 0.01)) * 0.18;
}

// 使用亮部剩余空间温和叠加，多次爆炸不把画面简单相加成白屏。
vec3 composite(vec3 background, vec3 contribution) {
    vec3 response = 1.0 - exp(-max(contribution, vec3(0.0)));
    return background + response * max(vec3(0.0), vec3(1.0) - background) * 0.9;
}

void main() {
    vec4 positions[8] = vec4[8](LightPositionRadius0, LightPositionRadius1, LightPositionRadius2, LightPositionRadius3, LightPositionRadius4, LightPositionRadius5, LightPositionRadius6, LightPositionRadius7);
    vec4 colors[8] = vec4[8](LightColorIntensity0, LightColorIntensity1, LightColorIntensity2, LightColorIntensity3, LightColorIntensity4, LightColorIntensity5, LightColorIntensity6, LightColorIntensity7);
    vec4 shapes[8] = vec4[8](LightShape0, LightShape1, LightShape2, LightShape3, LightShape4, LightShape5, LightShape6, LightShape7);
    vec4 directions[8] = vec4[8](LightDirection0, LightDirection1, LightDirection2, LightDirection3, LightDirection4, LightDirection5, LightDirection6, LightDirection7);
    vec4 emissions[8] = vec4[8](LightEmission0, LightEmission1, LightEmission2, LightEmission3, LightEmission4, LightEmission5, LightEmission6, LightEmission7);
    vec4 background = texture(SceneColor, screenUV);
    vec4 sampled = scenePosition(screenUV);
    if (sampled.w < -0.5) {
        fragColor = OutputMode == 0 ? background : vec4(0.0);
        return;
    }
    bool hasSurface = sampled.w > 0.5;
    vec3 position = hasSurface ? sampled.xyz : viewPosition(screenUV, 0.5);
    vec3 ray = normalize(position);
    float sceneDistance = hasSurface ? length(position) : 2048.0;
    if (OutputMode == 2) {
        vec3 emission = vec3(0.0);
        for (int index = 0; index < 8; ++index) {
            if (index >= LightCount) { break; }
            emission += sourceEmission(ray, sceneDistance, positions[index], colors[index].rgb, emissions[index]);
        }
        // 独立叠加解除普通表面光的 0.9 曝光限制，显示核心可以达到纯白热亮度。
        fragColor = vec4(1.0 - exp(-clamp(emission, vec3(0.0), vec3(60000.0))), 0.0);
        return;
    }
    vec3 normal = hasSurface ? surfaceNormal(screenUV, position) : vec3(0.0, 0.0, 1.0);
    vec3 contribution = vec3(0.0);
    float bloomEnergy = 0.0;
    for (int index = 0; index < 8; ++index) {
        if (index >= LightCount) { break; }
        vec4 light = positions[index];
        vec4 color = colors[index];
        vec4 shape = shapes[index];
        if (light.w <= 0.0 || color.a <= 0.0) { continue; }
        float surface = 0.0;
        vec3 delta = light.xyz - position;
        float distanceToLight = length(delta);
        // 半球光：directions[index].w > 0.5 时只照亮光源前方半球。
        // 判据是"从光源指向表面"的方向与半球轴的夹角，背面片元直接不受光。
        float hemisphere = 1.0;
        if (directions[index].w > 0.5 && distanceToLight > 0.0001) {
            hemisphere = smoothstep(-0.12, 0.12, dot(-delta / distanceToLight, directions[index].xyz));
        }
        if (hasSurface && distanceToLight < light.w && shape.x > 0.0) {
            float radial = distanceToLight / light.w;
            float facing = distanceToLight < 0.0001 ? 1.0 : max(0.0, dot(normal, delta / distanceToLight));
            if (facing > 0.0) {
                surface = pow(max(0.0, 1.0 - radial * radial), 2.0)
                        * facing * shape.x * hemisphere * visibility(position, normal, light.xyz);
            }
        }
        float volume = shape.y > 0.0
                ? volumeIntegral(ray, sceneDistance, light.xyz, light.w) * shape.y : 0.0;
        vec3 added = color.rgb * color.a * (surface + volume)
                + sourceEmission(ray, sceneDistance, light, color.rgb, emissions[index]);
        contribution += added;
        if (shape.z > 0.5) { bloomEnergy += dot(added, vec3(0.2126, 0.7152, 0.0722)); }
    }
    // RGBA16F 的有限最大值为 65504；给 8 灯、2 倍画质强度与模糊舍入保留余量，禁止出现 Inf。
    contribution = clamp(contribution, vec3(0.0), vec3(60000.0));
    bloomEnergy = clamp(bloomEnergy, 0.0, 60000.0);
    fragColor = OutputMode == 1 ? vec4(contribution, bloomEnergy)
            : vec4(composite(background.rgb, contribution), background.a);
}
