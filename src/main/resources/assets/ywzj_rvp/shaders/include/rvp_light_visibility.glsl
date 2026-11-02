// 三类光源与烟雾共用光路入口；正式使用下方三维几何，屏幕深度算法只保留作历史回归。
uniform sampler2D OcclusionDepth;
// 是否持有本帧完整分层深度；诊断未绑定时退回逐像素检查，不读取旧纹理。
uniform int OcclusionReady;
// 分层深度的最高层；每层完整汇总四个子格，不跳过奇数窗口的边缘像素。
uniform int OcclusionMaxLevel;
// 每条光路的硬查询预算；耗尽返回单独状态，不能假装发现了遮挡。
uniform int OcclusionMaxSteps;
// 仅生成下一层时使用，指定读取的上一层编号。
uniform int OcclusionReduceLevel;
// 每个片元独立的统计：命中次数、离屏次数、预算耗尽次数、最大查询数。
vec4 rvpOcclusionStats = vec4(0.0);

// 深度误差与物体厚度分开；二者都有上限，绝不将表面后方无限视为实心。
float rvpOcclusionBias(float depth) {
    return clamp(depth * 0.000002, 0.001, 0.02);
}

float rvpOcclusionThickness(float depth) {
    // 取横纵较小的像素覆盖尺寸，超宽/奇数诊断窗口不能因纵向像素大而把细杆影子加厚数倍。
    float pixelDensity = max(ScreenSize.x * abs(Projection[0][0]), ScreenSize.y * abs(Projection[1][1]));
    float pixelWorld = 2.0 * depth / max(pixelDensity, 1.0);
    return clamp(pixelWorld * 0.08, 0.005, 0.05);
}

// 查询一个像素或完整分层格的最近/最远表面距离；空格用 min>max 表示。
vec2 rvpOcclusionRange(ivec2 cell, int level) {
    if (OcclusionReady != 0) { return texelFetch(OcclusionDepth, cell, level).rg; }
    vec4 surface = scenePosition((vec2(cell) + 0.5) / ScreenSize);
    return surface.w > 0.5 ? vec2(-surface.z) : vec2(1e20, -1e20);
}

// 返回可见性、原因、查询次数；原因 0=完整无遮挡，1=命中，2=离屏/不可投影，3=预算耗尽。
vec3 rvpTraceLightPath(vec3 start, vec3 light) {
    vec4 a = Projection * vec4(start, 1.0);
    vec4 b = Projection * vec4(light, 1.0);
    // 先过滤相机后方和近裁剪面外的端点，避免除零；沿用现有离屏保守抑制策略。
    float nearA = NearEmptyDepth > 0.5 ? a.z + a.w : a.w - a.z;
    float nearB = NearEmptyDepth > 0.5 ? b.z + b.w : b.w - b.z;
    if (a.w <= 0.00001 || b.w <= 0.00001 || nearA < 0.0 || nearB < 0.0) { return vec3(0.0, 2.0, 0.0); }
    vec2 p = (a.xy / a.w * 0.5 + 0.5) * ScreenSize;
    vec2 end = (b.xy / b.w * 0.5 + 0.5) * ScreenSize;
    if (any(lessThan(end, vec2(0.0))) || any(greaterThanEqual(end, ScreenSize))) { return vec3(0.0, 2.0, 0.0); }
    vec2 delta = end - p;
    vec2 direction = sign(delta);
    // Q/k 恢复透视正确的光路位置，不能把屏幕线性比例当作世界线性比例。
    vec3 qa = start / a.w;
    vec3 qb = light / b.w;
    float ka = 1.0 / a.w;
    float kb = 1.0 / b.w;
    float entry = 0.0;
    // 原始受光像素在画面内，法线偏移可能把起点轻微推出边缘；只裁掉越界片段，不误切整条光路。
    if (p.x < 0.0) { entry = max(entry, -p.x / delta.x); }
    if (p.y < 0.0) { entry = max(entry, -p.y / delta.y); }
    if (p.x >= ScreenSize.x) { entry = max(entry, (ScreenSize.x - p.x) / delta.x); }
    if (p.y >= ScreenSize.y) { entry = max(entry, (ScreenSize.y - p.y) / delta.y); }
    entry = clamp(entry, 0.0, 1.0);
    int level = OcclusionReady != 0 ? OcclusionMaxLevel : 0;
    int budget = OcclusionMaxSteps > 0 ? min(OcclusionMaxSteps, 1024) : 512;
    for (int query = 0; query < 1024; ++query) {
        if (query >= budget) { return vec3(1.0, 3.0, float(query)); }
        float scale = exp2(float(level));
        // 极小方向偏移只用于选择跨边界后的像素格，不跨过完整像素或遮挡条。
        ivec2 cell = ivec2(floor((p + delta * entry + direction * 0.0001) / scale));
        vec2 edge = (vec2(cell) + max(direction, vec2(0.0))) * scale;
        vec2 crossing = vec2(abs(delta.x) < 0.000001 ? 1e20 : (edge.x - p.x) / delta.x,
                             abs(delta.y) < 0.000001 ? 1e20 : (edge.y - p.y) / delta.y);
        float exit = clamp(min(crossing.x, crossing.y), entry, 1.0);
        float z0 = -(mix(qa, qb, entry) / mix(ka, kb, entry)).z;
        float z1 = -(mix(qa, qb, exit) / mix(ka, kb, exit)).z;
        vec2 range = rvpOcclusionRange(cell, level);
        bool overlap = range.x <= range.y && max(z0, z1) >= range.x + rvpOcclusionBias(range.x)
                && min(z0, z1) <= range.y + rvpOcclusionThickness(range.y);
        if (overlap && level > 0) { --level; continue; }
        if (overlap) { return vec3(0.0, 1.0, float(query + 1)); }
        if (exit >= 1.0) { return vec3(1.0, 0.0, float(query + 1)); }
        entry = max(exit, entry + 0.0000001);
        if (OcclusionReady != 0) { level = min(level + 1, OcclusionMaxLevel); }
    }
    return vec3(1.0, 3.0, 1024.0);
}

// 三维几何模式：0 仅供旧算法回归，1 真实碰撞几何，2 缺数据时保留照亮，禁止再猜屏幕轮廓。
uniform int GeometryMode;
// 每灯的 64³ 形状编号平铺为 512² 区域，八灯按四列两行放在普通二维纹理中；0空、1完整方块。
uniform usampler2D GeometryCells;
// 两行分别为形状的最小点/盒数和最大点；保留楼梯等多个盒的并集。
uniform sampler2D GeometryPalette;
// 八个相机相对世界原点及有效区域编号，使用标量数组匹配 Minecraft Uniform 上传接口。
uniform float GeometryOrigins[32];
// 至多48个旋转实体盒：中心3、半长3、旋转4、适用光源位掩码1。
uniform float GeometryBodies[528];
// 当前真实提交的实体盒数量。
uniform int GeometryBodyCount;
// 观察坐标还原为相机相对世界坐标，方块格不会跟随相机旋转。
uniform mat4 GeometryViewToWorld;

// 对完整线段与有体积的方盒求交，返回进入/离开比例；不同表面只构成同一个体积。
vec2 rvpSegmentBox(vec3 origin, vec3 delta, vec3 minimum, vec3 maximum) {
    vec3 safe = vec3(abs(delta.x) < 1e-8 ? 1e-8 : delta.x,
                     abs(delta.y) < 1e-8 ? 1e-8 : delta.y, abs(delta.z) < 1e-8 ? 1e-8 : delta.z);
    vec3 a = (minimum - origin) / safe, b = (maximum - origin) / safe;
    vec3 lo = min(a, b), hi = max(a, b);
    return vec2(max(0.0, max(lo.x, max(lo.y, lo.z))), min(1.0, min(hi.x, min(hi.y, hi.z))));
}

vec3 rvpInverseRotate(vec3 point, vec4 rotation) {
    vec3 q = -rotation.xyz;
    return point + 2.0 * cross(q, cross(q, point) + rotation.w * point);
}

vec3 rvpTraceGeometry(int lightIndex, vec3 viewStart, vec3 viewLight) {
    vec3 start = (GeometryViewToWorld * vec4(viewStart, 1.0)).xyz;
    vec3 light = (GeometryViewToWorld * vec4(viewLight, 1.0)).xyz;
    float queries = 0.0;
    for (int body = 0; body < 48; ++body) {
        if (body >= GeometryBodyCount) { break; }
        int b = body * 11;
        if ((int(GeometryBodies[b + 10]) & (1 << lightIndex)) == 0) { continue; }
        vec3 center = vec3(GeometryBodies[b], GeometryBodies[b + 1], GeometryBodies[b + 2]);
        vec3 extents = vec3(GeometryBodies[b + 3], GeometryBodies[b + 4], GeometryBodies[b + 5]);
        vec4 rotation = vec4(GeometryBodies[b + 6], GeometryBodies[b + 7], GeometryBodies[b + 8], GeometryBodies[b + 9]);
        vec3 a = rvpInverseRotate(start - center, rotation), z = rvpInverseRotate(light - center, rotation);
        // 光心是否在盒内/贴面已由 CPU 用 double 世界坐标写入灯位标记，不能在这里用 float 再判一次。
        // 接收面自身在该碰撞盒中时仍豁免，避免车底/炮口被结构盒封死。
        if (all(lessThanEqual(abs(a), extents))) { continue; }
        vec2 hit = rvpSegmentBox(a, z - a, -extents, extents);
        queries += 1.0;
        if (hit.x <= hit.y && hit.y > 0.0001 && hit.x < 0.9999) { return vec3(0.0, 1.0, queries); }
    }
    int base = lightIndex * 4;
    if (GeometryOrigins[base + 3] < 0.5) { return vec3(1.0, 4.0, queries); }
    vec3 grid = vec3(GeometryOrigins[base], GeometryOrigins[base + 1], GeometryOrigins[base + 2]);
    vec3 origin = light - grid, delta = start - light;
    vec2 interval = rvpSegmentBox(origin, delta, vec3(0.0), vec3(64.0));
    if (interval.x > interval.y) { return vec3(1.0, 4.0, queries); }
    float entry = interval.x;
    ivec3 sourceCell = ivec3(floor(origin));
    ivec3 direction = ivec3(sign(delta));
    vec3 stride = 1.0 / max(abs(delta), vec3(1e-8));
    int layer = int(GeometryOrigins[base + 3]) - 1;
    for (int step = 0; step < 256; ++step) {
        ivec3 cell = ivec3(floor(origin + delta * entry + sign(delta) * 0.0001));
        if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(64)))) { break; }
        queries += 1.0;
        ivec2 texel = ivec2(cell.x + (cell.z & 7) * 64, cell.y + (cell.z >> 3) * 64);
        ivec2 tile = ivec2((layer & 3) * 512, (layer >> 2) * 512);
        int shape = int(texelFetch(GeometryCells, texel + tile, 0).r);
        if (shape != 0) {
            if (shape == 1 && any(notEqual(cell, sourceCell))) { return vec3(0.0, 1.0, queries); }
            // 光心在完整方块内部时只豁免该体素；部分形状必须逐盒判断，不能豁免同格的整根栏杆。
            if (shape > 1) {
                int count = int(texelFetch(GeometryPalette, ivec2(shape, 0), 0).w);
                for (int box = 0; box < 64; ++box) {
                    if (box >= count) { break; }
                    vec3 minimum = texelFetch(GeometryPalette, ivec2(shape + box, 0), 0).xyz + vec3(cell);
                    vec3 maximum = texelFetch(GeometryPalette, ivec2(shape + box, 1), 0).xyz + vec3(cell);
                    if (all(greaterThanEqual(origin, minimum)) && all(lessThanEqual(origin, maximum))) { continue; }
                    vec2 hit = rvpSegmentBox(origin, delta, minimum, maximum);
                    if (hit.x <= hit.y && hit.y > 0.0001 && hit.x < 0.9999) { return vec3(0.0, 1.0, queries); }
                }
            }
        }
        vec3 edge = vec3(cell) + max(vec3(direction), vec3(0.0));
        vec3 crossing = vec3(abs(delta.x) < 1e-8 ? 1e20 : (edge.x - origin.x) / delta.x,
                             abs(delta.y) < 1e-8 ? 1e20 : (edge.y - origin.y) / delta.y,
                             abs(delta.z) < 1e-8 ? 1e20 : (edge.z - origin.z) / delta.z);
        float exit = min(crossing.x, min(crossing.y, crossing.z));
        if (exit >= interval.y) { return vec3(1.0, interval.y >= 0.99999 ? 0.0 : 4.0, queries); }
        entry = max(exit, entry + 0.0000001);
    }
    return vec3(1.0, 4.0, queries);
}

// 诊断只计本片元真正执行的光路；正式运行不使用相机正面深度伪造方块/实体投影。
float rvpLightPathVisibility(int lightIndex, vec3 start, vec3 light) {
    vec3 result = GeometryMode == 1 ? rvpTraceGeometry(lightIndex, start, light)
            : GeometryMode == 2 ? vec3(1.0, 4.0, 0.0) : rvpTraceLightPath(start, light);
    rvpOcclusionStats.x += result.y == 1.0 ? 1.0 : 0.0;
    rvpOcclusionStats.y += result.y == 2.0 || result.y == 4.0 ? 1.0 : 0.0;
    rvpOcclusionStats.z += result.y == 3.0 ? 1.0 : 0.0;
    rvpOcclusionStats.w = max(rvpOcclusionStats.w, result.z);
    return result.x;
}
