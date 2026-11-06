package org.ywzj.rvp.client.performance;

/** 可复用的工作量计数；估算值不冒充显卡实际通过片元数、实际带宽或实际显存占用。 */
public enum RVP_RenderMetric {
    /** 已实际提交的独立绘制调用数；GUI批处理未逐批挂钩时不填写此项。 */
    DRAW_CALLS("draw_calls"),
    /** 原版PostChain中实际调用的后处理pass数量，单独列出而不猜测驱动draw call。 */
    POST_PASSES("post_passes"),
    /** 提交矩形覆盖的像素上限，包含可能被遮挡/丢弃的像素与重复绘制。 */
    PIXELS("submitted_pixels_upper_bound"),
    /** 像素上限乘采样步数；提前结束后的实际循环次数不会由此推断。 */
    RAY_SAMPLES("ray_samples_upper_bound"),
    /** 场景颜色或深度复制调用数。 */
    COPIES("copies"),
    /** 按目标尺寸及每像素存储估算的复制数据量，不是显卡真实内存总线流量。 */
    COPY_BYTES("copy_destination_bytes_estimate"),
    /** 当前范围内提交的光源、喷流、烟段、波层等业务条目数。 */
    ITEMS("submitted_items"),
    /** GUI逻辑矩形/面片数量，区别于批处理后的实际draw call。 */
    GUI_QUADS("gui_quads"),
    /** 动态视频雪花等纹理上传的目标字节数。 */
    UPLOAD_BYTES("upload_bytes_estimate"),
    /** 本帧新建/调整的二维纹理存储估算，不能当作当前显存存量。 */
    ALLOCATION_BYTES("texture_allocation_bytes_estimate");

    /** 独立日志中的稳定列名。 */
    public final String id;

    RVP_RenderMetric(String id) { this.id = id; }
}
