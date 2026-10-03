package org.ywzj.rvp.network;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 导弹空中目标命中视觉消息的公共消费端口，保持专用服务器不加载客户端粒子类。 */
public final class RVP_MissileAirTargetImpactFragmentEndpoint {
    /** 客户端安装真实消费者前的专用服务器空实现。 */
    private static final Consumer<S2CMissileAirTargetImpactFragments> NO_OP = message -> { };
    /** 当前生效的视觉消息消费者。 */
    private static volatile Consumer<S2CMissileAirTargetImpactFragments> sink = NO_OP;
    /** 防止客户端重复安装消费者。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private RVP_MissileAirTargetImpactFragmentEndpoint() {
    }

    /** 安装物理客户端的视觉消息消费者。 */
    public static void install(Consumer<S2CMissileAirTargetImpactFragments> newSink) {
        Objects.requireNonNull(newSink, "newSink");
        if (!INSTALLED.compareAndSet(false, true)) {
            throw new IllegalStateException("RVP missile impact fragment sink is already installed");
        }
        sink = newSink;
    }

    /** 将网络消息交给当前物理侧消费者。 */
    public static void accept(S2CMissileAirTargetImpactFragments message) {
        sink.accept(Objects.requireNonNull(message, "message"));
    }
}
