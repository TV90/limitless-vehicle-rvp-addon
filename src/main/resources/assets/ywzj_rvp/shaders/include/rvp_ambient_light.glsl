// 全局环境适应参数：开关、游戏光照权重、最亮环境保留比例、过渡曲线指数。
uniform vec4 AmbientSettings;

// 只读本帧RVP照明前的颜色；边缘限制在有效像素中心内，不能绕到屏幕另一侧。
float rvpAmbientLuminance(vec2 uv) {
    vec2 halfPixel = 0.5 / max(ScreenSize, vec2(1.0));
    vec3 color = clamp(texture(SceneColor, clamp(uv, halfPixel, vec2(1.0) - halfPixel)).rgb, 0.0, 1.0);
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

// 相邻五点平均，降低单个黑白纹理像素的干扰；放在光源循环外，同像素多灯共用。
float rvpAmbientSceneBrightness(vec2 uv) {
    if (AmbientSettings.x < 0.5) { return 0.0; }
    vec2 pixel = 2.0 / max(ScreenSize, vec2(1.0));
    return (rvpAmbientLuminance(uv)
            + rvpAmbientLuminance(uv + vec2(pixel.x, 0.0))
            + rvpAmbientLuminance(uv - vec2(pixel.x, 0.0))
            + rvpAmbientLuminance(uv + vec2(0.0, pixel.y))
            + rvpAmbientLuminance(uv - vec2(0.0, pixel.y))) * 0.2;
}

// 当地光照为负表示缺数据，只用原画面判断；关闭适应精确返回1，核心通道不调用此倍率。
float rvpAmbientMultiplier(float worldBrightness, float sceneBrightness) {
    if (AmbientSettings.x < 0.5) { return 1.0; }
    float ambient = worldBrightness < 0.0 ? sceneBrightness
            : mix(sceneBrightness, clamp(worldBrightness, 0.0, 1.0), clamp(AmbientSettings.y, 0.0, 1.0));
    float transition = pow(smoothstep(0.2, 0.8, ambient), max(AmbientSettings.w, 0.1));
    return mix(1.0, clamp(AmbientSettings.z, 0.0, 1.0), transition);
}
