#version 150
// 另名多尺度光晕：0 亮部提取，1 高斯柔化，2 最终合成，3 缩小，4 加权回升。
// 只过滤 RVP 自有光贡献；SceneColor 只在最终合成读取，世界和 HUD 不会被当作发光源。
uniform sampler2D InputTexture;
uniform sampler2D SceneColor;
uniform sampler2D BloomTexture;
uniform int Mode;
// 当前层的高斯采样间距，单位为该层纹理坐标。
uniform vec2 Direction;
// 本层固定权重乘独立强度、更小层是否存在；后者为零时不叠加无用采样器。
uniform vec2 LayerWeights;
in vec2 screenUV;
out vec4 fragColor;

// 只在首层过滤亮部；Alpha 是允许进入光晕的能量，绝不是画面的透明度。
vec3 inputColor(vec2 uv, bool extractBright) {
    vec4 light = texture(InputTexture, uv);
    vec3 color = clamp(light.rgb, vec3(0.0), vec3(60000.0));
    if (!extractBright) { return color; }
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float eligible = clamp(light.a / max(luminance, 0.0001), 0.0, 1.0);
    // 保留既有 0.08 门槛和 0.04 过渡，避免把多尺度扩散变成整幅画面提亮。
    float soft = clamp(luminance - 0.08 + 0.04, 0.0, 0.08);
    soft = soft * soft / 0.16;
    float threshold = max(luminance - 0.08, soft) / max(luminance, 0.0001);
    return color * eligible * threshold;
}

// 九点连续过滤，权重总和为一；细长喷焰缩小时不靠稀疏大步长采样。
vec3 downsample(bool extractBright) {
    vec2 pixel = 1.0 / vec2(textureSize(InputTexture, 0));
    vec3 color = inputColor(screenUV, extractBright) * 0.25;
    color += inputColor(screenUV + vec2(pixel.x, 0.0), extractBright) * 0.125;
    color += inputColor(screenUV - vec2(pixel.x, 0.0), extractBright) * 0.125;
    color += inputColor(screenUV + vec2(0.0, pixel.y), extractBright) * 0.125;
    color += inputColor(screenUV - vec2(0.0, pixel.y), extractBright) * 0.125;
    color += inputColor(screenUV + pixel, extractBright) * 0.0625;
    color += inputColor(screenUV - pixel, extractBright) * 0.0625;
    color += inputColor(screenUV + vec2(pixel.x, -pixel.y), extractBright) * 0.0625;
    color += inputColor(screenUV + vec2(-pixel.x, pixel.y), extractBright) * 0.0625;
    return color;
}

// 回升时使用连续双线性过滤再做轻度九点柔化，防止最远层的低分辨率格子显形。
vec3 upsample() {
    vec2 pixel = 0.5 / vec2(textureSize(BloomTexture, 0));
    vec3 color = texture(BloomTexture, screenUV).rgb * 0.25;
    color += texture(BloomTexture, screenUV + vec2(pixel.x, 0.0)).rgb * 0.125;
    color += texture(BloomTexture, screenUV - vec2(pixel.x, 0.0)).rgb * 0.125;
    color += texture(BloomTexture, screenUV + vec2(0.0, pixel.y)).rgb * 0.125;
    color += texture(BloomTexture, screenUV - vec2(0.0, pixel.y)).rgb * 0.125;
    color += texture(BloomTexture, screenUV + pixel).rgb * 0.0625;
    color += texture(BloomTexture, screenUV - pixel).rgb * 0.0625;
    color += texture(BloomTexture, screenUV + vec2(pixel.x, -pixel.y)).rgb * 0.0625;
    color += texture(BloomTexture, screenUV + vec2(-pixel.x, pixel.y)).rgb * 0.0625;
    return color;
}

void main() {
    if (Mode == 0 || Mode == 3) {
        fragColor = vec4(downsample(Mode == 0), 0.0);
    } else if (Mode == 1) {
        // 对称高斯核权重约为一；每层先横后纵，不用增加亮度换取范围。
        vec3 color = texture(InputTexture, screenUV).rgb * 0.227027;
        color += texture(InputTexture, screenUV + Direction * 1.384615).rgb * 0.316216;
        color += texture(InputTexture, screenUV - Direction * 1.384615).rgb * 0.316216;
        color += texture(InputTexture, screenUV + Direction * 3.230769).rgb * 0.070270;
        color += texture(InputTexture, screenUV - Direction * 3.230769).rgb * 0.070270;
        fragColor = vec4(clamp(color, vec3(0.0), vec3(60000.0)), 0.0);
    } else if (Mode == 4) {
        vec3 color = texture(InputTexture, screenUV).rgb * LayerWeights.x;
        if (LayerWeights.y > 0.0) { color += upsample() * LayerWeights.y; }
        // 允许手动增强，但永不向 RGBA16F 写入超过其安全范围的数值。
        fragColor = vec4(clamp(color, vec3(0.0), vec3(60000.0)), 0.0);
    } else {
        vec4 background = texture(SceneColor, screenUV);
        vec3 contribution = texture(InputTexture, screenUV).rgb;
        vec3 response = 1.0 - exp(-max(contribution, vec3(0.0)));
        vec3 base = background.rgb + response * max(vec3(0.0), vec3(1.0) - background.rgb) * 0.9;
        // 沿用原有两倍光晕响应和共同峰值映射，橙色辉光不会因逐通道压缩整圈变白。
        vec3 glow = max(texture(BloomTexture, screenUV).rgb, vec3(0.0)) * 2.0;
        float peak = max(glow.r, max(glow.g, glow.b));
        vec3 coloredGlow = glow / max(peak, 0.0001) * (1.0 - exp(-peak));
        fragColor = vec4(base + coloredGlow * max(vec3(0.0), vec3(1.0) - base), background.a);
    }
}
