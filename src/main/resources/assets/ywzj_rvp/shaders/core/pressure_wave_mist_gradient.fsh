#version 150

// 当前画面的只读颜色副本，用于空气折射，避免边读边写同一张纹理。
uniform sampler2D SceneColor;
// 当前画面的只读深度副本，用于裁掉被前景挡住的波面。
uniform sampler2D SceneDepth;
// 当前世界投影的逆矩阵，把像素和深度还原成真实观察空间坐标。
uniform mat4 InverseProjection;
// 观察空间方向转回世界方向，使空气纹理固定在爆心，不随相机转动。
uniform mat3 ViewToWorld;
// 当前投影的横向、纵向缩放，用于估算波面在屏幕中的大小。
uniform vec2 ProjectionScale;
// 复制视口的实际像素尺寸。
uniform vec2 ScreenSize;
// 爆心的观察空间坐标，摄像机位于原点。
uniform vec3 CenterView;
// 空气层的内、外半径，单位格。
uniform vec2 Radii;
// 凝结雾亮度；透明压力层传入 0，只扭曲背景。
uniform float Brightness;
// 同时作用于凝结雾和折射的生命周期淡出。
uniform float LifeFade;
// 背景推挤与回弹的强度。
uniform float Refraction;
// 折射边缘的轻微颜色分离强度。
uniform float Dispersion;
// 按统一时钟换算的动画年龄，用于缓慢改变空气纹理。
uniform float Age;
// 按爆心区分的纹理偏移，避免不同爆炸使用完全相同的雾形。
uniform vec3 NoiseOrigin;

// 顶点程序提供的屏幕采样坐标。
in vec2 screenUV;
// 已包含原背景的最终颜色。
out vec4 fragColor;

// 返回视线进入、离开球体的距离；负判别量表示没有相交。
vec2 sphereInterval(vec3 ray, float radius) {
    float along = dot(ray, CenterView);
    float discriminant = along * along - dot(CenterView, CenterView) + radius * radius;
    if (discriminant <= 0.0) return vec2(1.0, -1.0);
    float halfChord = sqrt(discriminant);
    return vec2(along - halfChord, along + halfChord);
}

// 用实际投影还原场景距离；天空视作无限远，不猜测近、远裁剪面。
float sceneDistance(vec2 uv) {
    float depth = texture(SceneDepth, uv).r;
    if (depth >= 0.999999) return 1.0e20;
    vec4 point = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return length(point.xyz / point.w);
}

// 连续、低频的空气扰动；只轻微打散密度，保留连贯而宽厚的波面。
float billow(vec3 point) {
    vec3 p = ViewToWorld * point / max(Radii.y, 0.01) * 5.5 + NoiseOrigin;
    p.y += Age * 0.022;
    float broad = sin(p.x + sin(p.z * 0.73)) * sin(p.y * 1.23 + sin(p.x * 0.61));
    float detail = sin(p.z * 2.1 + p.y) * sin(p.x * 1.8 - p.y * 1.1);
    return 0.72 + 0.22 * broad + 0.06 * detail;
}

// 只沿朝向玩家的空气层采样八次，分别累计空气和白雾厚度，以及外推和反向恢复。
void integrate(vec3 ray, float begin, float end, inout float opticalDepth, inout float mistDepth, inout vec2 bend) {
    if (end <= begin) return;
    float band = max(Radii.y - Radii.x, 0.001);
    float stepLength = (end - begin) / 8.0;
    for (int i = 0; i < 8; ++i) {
        vec3 point = ray * (begin + (float(i) + 0.5) * stepLength) - CenterView;
        float distanceToCenter = length(point);
        vec3 normal = point / max(distanceToCenter, 0.001);
        float q = clamp((distanceToCenter - Radii.x) / band, 0.0, 1.0);
        // 两侧都平滑消失，避免把宽厚空气层画成硬边圆圈。
        float density = smoothstep(0.0, 0.24, q) * (1.0 - smoothstep(0.66, 1.0, q));
        float variation = billow(point);
        opticalDepth += density * variation * stepLength / band;
        // 白雾在空气层内侧更浓，向外递减；独立累计，不削弱外侧压力折射。
        float mistDensity = density * 2.2 * pow(max(1.0 - q, 0.0), 1.6);
        mistDepth += mistDensity * variation * stepLength / band;
        float front = exp(-pow((q - 0.76) / 0.19, 2.0));
        float recovery = exp(-pow((q - 0.30) / 0.23, 2.0));
        vec2 outward = normal.xy - ray.xy * (normal.z / min(ray.z, -0.001));
        bend += outward * (front - 0.38 * recovery) * variation * stepLength / band;
    }
}

// 折射后的取样也检查深度，避免把波面前方的物体拖进空气层。
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
    // 到视线离爆心最近的位置就停止，只保留正面；背面不再叠成一颗发白的球。
    float end = min(min(outer.y, dot(ray, CenterView)), sceneDistance(screenUV));
    if (end <= begin) discard;
    vec2 inner = sphereInterval(ray, Radii.x);
    float opticalDepth = 0.0;
    float mistDepth = 0.0;
    vec2 bend = vec2(0.0);
    if (inner.y > inner.x) {
        integrate(ray, begin, min(end, inner.x), opticalDepth, mistDepth, bend);
    } else {
        integrate(ray, begin, end, opticalDepth, mistDepth, bend);
    }
    if (opticalDepth <= 0.00001) discard;

    // 由真实波面与视线的夹角决定通透程度，偏离屏幕中心的爆炸也能正确显示。
    // 保留负的入射距离，使玩家进入空气层后仍平滑过渡，不出现包住镜头的亮球。
    vec3 entryNormal = (ray * outer.x - CenterView) / Radii.y;
    float facing = clamp(-dot(ray, entryNormal), 0.0, 1.0);
    float faceRadius = sqrt(max(1.0 - facing * facing, 0.0));
    float centerVisibility = smoothstep(0.12, 0.62, faceRadius);
    float grazing = pow(1.0 - facing, 0.7);

    float projectedRadius = Radii.y * ProjectionScale.y * ScreenSize.y * 0.5
            / max(-CenterView.z, Radii.y * 0.4);
    float displacementPixels = clamp(1.0 + projectedRadius * 0.030, 1.2, 16.0);
    bend /= 1.0 + length(bend) * 0.35;
    vec2 offset = bend * displacementPixels * Refraction * LifeFade * centerVisibility / ScreenSize;
    vec2 redUV = safeRefractedUV(screenUV, offset * (1.0 + Dispersion), begin);
    vec2 greenUV = safeRefractedUV(screenUV, offset, begin);
    vec2 blueUV = safeRefractedUV(screenUV, offset * (1.0 - Dispersion), begin);
    vec4 background = texture(SceneColor, screenUV);
    vec3 refracted = vec3(texture(SceneColor, redUV).r, texture(SceneColor, greenUV).g,
            texture(SceneColor, blueUV).b);
    // 可见波带由内向外逐渐透明，外层以折射表现薄空气，内层保留较厚的凝结雾。
    float innerRatio = Radii.x / Radii.y;
    float mistProgress = clamp((faceRadius - innerRatio) / max(1.0 - innerRatio, 0.001), 0.0, 1.0);
    float outwardMist = pow(1.0 - mistProgress, 1.35);
    float mistFacing = mix(0.35, 1.0, grazing);
    // 雾量的中心过渡与折射分开，保留正面通透，同时避免外沿因斜视而更白。
    float mist = clamp(Brightness * LifeFade * 4.6 * mistFacing * pow(centerVisibility, 1.5)
            * outwardMist * (1.0 - exp(-mistDepth * 0.8)), 0.0, 0.7);
    fragColor = vec4(mix(refracted, vec3(0.94, 0.965, 0.985), mist), background.a);
}
