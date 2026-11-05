package org.ywzj.rvp.weapon.visual;

import org.ywzj.rvp.config.RVP_ClientConfig;

/** 火箭体积尾烟的代码开关和预算；不依赖客户端类型，也不按武器 ID 区分。 */
public final class RVP_RocketTrailParameters {
    /**
     * 新的世界轨迹与逐像素体积烟通道是否启用。
     *
     * <p>读取客户端配置 {@code rocketTrail.newVolumeTrail}，默认 false：恢复旧贴图尾迹与
     * 旧贴地烟浪。配置只在客户端渲染路径中读取，服务端不会加载该客户端配置。</p>
     */
    public static boolean volumeEnabled() {
        // 调用客户端配置读取器；未注册/未加载时返回旧版默认。
        return RVP_ClientConfig.isRocketTrailVolumeEnabled();
    }

    /** 旧贴图尾迹和贴地烟浪是否启用；与新体积烟通道互斥。 */
    public static boolean originalParticlesEnabled() {
        return !volumeEnabled();
    }

    /** 同一世界最多缓存的弹体来源；近距实体与远距克隆共用同步实体编号。 */
    public static final int MAX_SOURCES = 128;
    /** 全部来源最多保存的烟段，防止长时间齐射无限占用内存。 */
    public static final int MAX_STORED_SEGMENTS = 16384;
    /** 每个来源最多保留的烟段，避免单枚长航程导弹占满全局预算。 */
    public static final int MAX_SEGMENTS_PER_SOURCE = 1024;
    /** 每帧最多绘制 2048 段，比原 512 段提高四倍；先按来源公平取样，再按前后距离排序。 */
    public static final int MAX_VISIBLE_SEGMENTS = 2048;
    /** 单帧投影矩形累计最多 32 屏，比原三屏提高约 10.7 倍，允许更多重叠体积烟参与绘制。 */
    public static final double MAX_SCREEN_COVERAGE = 32.0;
    /** 相机在烟内时，最多为烟外体积预留 24 屏，比原两屏提高十二倍；未用完的份额仍可借用。 */
    public static final double EXTERIOR_SCREEN_RESERVE = 24.0;
    /** 一次位置更新最多拆出的烟段数；异常跳点直接断开，不连接跨地图烟柱。 */
    public static final int MAX_SEGMENTS_PER_UPDATE = 32;
    /** 正常尾迹允许连接的最大距离，单位格。 */
    public static final double MAX_JOIN_DISTANCE = 512;
    /** 单段长度上限，单位格；直视烟柱时仍可保持有界的采样步长。 */
    public static final double MAX_SEGMENT_LENGTH = 32;
    /** 单段逐像素采样次数上限；显卡程序使用同一个固定上限。 */
    public static final int RAY_STEPS = 24;
    /** 新贴地烟浪每 tick 两团体积烟，替代旧的八颗贴图片片。 */
    public static final int WASH_PUFFS_PER_TICK = 2;
    /** 贴地冲刷烟的出生密度；原为 0.65，提高到 1.5，实际透明度仍由穿烟厚度和淡出决定。 */
    public static final float WASH_DENSITY = 1.5F;

    /** 单颗凝结云粒子或单个体积烟段的总寿命上限，含保持和淡出。 */
    private static final int MAX_LIFETIME_TICKS = 300;

    public record Lifetime(int holdTicks, int fadeTicks) {
        public int totalTicks() {
            return holdTicks + fadeTicks;
        }
    }

    /** 为淡出留出时间，再用剩余预算限制发动机带来的保持期。 */
    public static Lifetime resolveLifetime(int remainingBurnTicks, int configuredHoldMinTicks, long requestedFadeTicks) {
        long requestedHold = Math.max(
                Math.max(0, remainingBurnTicks) + (long) RVP_RocketFlameRuntimeTuning.resolveHoldBufferTicks(),
                RVP_RocketFlameRuntimeTuning.resolveHoldMinTicks(configuredHoldMinTicks));
        int fade = (int) Math.min(MAX_LIFETIME_TICKS, requestedFadeTicks);
        int hold = (int) Math.min(requestedHold, MAX_LIFETIME_TICKS - fade);
        return new Lifetime(hold, fade);
    }

    private RVP_RocketTrailParameters() { }
}
