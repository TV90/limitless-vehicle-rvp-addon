#version 150

uniform sampler2D DiffuseSampler; // 第一道取场景颜色，第二道取合成的传感器数据
uniform sampler2D ThermalSampler; // 经过世界深度测试的热源
uniform sampler2D SceneDepth;
uniform vec2 OutSize;
uniform int Palette;
uniform int ComposeScene;
uniform vec2 ViewScale;
uniform vec4 SkyLight; // xyz 为视空间太阳方向，w 为可见天空的天气透过率
in vec2 texCoord;
out vec4 fragColor;

float luma(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

float peak(vec3 color) {
    return max(max(color.r, color.g), color.b);
}

vec3 colorExcess(vec3 color) {
    return max(color - max(color.gbr, color.brg), vec3(0.0));
}

// 用屏幕中实际可见的太阳/月亮核心控制双尺度光晕。天体在镜头外或被深度遮挡时，
// 亮度采样不会凭空点亮天际；暗背景也能接到外围的柔和衰减。
float celestialGlare(vec2 heatUV) {
    if (SkyLight.w <= 0.0) return 0.0;
    bool sun = SkyLight.z < 0.0;
    vec3 direction = sun ? SkyLight.xyz : -SkyLight.xyz;
    if (direction.z >= -0.05) return 0.0;
    vec2 projected = -direction.xy / (direction.z * ViewScale);
    vec2 delta = (heatUV * 2.0 - 1.0 - projected) * vec2(OutSize.x / OutSize.y, 1.0);
    float radiusSq = dot(delta, delta);
    if (radiusSq >= 0.095 || any(greaterThan(abs(projected), vec2(0.99)))) return 0.0;
    vec2 sampleUV = 0.5 + 0.5 * projected;
    if (texture(SceneDepth, sampleUV).r < 0.999999) return 0.0;

    // 把天体中心换回 CRT 画面坐标，采样已经呈现在镜中的光盘。
    vec2 projectedScene = projected / (1.0 + 0.05 * dot(projected, projected));
    projectedScene = projected / (1.0 + 0.05 * dot(projectedScene, projectedScene));
    vec2 sceneUV = 0.5 + 0.5 * projectedScene;
    vec3 core = texture(DiffuseSampler, sceneUV).rgb;
    float neutral = 1.0 - smoothstep(0.05, 0.24, core.b - core.r);
    float strength = sun ? smoothstep(0.76, 0.98, luma(core)) : smoothstep(0.30, 0.62, luma(core));
    float center = exp2(-radiusSq / (sun ? 0.0038 : 0.0016));
    float skirt = exp2(-radiusSq / (sun ? 0.0200 : 0.0090));
    float fade = 1.0 - smoothstep(0.065, 0.095, radiusSq);
    return SkyLight.w * neutral * strength * fade * (0.70 * center + 0.31 * skirt);
}

// 参照夜视光源提取：只增强局部的高亮核心或高饱和自发光。较宽的邻域压制晴天背景
// 和受光的亮表面；天空另用天体方向与深度，只让太阳、月亮附近的实际亮像素进入光晕。
float lightSignal(vec3 color, float lightness, vec2 heatUV, bool sky) {
    float brightest = peak(color);
    float celestial = celestialGlare(heatUV);
    if (brightest < 0.32) return celestial;

    if (sky) {
        if (SkyLight.w <= 0.0) return celestial;
        vec3 ray = normalize(vec3((heatUV * 2.0 - 1.0) * ViewScale, -1.0));
        float alignment = dot(ray, SkyLight.xyz);
        float skyColor = 1.0 - smoothstep(0.045, 0.23, color.b - color.r);
        float sun = smoothstep(0.914, 0.969, alignment) * smoothstep(0.70, 0.965, lightness);
        float moon = smoothstep(0.955, 0.984, -alignment) * smoothstep(0.34, 0.80, lightness);
        return max(celestial, SkyLight.w * skyColor * max(sun, moon * 0.72));
    }

    vec2 radius = vec2(OutSize.y / OutSize.x, 1.0) * 0.045;
    vec3 east = texture(DiffuseSampler, texCoord + vec2(radius.x, 0)).rgb;
    vec3 west = texture(DiffuseSampler, texCoord - vec2(radius.x, 0)).rgb;
    vec3 north = texture(DiffuseSampler, texCoord + vec2(0, radius.y)).rgb;
    vec3 south = texture(DiffuseSampler, texCoord - vec2(0, radius.y)).rgb;
    float surroundingLuma = max(max(luma(east), luma(west)), max(luma(north), luma(south)));
    float contrast = smoothstep(0.05, 0.27, lightness - surroundingLuma);
    float whiteSource = smoothstep(0.52, 0.94, lightness) * contrast;
    // 彩色发光比较同色通道的局部富余。浅色墙面或蓝色天空再亮，也不会抵消红色玻片的峰值。
    vec3 neighborColor = max(max(colorExcess(east), colorExcess(west)),
                             max(colorExcess(north), colorExcess(south)));
    float colorSource = smoothstep(0.105, 0.30, peak(colorExcess(color) - neighborColor))
                      * smoothstep(0.32, 0.68, brightest);
    return max(celestial, pow(max(whiteSource, colorSource), 0.78));
}

// 热亮度沿用本体白热曲线。把原始场景亮度和局部眩光单独写入同一中间纹理，
// 让微光看清地形，也让最终调色阶段读取眩光时不用增加整屏拷贝。
vec4 composeThermal(vec2 heatUV, vec2 p) {
    vec3 sceneColor = texture(DiffuseSampler, texCoord).rgb;
    vec4 heat = texture(ThermalSampler, heatUV);
    bool sky = texture(SceneDepth, heatUV).r >= 0.999999;
    float scene = luma(sceneColor);
    float noise = fract(sin(dot(texCoord * 100.0, vec2(12.9898, 78.233))) * 43758.5453123);
    vec2 vignetteUV = texCoord * (1.0 - texCoord.yx);
    float vignette = pow(vignetteUV.x * vignetteUV.y * 15.0, 0.25);
    float gray = (0.10 + scene * 0.20 + (noise - 0.5) * 0.06) * vignette;
    float warmth = sceneColor.r - max(sceneColor.g, sceneColor.b);
    float environment = max(smoothstep(0.92, 1.0, scene),
                            smoothstep(0.50, 0.90, scene) * smoothstep(0.05, 0.40, warmth));
    if (environment > 0.01) {
        gray = mix(gray, 0.50 + environment * 0.50, min(1.0, environment + 0.40));
    }
    gray = mix(gray, pow(0.40 + 0.60 * luma(heat.rgb), 0.80), heat.a);
    // 映射微光亮度后储存，给暗部留出更多 8 位阶调。坡面、墙面原有细纹按像素保留。
    float microLight = min(1.0, pow(scene, 0.64) * 1.19) * (1.0 - 0.18 * dot(p, p));
    if (sky) microLight *= 0.80;
    return vec4(gray, microLight, lightSignal(sceneColor, scene, heatUV, sky), heat.a);
}

// 一个低清感光单元内取四点平均，以免细节缩小时出现明显锯齿。
vec2 cellSignal(vec2 cell, vec2 stepUV) {
    vec2 c = (cell + 0.5) * stepUV;
    vec2 d = 0.25 * stepUV;
    return 0.25 * (texture(DiffuseSampler, c + d).rb + texture(DiffuseSampler, c - d).rb
                 + texture(DiffuseSampler, c + vec2(d.x, -d.y)).rb
                 + texture(DiffuseSampler, c + vec2(-d.x, d.y)).rb);
}

vec2 softWhiteHot(float rows) {
    // 灰阶和强光一起降采样，按窗口宽高比重建，HUD 在后续阶段绘制。
    vec2 grid = max(vec2(1.0), round(OutSize * min(0.60, rows / OutSize.y)));
    vec2 pos = texCoord * grid - 0.5;
    vec2 i = floor(pos), f = fract(pos), stepUV = 1.0 / grid;
    return mix(mix(cellSignal(i, stepUV), cellSignal(i + vec2(1, 0), stepUV), f.x),
               mix(cellSignal(i + vec2(0, 1), stepUV), cellSignal(i + vec2(1, 1), stepUV), f.x), f.y);
}

// 原白热合成带有单像素噪声。仅在亮度相近的相邻像素间降噪，避免铁红色带放大彩色噪点，
// 同时保住轮廓和表面热纹。输出仍取自同一张已做深度遮挡的白热图。
float ironGray(float center) {
    // 本体的颗粒叠在冷背景上。热源保留原始表面纹理，也省去邻域采样。
    if (center >= 0.45) return center;
    vec4 nearGray = vec4(textureOffset(DiffuseSampler, texCoord, ivec2(-1, -1)).r,
                         textureOffset(DiffuseSampler, texCoord, ivec2(-1, 1)).r,
                         textureOffset(DiffuseSampler, texCoord, ivec2(1, -1)).r,
                         textureOffset(DiffuseSampler, texCoord, ivec2(1, 1)).r);
    vec4 farGray = vec4(textureOffset(DiffuseSampler, texCoord, ivec2(-3, 0)).r,
                        textureOffset(DiffuseSampler, texCoord, ivec2(3, 0)).r,
                        textureOffset(DiffuseSampler, texCoord, ivec2(0, -3)).r,
                        textureOffset(DiffuseSampler, texCoord, ivec2(0, 3)).r);
    vec4 nearWeight = 1.0 - smoothstep(vec4(0.045), vec4(0.110), abs(nearGray - center));
    vec4 farWeight = 1.0 - smoothstep(vec4(0.045), vec4(0.110), abs(farGray - center));
    float filtered = (center + dot(nearGray, nearWeight) + dot(farGray, farWeight))
                   / (1.0 + dot(nearWeight + farWeight, vec4(1.0)));
    return mix(filtered, center, smoothstep(0.30, 0.45, center));
}

// 本体把环境亮度压在灰阶低段；在这段多留几个色阶，地表明暗也能经过紫红和橙色。
vec3 ironbow(float g) {
    const vec3 ink = vec3(0.035, 0.017, 0.10);
    const vec3 violet = vec3(0.29, 0.059, 0.58);
    const vec3 magenta = vec3(0.65, 0.095, 0.60);
    const vec3 red = vec3(0.91, 0.22, 0.33);
    const vec3 orange = vec3(0.99, 0.46, 0.045);
    const vec3 gold = vec3(1.0, 0.80, 0.075);
    const vec3 pale = vec3(1.0, 0.96, 0.64);
    if (g < 0.125) return mix(ink, violet, clamp((g - 0.035) / 0.090, 0.0, 1.0));
    if (g < 0.185) return mix(violet, magenta, (g - 0.125) / 0.060);
    if (g < 0.265) return mix(magenta, red, (g - 0.185) / 0.080);
    if (g < 0.530) return mix(red, orange, (g - 0.265) / 0.265);
    if (g < 0.670) return mix(orange, gold, (g - 0.530) / 0.140);
    return mix(gold, pale, clamp((g - 0.670) / 0.140, 0.0, 1.0));
}

void main() {
    // 保持与本体热缓冲采样相同的镜面变形，边框在黑热反相后仍为黑色。
    vec2 p = (texCoord - 0.5) * 2.0;
    vec2 heatUV = 0.5 + 0.5 * p * (1.0 + 0.05 * dot(p, p));
    if (any(lessThan(heatUV, vec2(0))) || any(greaterThan(heatUV, vec2(1)))) {
        fragColor = vec4(0, 0, 0, ComposeScene == 1 ? 0.0 : 1.0);
        return;
    }
    if (ComposeScene == 1) {
        fragColor = composeThermal(heatUV, p);
        return;
    }

    vec4 sensor = texture(DiffuseSampler, texCoord);
    vec3 color = vec3(sensor.r);
    vec3 haloTint = vec3(1.0);
    float glare = sensor.b;
    bool lowRes = Palette == 1 || Palette == 6 || Palette == 7;
    if (lowRes) {
        vec2 soft = softWhiteHot(Palette == 7 ? 60.0 : 240.0);
        float g = soft.x;
        color = vec3(g);
        glare = soft.y;
        if (Palette == 6) {
            // 暗酒红环境、暖红中间调和略浅的珊瑚红热源，均沿低清采样后的灰度连续过渡。
            const vec3 brick = vec3(0.63, 0.294, 0.30);
            color = mix(vec3(0.22, 0.095, 0.12), brick, clamp(g / 0.25, 0.0, 1.0));
            color = mix(color, vec3(0.965, 0.51, 0.495), clamp((g - 0.25) / 0.70, 0.0, 1.0));
            haloTint = vec3(1.0, 0.51, 0.48);
        }
    } else if (Palette == 4) {
        color = vec3(1.0 - sensor.r);
    } else if (Palette == 5) {
        color = ironbow(ironGray(sensor.r));
        haloTint = vec3(1.0, 0.70, 0.15);
    } else if (Palette == 2 || Palette == 3) {
        float heat = clamp((sensor.r - 0.40) / 0.51, 0.0, 1.0);
        vec3 cold;
        vec3 hot;
        if (Palette == 2) {
            cold = mix(vec3(0.010, 0.028, 0.014), vec3(0.16, 0.86, 0.29), sensor.g);
            hot = mix(vec3(0.82, 0.79, 0.12), vec3(1.0, 1.0, 0.66), heat);
            haloTint = vec3(0.51, 1.0, 0.65);
        } else {
            cold = mix(vec3(0.018, 0.074, 0.10), vec3(0.37, 0.82, 0.94), sensor.g);
            hot = mix(vec3(0.48, 0.76, 0.86), vec3(0.86, 0.99, 1.0), heat);
            haloTint = vec3(0.43, 0.87, 1.0);
        }
        color = mix(cold, hot, sensor.a);
    }

    // 夜视同款的亮芯与外围晕。低清档沿用低通后的源，避免在模糊画面上叠回清晰的边缘。
    float halo = glare;
    if (!lowRes) {
        vec2 px = max(1.0, OutSize.y / 900.0) / OutSize;
        float tight = texture(DiffuseSampler, texCoord + vec2( 2,  2) * px).b
                    + texture(DiffuseSampler, texCoord + vec2(-2,  2) * px).b
                    + texture(DiffuseSampler, texCoord + vec2( 2, -2) * px).b
                    + texture(DiffuseSampler, texCoord + vec2(-2, -2) * px).b;
        float wide = texture(DiffuseSampler, texCoord + vec2( 7,  3) * px).b
                   + texture(DiffuseSampler, texCoord + vec2(-3,  7) * px).b
                   + texture(DiffuseSampler, texCoord + vec2( 3, -7) * px).b
                   + texture(DiffuseSampler, texCoord + vec2(-7, -3) * px).b;
        halo = tight * 0.095 + wide * 0.035;
    }
    vec3 whiteCore = mix(haloTint, vec3(1.0), smoothstep(0.12, 0.75, glare));
    color = clamp(color + glare * 0.90 * whiteCore + halo * 0.38 * haloTint, 0.0, 1.0);
    fragColor = vec4(color, 1.0);
}
