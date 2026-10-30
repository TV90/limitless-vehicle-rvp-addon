package org.ywzj.rvp.client.light;

import org.joml.Matrix4f;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DhProjectionMath;

/** 仅保存当前渲染帧的 DH 深度来源；不持有 DH 类型，也不修改借来的纹理。 */
public final class RVP_LightDepthFrame {
    /** 客户端唯一的当帧深度信箱，DH 桥和灯光合成器共用。 */
    public static final RVP_LightDepthFrame INSTANCE = new RVP_LightDepthFrame();
    /** 当前帧所属的世界身份；只比较对象身份，换维度不能复用深度。 */
    private Object world;
    /** 每次开始绘制都会递增的代号，与游戏是否暂停无关。 */
    private long frameId;
    /** 当前帧最后一个有效的非阴影 DH 深度来源。 */
    private Snapshot distant;
    /** 本帧是否确实到达 DH 地形绘制后的公开事件，防止读取光影残留的旧纹理。 */
    private boolean rendered;

    /** 在 Forge RenderTick START 清空来源，不能用游戏 tick 代替渲染帧。 */
    public void beginFrame(Object currentWorld) {
        world = currentWorld;
        frameId++;
        distant = null;
        rendered = false;
    }

    /** DH apply/cleanup 事件确认本帧已有地形结果；光影随后可提供其覆盖后的纹理 ID。 */
    public void markRendered(Object currentWorld) {
        if (currentWorld != null && currentWorld == world) { rendered = true; }
    }

    /** 光影持有有效纹理编号不等于本帧画过 DH，必须同时有当前帧的公开事件。 */
    public boolean wasRendered(Object currentWorld) { return currentWorld != null && currentWorld == world && rendered; }

    /** DH 公开事件发布矩阵副本；此处不执行 GL，也不依赖远距载具是否存在。 */
    public void publish(Object currentWorld, int texture, Matrix4f projection, Matrix4f view) {
        if (currentWorld == null || currentWorld != world || texture <= 0 || view == null
                || !view.isFinite() || !Float.isFinite(view.determinant()) || Math.abs(view.determinant()) < 1.0E-9F) {
            return;
        }
        // 调用已有 DH 深度数学校验，近远裁剪面以实际矩阵为准，支持正向和反向深度。
        if (RVP_DhProjectionMath.analyzeFromProjection(projection).isEmpty()) { return; }
        distant = new Snapshot(texture, projection, view, frameId);
        rendered = true;
    }

    /** 只返回同一世界、同一绘制帧的来源，缺失时保留近景光照。 */
    public Snapshot current(Object currentWorld) {
        return currentWorld != null && currentWorld == world && distant != null
                && distant.frameId() == frameId ? distant : null;
    }

    /** 世界卸载和资源重载时释放所有借用引用，不删除外部纹理。 */
    public void clear() { world = null; distant = null; rendered = false; frameId++; }

    /**
     * 当帧只读快照。
     * @param texture DH 所有的二维深度纹理，只允许读取
     * @param projection 与该深度配对的 DH 投影矩阵
     * @param view 与该深度配对的观察矩阵
     * @param frameId 发布时的绘制帧代号
     */
    public record Snapshot(int texture, Matrix4f projection, Matrix4f view, long frameId) {
        public Snapshot { projection = new Matrix4f(projection); view = new Matrix4f(view); }
        @Override public Matrix4f projection() { return new Matrix4f(projection); }
        @Override public Matrix4f view() { return new Matrix4f(view); }
    }
}
