#version 150

// 夜视仪合成 pass（2026-10-31 第四版）。
// 三个实测结论驱动了这版：
//   1) 亮度：pow 指数 < 1 是抬暗部（第一/二版整屏发白），> 1 是压暗（第三版整屏死黑）。
//      取中间值 1.22，配合 Gain 2.05，让星光下的地面落在"能看清轮廓但不刺眼"的区间。
//   2) 颜色：完全不要真实色彩。整体锁在绿色系内，只做色相微扰（偏黄绿 ↔ 偏青绿），
//      再叠亮度噪点——得到"荧光粉颗粒"的脏感，而不是灰噪点糊在纯绿上。
//   3) 结构：光源眩光、过曝烧白保持不变。
uniform sampler2D DiffuseSampler;
uniform sampler2D BloomSampler;
// 第二尺度光晕：宽核模糊的结果，只负责外围裙边。
// 单尺度做不到"中心亮 + 外围散"——归一化高斯把核拉宽，小光源的峰值就按 1/半径² 掉下去，
// 所以紧核保核心、宽核铺裙边，两路分别给强度。
uniform sampler2D BloomWideSampler;

uniform vec2 Resolution;
uniform vec3 PhosphorColor;
uniform float Gain;
uniform float BloomIntensity;
uniform float BloomWideIntensity;
uniform float Whiten;
uniform float ColorMix;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float l = luma(c);

    // 像增强：指数略微 > 1 压暗暗部，但不至于死黑
    float g = pow(clamp(l, 0.0, 1.0), 1.22) * Gain;
    // 黑电平：压掉极暗噪声底
    g = max(g - 0.015, 0.0) / 0.985;

    // 管壁暗角：边缘不提供增益
    vec2 q = texCoord - 0.5;
    float vig = 1.0 - 1.20 * dot(q, q);
    g *= clamp(vig, 0.0, 1.0);

    // 亮度噪点：亮部更明显，暗部保留细颗粒
    float n = hash(texCoord * Resolution + vec2(Time * 61.0, Time * 37.0));
    g += (n - 0.5) * 0.10 * clamp(g * 2.0 + 0.22, 0.0, 1.0);
    g = max(g, 0.0);

    // 绿系色相微扰：在两支相近的荧光绿之间按颗粒噪声摆动，
    // 宏观仍是纯绿，微观出现"荧光粉颗粒"的层次（比灰噪点更像真实像增强管）。
    vec3 greenWarm = vec3(0.36, 1.00, 0.34);   // 偏黄绿
    vec3 greenCool = vec3(0.10, 0.92, 0.58);   // 偏青绿
    float hueNoise = hash(texCoord * Resolution * 0.37 + vec2(Time * 11.0, Time * 5.0));
    vec3 phosphorTint = mix(greenWarm, greenCool, 0.22 + hueNoise * 0.56);
    vec3 phosphor = phosphorTint * g;

    // ColorMix 保留为可选：默认 0 = 纯绿系；调到 1 才掺原始色彩（默认不用）
    vec3 natural = c * (g * 1.25);
    vec3 col = mix(phosphor, natural, clamp(ColorMix, 0.0, 1.0));

    // 光源眩光：紧核保中心亮度，宽核铺外围裙边，两路分别叠加。
    // 必须只取亮度、不能拿原色：像增强管是单色的，上一版直接把光源原色（干扰机的红）
    // 加在荧光绿底上，Whiten 又要 over≥0.80 才开始去色，中段整片就成了用户看到的红斑。
    // 取峰值通道而不是 luma——饱和红光的 luma 只有 0.34 倍，会把本来该刺眼的灯砍掉三分之二。
    vec3 bloom = texture(BloomSampler, texCoord).rgb;
    vec3 bloomWide = texture(BloomWideSampler, texCoord).rgb;
    float bloomL = max(max(bloom.r, bloom.g), bloom.b);
    float bloomWideL = max(max(bloomWide.r, bloomWide.g), bloomWide.b);
    // 越接近核心越烧白，外围仍是荧光绿，看起来像管子里过曝而不是光源本身的颜色。
    vec3 flareTint = mix(vec3(0.55, 1.00, 0.62), vec3(1.0), clamp(bloomL * 1.30, 0.0, 1.0));
    col += flareTint * bloomL * BloomIntensity
            + vec3(0.48, 1.00, 0.58) * bloomWideL * BloomWideIntensity;

    // 过曝去色：亮部向纯白烧穿
    float over = max(max(col.r, col.g), col.b);
    col = mix(col, vec3(over), Whiten * smoothstep(0.80, 1.70, over));

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
