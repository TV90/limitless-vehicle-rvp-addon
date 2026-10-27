#version 150

// 当前绘制目标的颜色和深度副本，均只读，避免边采样边写回同一纹理。
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
// 与当前世界画面相同的投影逆矩阵，用于把像素深度还原为视线距离。
uniform mat4 InverseProjection;
// 观察空间方向转回世界方向，使雾形不会随相机转动。
uniform mat3 ViewToWorld;
// 当前投影的横向、纵向缩放，用于估算球体在屏幕中的大小。
uniform vec2 ProjectionScale;
// 复制视口的实际像素尺寸。
uniform vec2 ScreenSize;
// 爆心的观察空间坐标，摄像机位于原点。
uniform vec3 CenterView;
// 内半径与外半径，单位格；两者间才有空气层。
uniform vec2 Radii;
// 柔和凝结雾强度、整体淡出、空气折射和轻微色散倍率。
uniform float Brightness;
uniform float LifeFade;
uniform float Refraction;
uniform float Dispersion;
// 标准动画年龄及按爆心区分的雾形偏移。
uniform float Age;
uniform vec3 NoiseOrigin;

in vec2 screenUV;
out vec4 fragColor;

// 视线方向已归一化，返回进入/离开球体的距离；负判别量表示没有相交。
vec2 sphereInterval(vec3 ray, float radius) {
    float along = dot(ray, CenterView);
    float discriminant = along * along - dot(CenterView, CenterView) + radius * radius;
    if (discriminant <= 0.0) return vec2(1.0, -1.0);
    float halfChord = sqrt(discriminant);
    return vec2(along - halfChord, along + halfChord);
}

// 使用原投影还原场景距离，天空视作无限远；不自行猜测近/远裁剪面。
float sceneDistance(vec2 uv) {
    float depth = texture(SceneDepth, uv).r;
    if (depth >= 0.999999) return 1.0e20;
    vec4 point = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return length(point.xyz / point.w);
}

// 低频且连续的空气扰动，只轻微打散密度，不生成新的粒子、纹理或闪烁噪点。
float billow(vec3 point) {
    vec3 p = ViewToWorld * point / max(Radii.y, 0.01) * 5.5 + NoiseOrigin;
    p.y += Age * 0.022;
    float broad = sin(p.x + sin(p.z * 0.73)) * sin(p.y * 1.23 + sin(p.x * 0.61));
    float detail = sin(p.z * 2.1 + p.y) * sin(p.x * 1.8 - p.y * 1.1);
    return 0.72 + 0.22 * broad + 0.06 * detail;
}

// 每段积分八次：累计穿过空气的有效厚度，以及向外压缩/向内恢复的背景偏移。
void integrate(vec3 ray, float begin, float end, inout float opticalDepth, inout vec2 bend) {
    if (end <= begin) return;
    float band = max(Radii.y - Radii.x, 0.001);
    float stepLength = (end - begin) / 8.0;
    for (int i = 0; i < 8; ++i) {
        vec3 point = ray * (begin + (float(i) + 0.5) * stepLength) - CenterView;
        float distanceToCenter = length(point);
        vec3 normal = point / max(distanceToCenter, 0.001);
        float q = clamp((distanceToCenter - Radii.x) / band, 0.0, 1.0);
        // 内、外缘都逐渐消失，中间保留宽厚空气层，避免硬边白圈。
        float density = smoothstep(0.0, 0.24, q) * (1.0 - smoothstep(0.66, 1.0, q));
        float variation = billow(point);
        opticalDepth += density * variation * stepLength / band;
        float front = exp(-pow((q - 0.76) / 0.19, 2.0));
        float recovery = exp(-pow((q - 0.30) / 0.23, 2.0));
        vec2 outward = normal.xy - ray.xy * (normal.z / min(ray.z, -0.001));
        bend += outward * (front - 0.38 * recovery) * variation * stepLength / band;
    }
}

// 防止折射把原本挡在空气层前面的物体拖到波面里。
vec2 safeRefractedUV(vec2 uv, vec2 offset, float entryDistance) {
    vec2 halfPixel = 0.5 / ScreenSize;
    vec2 sampleUV = clamp(uv + offset, halfPixel, 1.0 - halfPixel);
    return sceneDistance(sampleUV) + 0.05 < entryDistance ? uv : sampleUV;
}

void main() {
    if (LifeFade <= 0.0001 || Radii.y <= Radii.x) discard;
    vec4 farPoint = InverseProjection * vec4(screenUV * 2.0 - 1.0, 1.0, 1.0);
    vec3 ray = normalize(farPoint.xyz / farPoint.w);
    vec2 outer = sphereInterval(ray, Radii.y);
    float begin = max(outer.x, 0.0);
    float end = min(outer.y, sceneDistance(screenUV));
    if (end <= begin) discard;
    vec2 inner = sphereInterval(ray, Radii.x);
    float opticalDepth = 0.0;
    vec2 bend = vec2(0.0);
    if (inner.y > inner.x) {
        integrate(ray, begin, min(end, inner.x), opticalDepth, bend);
        integrate(ray, max(begin, inner.y), end, opticalDepth, bend);
    } else {
        integrate(ray, begin, end, opticalDepth, bend);
    }
    if (opticalDepth <= 0.00001) discard;

    float projectedRadius = Radii.y * ProjectionScale.y * ScreenSize.y * 0.5
            / max(-CenterView.z, Radii.y * 0.4);
    float displacementPixels = clamp(1.0 + projectedRadius * 0.030, 1.2, 16.0);
    bend /= 1.0 + length(bend) * 0.35;
    vec2 offset = bend * displacementPixels * Refraction * LifeFade / ScreenSize;
    vec2 redUV = safeRefractedUV(screenUV, offset * (1.0 + Dispersion), begin);
    vec2 greenUV = safeRefractedUV(screenUV, offset, begin);
    vec2 blueUV = safeRefractedUV(screenUV, offset * (1.0 - Dispersion), begin);
    vec4 background = texture(SceneColor, screenUV);
    vec3 refracted = vec3(texture(SceneColor, redUV).r, texture(SceneColor, greenUV).g,
            texture(SceneColor, blueUV).b);
    vec3 entryNormal = (ray * max(outer.x, 0.0) - CenterView) / Radii.y;
    float grazing = pow(clamp(1.0 - abs(dot(ray, entryNormal)), 0.0, 1.0), 0.65);
    // 斜着看穿过的空气更厚，但中央仍留一点柔雾，让它有体积而不是发光轮廓。
    float mist = clamp(Brightness * LifeFade * 3.6 * (0.26 + 0.74 * grazing)
            * (1.0 - exp(-opticalDepth * 0.8)), 0.0, 0.7);
    fragColor = vec4(mix(refracted, vec3(0.94, 0.965, 0.985), mist), background.a);
}
