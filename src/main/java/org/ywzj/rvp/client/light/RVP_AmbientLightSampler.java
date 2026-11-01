package org.ywzj.rvp.client.light;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** 光源附近的原版环境亮度；只读已加载区块，同一帧各受光通道按世界位置共享结果。 */
final class RVP_AmbientLightSampler {
    /** 负值表示当地光照不可用，显卡此时只按原画面亮度判断，不能把缺数据当成全黑。 */
    static final float UNAVAILABLE = -1F;
    /** 无世界时的取样器；不保留上一世界的引用。 */
    private static final ToDoubleFunction<BlockPos> NO_WORLD = position -> UNAVAILABLE;
    /** 当帧世界位置到环境亮度的缓存；不能用灯槽编号，因为烟雾会重新筛选和排列灯光。 */
    private final Map<Vec3, Float> samples = new HashMap<>();
    /** 当前帧读取单个方块位置的入口，返回0～1亮度或负值。 */
    private ToDoubleFunction<BlockPos> brightness = NO_WORLD;

    /** 在复制原场景颜色后建立当帧取样入口；实时天光因子包含昼夜、雨和雷暴。 */
    void beginFrame(ClientLevel level, float partialTick) {
        if (level == null) {
            // 调用统一清理，换世界或退出游戏后不能复用旧环境亮度。
            clear();
            return;
        }
        float daylight = level.getSkyFlashTime() > 0 ? 1F : level.getSkyDarken(partialTick);
        boolean hasSky = level.dimensionType().hasSkyLight();
        // 调用统一帧初始化，客户端和可控测试都走相同的插值及缓存路径。
        beginFrame(position -> {
            if (!level.hasChunkAt(position)) { return UNAVAILABLE; }
            int block = level.getBrightness(LightLayer.BLOCK, position);
            int sky = hasSky ? level.getBrightness(LightLayer.SKY, position) : 0;
            // 原版换算同时包含维度基础环境亮度；不用客户端可能过期的整数 skyDarken。
            float blockBrightness = LightTexture.getBrightness(level.dimensionType(), block);
            float skyBrightness = hasSky ? LightTexture.getBrightness(level.dimensionType(), sky) * daylight : 0;
            return Math.max(blockBrightness, skyBrightness);
        });
    }

    /** 替换整帧数据源并丢弃缓存；测试可模拟区块加载、天气变化与世界切换。 */
    void beginFrame(ToDoubleFunction<BlockPos> lookup) {
        samples.clear();
        brightness = lookup == null ? NO_WORLD : lookup;
    }

    /** 同位置只查询一次；关闭适应或游戏光照权重为0时完全跳过世界查询。 */
    float sample(Vec3 position) {
        // 调用统一调参入口，既有光源下一帧即可响应，不修改其年龄或原始亮度。
        if (RVP_LightRuntimeTuning.get(Group.global, Parameter.ambientEnabled) < 0.5F
                || RVP_LightRuntimeTuning.get(Group.global, Parameter.ambientWorldWeight) <= 0) {
            return UNAVAILABLE;
        }
        // 调用以方块中心为节点的三线性插值，移动光源越过方块边界时不突然跳亮。
        return samples.computeIfAbsent(position, point -> interpolate(point, brightness));
    }

    /** 八个邻近方块按距离混合；任一有贡献的样点未加载时，整次采样明确退回画面判断。 */
    static float interpolate(Vec3 position, ToDoubleFunction<BlockPos> lookup) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)) { return UNAVAILABLE; }
        double x = position.x - 0.5, y = position.y - 0.5, z = position.z - 0.5;
        int baseX = Mth.floor(x), baseY = Mth.floor(y), baseZ = Mth.floor(z);
        double fractionX = x - baseX, fractionY = y - baseY, fractionZ = z - baseZ;
        double result = 0;
        BlockPos.MutableBlockPos point = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    double weight = (dx == 0 ? 1 - fractionX : fractionX)
                            * (dy == 0 ? 1 - fractionY : fractionY)
                            * (dz == 0 ? 1 - fractionZ : fractionZ);
                    if (weight <= 0) { continue; }
                    double value = lookup.applyAsDouble(point.set(baseX + dx, baseY + dy, baseZ + dz));
                    if (!Double.isFinite(value) || value < 0) { return UNAVAILABLE; }
                    result += Math.min(1, value) * weight;
                }
            }
        }
        return (float) result;
    }

    /** 帧结束、世界卸载与资源重载均释放查询入口及缓存。 */
    void clear() {
        samples.clear();
        brightness = NO_WORLD;
    }
}
