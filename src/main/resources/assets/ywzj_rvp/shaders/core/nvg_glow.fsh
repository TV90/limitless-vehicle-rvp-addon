#version 150

// 夜视仪下的光电干扰机光斑（2026-10-31）。
// 用"白热核心 + 红色软晕"两层叠加代替原来的玻片自发光：夜视像增强管看强红外/红光
// 就是这个样子——中心烧成白色，外围一圈发散的暗红晕，边缘再平滑衰减到无。
// 因为走加性混合，两台干扰机靠得很近时两层光斑自然累加，重叠处更亮、中心更多烧白，
// 不需要额外的"叠加预案"分支。
uniform vec4 CoreColor;
uniform vec4 EdgeColor;
uniform float Intensity;
uniform float HaloPower;

in vec2 quadUV;
out vec4 fragColor;

void main() {
    // quadUV 在 [0,1]，换成以中心为原点的 [-1,1] 径向坐标（四边形本身是椭圆，故得到椭圆光斑）
    vec2 d = (quadUV - 0.5) * 2.0;
    float r = length(d);

    // 核心：半径 0.30 内近乎满亮，边缘 0.06 过渡，形成"烧穿"的实心白点
    float core = 1.0 - smoothstep(0.24, 0.34, r);
    // 软晕：从中心一路衰减到边缘，幂次控制发散程度
    float halo = pow(max(0.0, 1.0 - r), HaloPower);

    // 强度：核心给足（>1 之后在加性混合里会直接烧白），晕只做扩散
    float amount = core * (1.0 + Intensity) + halo * Intensity;

    // 颜色不取纯色：核心偏白热，向外过渡到边缘红，中间连续混合
    vec3 color = mix(EdgeColor.rgb, CoreColor.rgb, clamp(core * 1.35, 0.0, 1.0));

    fragColor = vec4(color * amount, 1.0);
}
