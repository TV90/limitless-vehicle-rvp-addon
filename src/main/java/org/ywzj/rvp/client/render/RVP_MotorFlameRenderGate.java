package org.ywzj.rvp.client.render;

/**
 * RVP 弹体模型尾焰的纯逻辑门控。
 *
 * <p>普通客户端实体使用自身的发动机状态；超视距视觉克隆使用服务端同步的燃烧快照。
 * 把选择逻辑抽出后，远程克隆的“快照缺失即不渲染”语义可以独立测试。</p>
 */
final class RVP_MotorFlameRenderGate {

    private RVP_MotorFlameRenderGate() {
    }

    /**
     * 解析一枚弹体当前是否允许绘制模型尾焰。
     *
     * @param remoteVisualClone 是否为超视距客户端视觉克隆
     * @param localMotorBurning 普通客户端实体自身解析出的发动机状态
     * @param remoteSnapshotMotorBurning 服务端远程燃烧快照中的发动机状态
     * @return 是否允许绘制模型尾焰
     */
    static boolean shouldRender(boolean remoteVisualClone, boolean localMotorBurning,
                                boolean remoteSnapshotMotorBurning) {
        return remoteVisualClone ? remoteSnapshotMotorBurning : localMotorBurning;
    }
}
