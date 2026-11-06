package org.ywzj.rvp.client.performance;

/** 屏幕空间性能目录；父级耗时包含子级，读日志时不能把父子两行再次相加。 */
public enum RVP_RenderStage {
    /** 整个客户端渲染阶段的对照，包含Minecraft及其它模组，不等于RVP自身总耗时。 */
    FRAME("frame.render", "整帧渲染对照", ""),
    /** 统一屏幕光的候选筛选、深度准备与可见性预算。 */
    SCREEN_PREPARE("screen.prepare", "屏幕视觉输入准备", "frame.render"),
    /** 整套RVP屏幕光、喷流、烟与爆炸的合成。 */
    SCREEN_COMPOSITE("screen.composite", "屏幕视觉合成总计", "frame.render"),
    /** 不透明近景的只读深度复制。 */
    SCENE_DEPTH("screen.copy.depth", "近景深度复制", "screen.prepare"),
    /** DH独立投影的深度复制。 */
    SCENE_DISTANT_DEPTH("screen.copy.distant_depth", "远景深度复制", "screen.prepare"),
    /** 基础场景颜色与最终合成背景的复制。 */
    SCENE_COLOR("screen.copy.color", "场景颜色复制", "screen.composite"),
    /** 表面受光、天空散射、普通光晕来源。 */
    LIGHT("screen.lighting", "世界照亮、天空与光晕来源", "screen.composite"),
    /** 只做CPU几何采集与显卡几何数据上传，不把CPU时间伪装成显卡时间。 */
    LIGHT_GEOMETRY("screen.light_geometry", "照明遮挡几何准备", "screen.composite"),
    /** 光晕降采样、横纵模糊和多尺度回升。 */
    BLOOM("screen.bloom", "多尺度光晕柔化", "screen.composite"),
    /** 光晕与已照亮场景的最终合成。 */
    BLOOM_COMPOSITE("screen.bloom_composite", "光晕最终合成", "screen.composite"),
    /** 发光核心的独立显示。 */
    EMISSION("screen.emission", "爆炸及炮口自发光核心", "screen.composite"),
    /** 定向喷焰的光晕来源和显示峰值。 */
    PLUME("screen.plume", "程序喷焰", "screen.composite"),
    /** 喷焰广域光晕与一像素可见度检测。 */
    PLUME_HALO("screen.plume_halo", "喷焰广域光晕", "screen.composite"),
    /** 粒子烟和模型贴图的屏幕空间受光。 */
    SMOKE_LIGHT("screen.smoke_light", "烟雾及炮口贴图受光", "screen.composite"),
    /** 程序体积尾烟。 */
    TRAIL("screen.trail", "程序体积尾烟", "screen.composite"),
    /** F8专用大爆炸，含背景刷新、火焰、黑烟及热浪。 */
    VEHICLE_EXPLOSION("screen.vehicle_explosion", "程序大爆炸", "screen.composite"),
    /** 普通冲击波提前保存世界深度。 */
    PRESSURE_DEPTH("pressure.copy_depth", "冲击波深度复制", "frame.render"),
    /** 普通冲击波的多层空气折射与凝结雾。 */
    PRESSURE("pressure.composite", "冲击波空气折射与雾层", "frame.render"),
    /** 核爆原有折射球壳。 */
    NUCLEAR_REFRACTION("nuclear.refraction", "核爆球壳折射", "frame.render"),
    /** NVG像增强、两种尺度的模糊及最终配色。 */
    NIGHT_VISION("post.night_vision", "夜视后处理", "frame.render"),
    /** 夜视下干扰机的屏幕投影光斑。 */
    NIGHT_VISION_GLOW("post.night_vision_glow", "夜视干扰机光斑", "frame.render"),
    /** 电视导弹的黑白后处理。 */
    TV_VIDEO("post.tv_video", "电视导弹黑白画面", "frame.render"),
    /** CRT扫描线等屏幕后处理。 */
    CRT("post.crt", "CRT屏幕后处理", "frame.render"),
    /** 向本体热成像缓冲补画RVP热粒子，不包含本体自身后处理。 */
    THERMAL_PARTICLES("thermal.rvp_particles", "RVP热粒子补绘", "frame.render"),
    /** 夜视后处理不可用时的绿罩和暗角。 */
    NIGHT_VISION_FALLBACK("overlay.night_vision", "夜视绿罩及暗角", "frame.render"),
    /** 激光致盲的全屏覆盖。 */
    LASER_BLIND("overlay.laser_blind", "激光致盲白闪", "frame.render"),
    /** DIRCM干扰的中心扩散白闪。 */
    DIRCM("overlay.dircm", "DIRCM视频干扰白闪", "frame.render"),
    /** 失图和链路阻断时的视频雪花，含噪点纹理刷新。 */
    VIDEO_SNOW("overlay.video_snow", "视频雪花背景", "frame.render"),
    /** 核爆全屏闪光。 */
    NUCLEAR_FLASH("overlay.nuclear_flash", "核爆闪光", "frame.render"),
    /** 温压爆炸的全屏闪光。 */
    THERMOBARIC_FLASH("overlay.thermobaric_flash", "温压爆炸闪光", "frame.render"),
    /** 相机角度震动只消耗CPU，不创建GPU工作。 */
    CAMERA_SHAKE("camera.shake", "通用镜头震动计算", "frame.render"),
    /** DH前端深度合成的完整准备与分层输出。 */
    DH_COMPOSITE("dh.composite", "远景深度合成总计", "frame.render"),
    /** DH合成前的独立深度复制。 */
    DH_DEPTH_COPY("dh.copy_depth", "远景合成深度复制", "dh.composite"),
    /** DH每层全屏颜色和视深度输出。 */
    DH_LAYER("dh.layer", "远景单层屏幕合成", "dh.composite"),
    /** DH不可深度合成时的晚期轮廓/始终可见输出。 */
    DH_FALLBACK("dh.fallback", "远景晚期屏幕输出", "frame.render");

    /** 日志稳定键，不受显示语言影响。 */
    public final String id;
    /** 给人看的中文名称。 */
    public final String title;
    /** 展示用的通常父级；真实调用嵌套可能有分支，所有时间均明确标为包含子级。 */
    public final String parent;

    RVP_RenderStage(String id, String title, String parent) { this.id = id; this.title = title; this.parent = parent; }
}
