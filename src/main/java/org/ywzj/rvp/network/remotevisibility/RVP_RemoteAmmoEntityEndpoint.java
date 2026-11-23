package org.ywzj.rvp.network.remotevisibility;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * RVP 远程弹药实体快照的公共消费端口。
 *
 * <p>专用服务器保持 NOOP，客户端在启动阶段安装真实消费者，避免公共网络类加载客户端类型。</p>
 */
public final class RVP_RemoteAmmoEntityEndpoint {
    /** 专用服务器与客户端安装前使用的空消费端。 */
    private static final Consumer<S2CRemoteAmmoEntitySnapshot> NO_OP = message -> { };
    /** 当前生效的远程弹药实体消费端。 */
    private static volatile Consumer<S2CRemoteAmmoEntitySnapshot> sink = NO_OP;
    /** 防止物理客户端重复安装消费端。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private RVP_RemoteAmmoEntityEndpoint() {
    }

    /** 安装物理客户端的远程弹药实体消费端。 */
    public static void install(Consumer<S2CRemoteAmmoEntitySnapshot> newSink) {
        Objects.requireNonNull(newSink, "newSink");
        if (!INSTALLED.compareAndSet(false, true)) {
            throw new IllegalStateException("RVP remote ammo entity sink is already installed");
        }
        sink = newSink;
    }

    /** 将网络消息交给当前物理侧消费端。 */
    public static void accept(S2CRemoteAmmoEntitySnapshot message) {
        sink.accept(Objects.requireNonNull(message, "message"));
    }
}
