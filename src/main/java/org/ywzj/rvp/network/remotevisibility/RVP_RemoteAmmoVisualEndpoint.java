package org.ywzj.rvp.network.remotevisibility;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 弹药超视距视觉消息的公共消费端口。
 * <p>
 * 专用服务器保持 NOOP，物理客户端在启动阶段安装真实状态消费者，避免网络包直接依赖客户端类。
 */
public final class RVP_RemoteAmmoVisualEndpoint {
    /** 专用服务器与客户端安装前使用的空消费端。 */
    private static final Consumer<S2CRemoteAmmoVisualSnapshot> NO_OP = message -> { };
    /** 当前生效的弹药视觉消费端。 */
    private static volatile Consumer<S2CRemoteAmmoVisualSnapshot> sink = NO_OP;
    /** 防止物理客户端重复安装消费端。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private RVP_RemoteAmmoVisualEndpoint() {
    }

    /** 安装物理客户端的弹药视觉消费端。 */
    public static void install(Consumer<S2CRemoteAmmoVisualSnapshot> newSink) {
        Objects.requireNonNull(newSink, "newSink");
        if (!INSTALLED.compareAndSet(false, true)) {
            throw new IllegalStateException("RVP remote ammo visual sink is already installed");
        }
        sink = newSink;
    }

    /** 将网络消息交给当前物理侧消费端。 */
    public static void accept(S2CRemoteAmmoVisualSnapshot message) {
        sink.accept(Objects.requireNonNull(message, "message"));
    }
}
