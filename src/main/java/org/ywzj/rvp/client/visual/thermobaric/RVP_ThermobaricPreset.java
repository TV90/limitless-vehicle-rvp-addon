package org.ywzj.rvp.client.visual.thermobaric;

import net.minecraft.resources.ResourceLocation;

/**
 * 温压视觉的不可变类型化预设。
 *
 * @param coreColor 点火核心 RGB 颜色
 * @param flameColor 主火球与早期云团 RGB 颜色
 * @param smokeColor 后期烟云 RGB 颜色
 * @param showCore 是否显示点火核心与主火球
 * @param showPressureWave 是否显示压力波光学球壳
 * @param showCondensationCloud 是否显示使用纯白贴图球壳实现的压力波凝结云墙
 * @param showCondensationCloudParticles 是否显示仅使用方块团粒子贴图实现的压力波凝结云
 * @param condensationCloudParticleMaxCount 粒子凝结云在完整视觉密度下允许显示的最大粒子数量
 * @param condensationCloudParticleScale 粒子凝结云的贴图尺寸缩放倍率
 * @param condensationCloudParticleSpawnThicknessFactor 粒子凝结云在 start tick 的墙体厚度倍率，默认 1.0；
 *                                                        只影响生成阶段起始厚度，full tick 后恢复原曲线
 * @param condensationCloudCutSpeedFactor 凝结云在完整成形后从 Y 轴最高点向下连续裁切的无量纲速度倍率，默认 1.0；
 *                                          接受非负有限值，0 关闭裁切，只在凝结云显示时生效且不改变径向速度、淡出或寿命
 * @param thermobaricLod 火球、粒子凝结云、尘环与后燃烟云共用的四档距离 LOD
 * @param showDustRing 是否显示贴地尘环
 * @param showCloud 是否显示后燃烟云
 * @param maxClouds 完整视觉密度下后燃烟云允许生成的最大云团数量
 * @param maxFireballClouds 完整视觉密度下温压火球允许生成的最大团状云数量
 * @param maxDustSegments 完整视觉密度下贴地尘环允许生成的最大环段数量
 * @param dustGroundRadialSamples 尘环沿扩散半径预采样的地表层数
 * @param pressureRings 压力波及凝结云球壳的纬向细分数
 * @param pressureSegments 压力波及凝结云球壳的经向细分数
 * @param coreStartTick 主火球相对整个效果起点的绝对开始 tick
 * @param coreFullTick 主火球完成扩张并完整成形的绝对 tick
 * @param coreFadeDurationTicks 主火球完整成形后到完全消失的持续 tick
 * @param pressureWaveStartTick 压力波相对整个效果起点的绝对开始 tick
 * @param pressureWaveFullTick 压力波到达最大半径的绝对 tick
 * @param pressureWaveFadeSpeedFactor 压力波与两种凝结云从 full tick 到派生结束 tick 的线性淡出速度倍率，默认 0；
 *                                        接受非负有限值，0 关闭线性淡出，且不改变径向速度、裁切或寿命
 * @param dustRingStartTick 贴地尘环相对整个效果起点的绝对开始 tick
 * @param dustRingFullTick 贴地尘环到达配置半径并立即开始匀速外扩消散的绝对 tick
 * @param cloudStartTick 后燃烟云相对整个效果起点的绝对开始 tick
 * @param cloudFullTick 后燃烟云开始消散的绝对 tick，仅控制淡出起点，不切换运动曲线
 * @param cloudFadeDurationTicks 后燃烟云开始消散后到完全消失的持续 tick
 * @param cloudColorChangeStartTick 后燃烟云从火焰色向烟色变化的绝对开始 tick
 * @param cloudColorChangeEndTick 后燃烟云完全变为烟色的绝对结束 tick
 * @param pressureRadiusFactor 压力波与凝结云在 full tick 时相对基础爆炸半径的半径倍率
 * @param dustRadiusFactor 尘环相对基础爆炸半径的倍率
 * @param cloudRadiusFactor 烟云横向相对基础爆炸半径的倍率
 * @param cloudRiseFactor 烟云最终最高升起高度相对基础爆炸半径的倍率，默认 1.2 且不设上限
 * @param cloudRiseSpeedFactor 烟云竖直升起速度的无量纲倍率，默认 1.0，零值停止基础升起
 * @param cloudRollSpeedFactor 烟云翻滚、卷吸、湍流与水平平流速度的无量纲倍率，默认 1.0，零值冻结对应运动
 * @param nearSound 近距离音效资源 ID，阶段 C 启用
 * @param farSound 远距离音效资源 ID，阶段 C 启用
 * @param tailSound 尾音资源 ID，阶段 C 启用
 */
public record RVP_ThermobaricPreset(
        int coreColor,
        int flameColor,
        int smokeColor,
        boolean showCore,
        boolean showPressureWave,
        boolean showCondensationCloud,
        boolean showCondensationCloudParticles,
        int condensationCloudParticleMaxCount,
        float condensationCloudParticleScale,
        float condensationCloudParticleSpawnThicknessFactor,
        float condensationCloudCutSpeedFactor,
        RVP_ThermobaricLod thermobaricLod,
        boolean showDustRing,
        boolean showCloud,
        int maxClouds,
        int maxFireballClouds,
        int maxDustSegments,
        int dustGroundRadialSamples,
        int pressureRings,
        int pressureSegments,
        int coreStartTick,
        int coreFullTick,
        int coreFadeDurationTicks,
        int pressureWaveStartTick,
        int pressureWaveFullTick,
        float pressureWaveFadeSpeedFactor,
        int dustRingStartTick,
        int dustRingFullTick,
        int cloudStartTick,
        int cloudFullTick,
        int cloudFadeDurationTicks,
        int cloudColorChangeStartTick,
        int cloudColorChangeEndTick,
        float pressureRadiusFactor,
        float dustRadiusFactor,
        float cloudRadiusFactor,
        float cloudRiseFactor,
        float cloudRiseSpeedFactor,
        float cloudRollSpeedFactor,
        ResourceLocation nearSound,
        ResourceLocation farSound,
        ResourceLocation tailSound
) {
    /** 阶段 B 使用的内建安全默认预设。 */
    public static final RVP_ThermobaricPreset DEFAULT = new RVP_ThermobaricPreset(
            0xFFD2A0,
            0xFF7A24,
            0x3A302D,
            true,
            false,
            false,
            true,
            1024,
            8.0F,
            1.0F,
            1.0F,
            RVP_ThermobaricLod.DEFAULT,
            true,
            true,
            200,
            240,
            128,
            9,
            16,
            32,
            0,
            4,
            10,
            2,
            8,
            0.0F,
            3,
            36,
            0,
            35,
            65,
            8,
            35,
            3.5F,
            2.4F,
            1.5F,
            1.2F,
            1.0F,
            1.0F,
            ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric_near"),
            ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric_far"),
            ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric_tail")
    );

    /**
     * 返回压力波与凝结云的派生结束 tick；消散时长与成形时长相同，保证径向速度连续。
     */
    public int pressureWaveEndTick() {
        if (pressureWaveFullTick <= pressureWaveStartTick) {
            return pressureWaveStartTick;
        }
        return saturatedAdd(pressureWaveFullTick,
                pressureWaveFullTick - pressureWaveStartTick);
    }

    /**
     * 返回尘环的派生结束 tick；淡出时长与成形时长相同，保证 full tick 前后径向速度一致。
     */
    public int dustRingEndTick() {
        if (dustRingFullTick <= dustRingStartTick) {
            return dustRingStartTick;
        }
        return saturatedAdd(dustRingFullTick, dustRingFullTick - dustRingStartTick);
    }

    /** 返回所有子效果的最晚结束 tick，作为默认实例结束时间。 */
    public int effectEndTick() {
        return Math.max(Math.max(saturatedAdd(coreFullTick, coreFadeDurationTicks),
                        pressureWaveEndTick()),
                Math.max(dustRingEndTick(),
                        saturatedAdd(cloudFullTick, cloudFadeDurationTicks)));
    }

    /** 对两个非负 tick 值做饱和加法，避免自由配置的大数在计算结束时间时溢出。 */
    private static int saturatedAdd(int left, int right) {
        long sum = (long) left + right;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
