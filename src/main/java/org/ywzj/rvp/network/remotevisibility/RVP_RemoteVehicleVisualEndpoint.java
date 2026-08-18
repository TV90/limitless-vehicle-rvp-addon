package org.ywzj.rvp.network.remotevisibility;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 载具超视距视觉消息的公共消费端口。
 * <p>
 * 专用服务器和真实消费者安装前保持 NOOP；物理客户端启动时安装非世界代理状态消费者。
 */
public final class RVP_RemoteVehicleVisualEndpoint {
    /** 专用服务器与物理客户端真实消费者安装前使用的空消费端。 */
    private static final Consumer<S2CRemoteVehicleVisualSnapshot> NO_OP = message -> { };
    /** 当前生效的载具视觉消费端。 */
    private static volatile Consumer<S2CRemoteVehicleVisualSnapshot> sink = NO_OP;
    /** 防止后续物理客户端重复安装消费端。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private RVP_RemoteVehicleVisualEndpoint() {
    }

    /** 安装物理客户端的载具视觉消费端。 */
    public static void install(Consumer<S2CRemoteVehicleVisualSnapshot> newSink) {
        Objects.requireNonNull(newSink, "newSink");
        if (!INSTALLED.compareAndSet(false, true)) {
            throw new IllegalStateException("RVP remote vehicle visual sink is already installed");
        }
        sink = newSink;
    }

    /** 将网络消息交给当前物理侧消费端；未安装真实消费者时调用安全地无副作用。 */
    public static void accept(S2CRemoteVehicleVisualSnapshot message) {
        sink.accept(Objects.requireNonNull(message, "message"));
    }
}
