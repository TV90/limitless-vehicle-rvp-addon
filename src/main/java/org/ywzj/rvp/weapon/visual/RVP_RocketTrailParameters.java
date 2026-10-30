package org.ywzj.rvp.weapon.visual;

/** 火箭体积尾烟的代码开关和预算；不依赖客户端类型，也不按武器 ID 区分。 */
public final class RVP_RocketTrailParameters {
    /** 新的世界轨迹与逐像素体积烟通道，沿用 rvp_rocket_flame 风格入口。 */
    public static final boolean VOLUME_ENABLED = true;
    /** 旧贴图尾迹和贴地烟浪停止生成；保留全部构造、动画和绘制代码供显式切回。 */
    public static final boolean ORIGINAL_PARTICLES_ENABLED = false;
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

    private RVP_RocketTrailParameters() { }
}
