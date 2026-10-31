#version 150

// 夜视仪光源提取 pass（2026-10-31 第二版：把"边缘判据"换成"成团判据"）。
// 只保留"会被夜视仪放大成眩光"的像素，输出到 nvg_light 供两遍高斯模糊。
//
// 上一版的判据是"比 8 邻域平均更亮"，那本质上是边缘检测：夜里车壳比天空/背景亮 0.1～0.15，
// 于是整车轮廓那一圈全部命中，模糊后按亮度加回来就成了用户看到的"载具描边"。
// 真光源是一**团**（中心亮、邻域也亮），描边是一条（必然有一侧邻域是暗背景），
// 所以改用 8 邻域的最小亮度 minL 来区分两者：所有掩码都要先过成团这一关。
// 亮度响应整体抬高（火把、干扰机红光更亮）：绝对阈值下调 + 掩码保留亮度梯度。
// 输出的掩码不再"够亮就封顶"，外围衰减交给合成 pass 的两尺度光晕（紧核保核心、宽核铺裙边）。
uniform sampler2D DiffuseSampler;
uniform vec2 Resolution;

in vec2 texCoord;
out vec4 fragColor;

// 绝对亮阈值：火把/岩浆/爆炸白闪/枪口火花核心。
// 这里刻意用**线性斜坡**而不是 smoothstep 的"够亮就封顶 1.0"：掩码本身要带走亮度梯度，
// 模糊加回来才有从核心到裙边的连续衰减。平顶掩码 + 有限模糊核只会得到一块硬边白盘。
const float L_ABS_LO = 0.45;
const float L_ABS_HI = 1.00;
// 成团判据：邻域里出现暗背景就判定为"一条边"，压到 EDGE_KEEP 而不是全砍，
// 保住贴在暗处的小光源边缘不至于整个消失。
const float BLOB_LO = 0.05;
const float BLOB_HI = 0.18;
const float EDGE_KEEP = 0.25;
// 掩码伽马：< 1 抬升低值段，让光源外围的暗裙边也参与成像 = 更发散。
const float SKIRT_GAMMA = 0.70;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// 线性斜坡：从 lo 起判、到 hi 才满值，中间保持与亮度成正比。
float ramp(float value, float lo, float hi) {
    return clamp((value - lo) / max(hi - lo, 1.0E-4), 0.0, 1.0);
}

void main() {
    vec2 px = 2.0 / max(Resolution, vec2(1.0));
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float l = luma(c);

    float n0 = luma(texture(DiffuseSampler, texCoord + vec2( px.x,   0.0)).rgb);
    float n1 = luma(texture(DiffuseSampler, texCoord + vec2(-px.x,   0.0)).rgb);
    float n2 = luma(texture(DiffuseSampler, texCoord + vec2(  0.0,  px.y)).rgb);
    float n3 = luma(texture(DiffuseSampler, texCoord + vec2(  0.0, -px.y)).rgb);
    float n4 = luma(texture(DiffuseSampler, texCoord + vec2( px.x,  px.y)).rgb);
    float n5 = luma(texture(DiffuseSampler, texCoord + vec2(-px.x,  px.y)).rgb);
    float n6 = luma(texture(DiffuseSampler, texCoord + vec2( px.x, -px.y)).rgb);
    float n7 = luma(texture(DiffuseSampler, texCoord + vec2(-px.x, -px.y)).rgb);
    float avg = (n0 + n1 + n2 + n3 + n4 + n5 + n6 + n7) * 0.125;
    float minL = min(min(min(n0, n1), min(n2, n3)), min(min(n4, n5), min(n6, n7)));
    float delta = l - avg;
    float blob = smoothstep(BLOB_LO, BLOB_HI, minL);

    // 1) 绝对亮：线性斜坡，越亮掩码越大；贴一侧暗背景的亮边按 EDGE_KEEP 打折。
    float absPeak = ramp(l, L_ABS_LO, L_ABS_HI) * mix(EDGE_KEEP, 1.0, blob);
    // 2) 暗处小光点：局部峰 + 绝对底（0.22 起判、0.52 才满值），且必须成团；
    //    只有"比邻域亮"不再算光源，所以载具轮廓不会再被描边。
    float relPeak = smoothstep(0.05, 0.14, delta) * ramp(l, 0.22, 0.52) * blob;
    // 3) 高饱和彩光：光电干扰机那类暗处红光（sat 高但绝对亮度不够）。
    //    彩度与亮度都走斜坡，红光从玻片核心到外围是一圈连续衰减的红晕而不是红盘。
    float maxC = max(max(c.r, c.g), c.b);
    float sat = maxC - min(min(c.r, c.g), c.b);
    float chromaPeak = ramp(sat, 0.14, 0.44) * ramp(maxC, 0.18, 0.55) * mix(0.35, 1.0, blob);

    float w = clamp(max(absPeak, max(relPeak, chromaPeak)), 0.0, 1.0);
    // 抬裙边：0.7 次幂把 0.2～0.5 这段掩码顶起来，光源外围的低值贡献也能被模糊成发散的光晕。
    w = pow(w, SKIRT_GAMMA);

    fragColor = vec4(c * w, 1.0);
}
