#version 150

// 夜视仪光源提取 pass（2026-10-31）。
// 只保留"会被夜视仪放大成眩光"的像素，输出到 nvg_light 供两遍高斯模糊。
// 判据必须同时覆盖三类光源，否则暗处点光源会漏：
//   1) 绝对亮：原版火把/岩浆、爆炸白闪、枪口火花核心 —— luma 直接够高
//   2) 局部亮峰：亮度明显高于 8 邻域均值 —— 暗场景里的小光点
//   3) 高饱和彩光：光电干扰机那类暗处红光（r≈0.3、g/b≈0）绝对亮度不够，
//      但饱和度极高且是个局部峰
uniform sampler2D DiffuseSampler;
uniform vec2 Resolution;

in vec2 texCoord;
out vec4 fragColor;

const float L_ABS_LO = 0.62;
const float L_ABS_HI = 0.95;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 px = 2.0 / max(Resolution, vec2(1.0));
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float l = luma(c);

    float sum = 0.0;
    sum += luma(texture(DiffuseSampler, texCoord + vec2( px.x,   0.0)).rgb);
    sum += luma(texture(DiffuseSampler, texCoord + vec2(-px.x,   0.0)).rgb);
    sum += luma(texture(DiffuseSampler, texCoord + vec2(  0.0,  px.y)).rgb);
    sum += luma(texture(DiffuseSampler, texCoord + vec2(  0.0, -px.y)).rgb);
    sum += luma(texture(DiffuseSampler, texCoord + vec2( px.x,  px.y)).rgb);
    sum += luma(texture(DiffuseSampler, texCoord + vec2(-px.x,  px.y)).rgb);
    sum += luma(texture(DiffuseSampler, texCoord + vec2( px.x, -px.y)).rgb);
    sum += luma(texture(DiffuseSampler, texCoord + vec2(-px.x, -px.y)).rgb);
    float avg = sum * 0.125;

    float delta = l - avg;
    float peak = smoothstep(0.02, 0.10, delta);

    float absPeak = smoothstep(L_ABS_LO, L_ABS_HI, l);
    float relPeak = smoothstep(0.04, 0.16, delta) * smoothstep(0.05, 0.18, l);

    float maxC = max(max(c.r, c.g), c.b);
    float sat = maxC - min(min(c.r, c.g), c.b);
    float chromaPeak = smoothstep(0.16, 0.42, sat) * smoothstep(0.06, 0.24, maxC) * peak;

    float w = max(absPeak, max(relPeak, chromaPeak));

    fragColor = vec4(c * w, 1.0);
}
