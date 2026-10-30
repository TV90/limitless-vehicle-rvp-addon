#version 150
// 模式 0 提取灯光亮部，模式 1 半分辨率模糊，模式 2 合成原场景。
// 只读取自有纹理，绝不把场景颜色当作输出附件同时采样。
uniform sampler2D InputTexture;
uniform sampler2D SceneColor;
uniform sampler2D BloomTexture;
uniform int Mode;
uniform vec2 Direction;
in vec2 screenUV;
out vec4 fragColor;

void main() {
    if (Mode == 0) {
        vec4 light = texture(InputTexture, screenUV);
        float luminance = dot(light.rgb, vec3(0.2126, 0.7152, 0.0722));
        float eligible = clamp(light.a / max(luminance, 0.0001), 0.0, 1.0);
        // 0.08 亮部门槛、0.04 平滑过渡，弱边缘也能接上强喷口；不读取背景，所以不会提亮整片白天。
        float soft = clamp(luminance - 0.08 + 0.04, 0.0, 0.08);
        soft = soft * soft / 0.16;
        float threshold = max(luminance - 0.08, soft) / max(luminance, 0.0001);
        fragColor = vec4(light.rgb * eligible * threshold, 0.0);
    } else if (Mode == 1) {
        // 线性采样下的对称高斯核，横/纵各一轮。
        vec3 color = texture(InputTexture, screenUV).rgb * 0.227027;
        color += texture(InputTexture, screenUV + Direction * 1.384615).rgb * 0.316216;
        color += texture(InputTexture, screenUV - Direction * 1.384615).rgb * 0.316216;
        color += texture(InputTexture, screenUV + Direction * 3.230769).rgb * 0.070270;
        color += texture(InputTexture, screenUV - Direction * 3.230769).rgb * 0.070270;
        fragColor = vec4(color, 0.0);
    } else {
        vec4 background = texture(SceneColor, screenUV);
        vec3 contribution = texture(InputTexture, screenUV).rgb;
        vec3 response = 1.0 - exp(-max(contribution, vec3(0.0)));
        vec3 base = background.rgb + response * max(vec3(0.0), vec3(1.0) - background.rgb) * 0.9;
        // 光晕从旧 0.22 增强到 2.0；按共同峰值映射，强橙光扩散后仍保留橙色，不整圈烧成白色。
        vec3 glow = max(texture(BloomTexture, screenUV).rgb, vec3(0.0)) * 2.0;
        float peak = max(glow.r, max(glow.g, glow.b));
        vec3 coloredGlow = glow / max(peak, 0.0001) * (1.0 - exp(-peak));
        fragColor = vec4(base + coloredGlow * max(vec3(0.0), vec3(1.0) - base), background.a);
    }
}
