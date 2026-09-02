package org.ywzj.rvp.client.render.remotevisibility;

/** 远距载具帧计划的唯一消费状态机；不依赖 Minecraft/OpenGL，便于自动化验证。 */
final class RVP_RemoteVehicleFrameRoute {
    /** 当前帧路由状态。 */
    private State state = State.EMPTY;

    /** 新计划替换上一帧残留，并进入等待 DH 合成状态。 */
    void prepare(boolean hasPlan) {
        state = hasPlan ? State.PREPARED : State.EMPTY;
    }

    /** 尝试由 DH 深度感知通道消费；每帧最多成功一次。 */
    boolean consumeDh() {
        if (state != State.PREPARED) {
            return false;
        }
        state = State.DH_COMPOSITED;
        return true;
    }

    /** 尝试由世界末端的 RVP_FIRST 优先显示通道消费。 */
    boolean consumeRvpFirst() {
        if (state != State.PREPARED) {
            return false;
        }
        state = State.RVP_FIRST;
        return true;
    }

    /** 尝试由原有实体后通道降级消费。 */
    boolean consumeCurrentPass() {
        if (state != State.PREPARED) {
            return false;
        }
        state = State.CURRENT_PASS;
        return true;
    }

    /** 尝试由世界末端降级通道消费。 */
    boolean consumeLateFallback() {
        if (state != State.PREPARED) {
            return false;
        }
        state = State.LATE_FALLBACK;
        return true;
    }

    /** 清空当前路由，供换维度、资源重载和退出世界使用。 */
    void clear() {
        state = State.EMPTY;
    }

    /** 返回当前状态，供测试与诊断使用。 */
    State state() {
        return state;
    }

    /** 一帧计划的生命周期状态。 */
    enum State {
        /** 没有可消费计划。 */
        EMPTY,
        /** 已准备，尚未决定最终输出通道。 */
        PREPARED,
        /** 已由 DH apply 前深度感知通道消费。 */
        DH_COMPOSITED,
        /** 已由 AFTER_LEVEL 的 RVP_FIRST 优先显示通道消费。 */
        RVP_FIRST,
        /** 已由原有 AFTER_ENTITIES 通道消费。 */
        CURRENT_PASS,
        /** 已由 AFTER_LEVEL 显式降级通道消费。 */
        LATE_FALLBACK
    }
}
