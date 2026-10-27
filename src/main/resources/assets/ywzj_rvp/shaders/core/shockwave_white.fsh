#version 150

// 观察空间数据；相机位于原点。
in vec3 vPos;
in vec3 vNormal;
in vec3 vCenter;
// 内外半径（格）、SLO 基准亮度、生命周期淡出乘数。
uniform vec2 uRadii;
uniform float uBrightness;
uniform float uLifeFade;
// 输出纯白发光颜色，最终可见强度通过加色混合叠加。
out vec4 fragColor;

void main() {
    if (uRadii.y <= 0.001 || uBrightness <= 0.0 || uLifeFade <= 0.0) discard;
    vec3 direction = normalize(vPos);
    bool inside = dot(vCenter, vCenter) < uRadii.y * uRadii.y;
    // 每条视线仅绘制一个表面：球外取近侧，球内取远侧，避免前后面重复增亮。
    float side = dot(normalize(vNormal), -direction);
    if ((!inside && side <= 0.0) || (inside && side >= 0.0)) discard;

    // 求视线穿过外球和内球的交点，得到壳层内路程。
    vec3 toCentre = -vCenter;
    float along = dot(toCentre, direction);
    float gap = dot(toCentre, toCentre) - uRadii.y * uRadii.y;
    float outerDisc = along * along - gap;
    if (outerDisc <= 0.0) discard;
    float root = sqrt(outerDisc);
    float outerNear = max(0.0, -along - root);
    float outerFar = -along + root;
    if (outerFar <= outerNear) discard;
    float chord = outerFar - outerNear;
    float innerDisc = along * along - (dot(toCentre, toCentre) - uRadii.x * uRadii.x);
    if (innerDisc > 0.0) {
        float innerRoot = sqrt(innerDisc);
        float innerNear = max(outerNear, -along - innerRoot);
        float innerFar = min(outerFar, -along + innerRoot);
        chord -= max(0.0, innerFar - innerNear);
    }
    float band = max(0.001, uRadii.y - uRadii.x);
    float thickness = clamp(chord / band, 0.0, 1.0);
    // 沿用 SLO 的边缘项、厚度项和3倍增益；不再给背景折射颜色叠一层淡蓝色。
    float facing = clamp(abs(along) / max(0.001, root + abs(along)), 0.0, 1.0);
    float limb = pow(1.0 - facing, 0.35);
    float alpha = uBrightness * uLifeFade * (0.55 + 0.45 * limb)
            * (0.35 + 0.65 * thickness) * 3.0;
    if (alpha <= 0.002) discard;
    fragColor = vec4(1.0, 1.0, 1.0, min(alpha, 1.0));
}
