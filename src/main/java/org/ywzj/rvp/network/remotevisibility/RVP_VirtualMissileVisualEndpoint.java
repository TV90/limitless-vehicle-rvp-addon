package org.ywzj.rvp.network.remotevisibility;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 虚拟弹只供显示的公共端口；专服保持空操作，不引用客户端或目标表。 */
public final class RVP_VirtualMissileVisualEndpoint {
    /** 安装客户端前以及专用服务端使用的空接收器。 */
    private static volatile Consumer<S2CVirtualMissileVisualSnapshot> sink = message -> { };
    /** 客户端消费端只允许安装一次。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private RVP_VirtualMissileVisualEndpoint() { }

    /** 客户端启动时安装真实消费者，保持公共网络注册双端安全。 */
    public static void install(Consumer<S2CVirtualMissileVisualSnapshot> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (!INSTALLED.compareAndSet(false, true)) throw new IllegalStateException("Visual sink already installed");
        sink = consumer;
    }

    /** 接收端已在主线程，原样转交完整集合。 */
    public static void accept(S2CVirtualMissileVisualSnapshot message) { sink.accept(message); }
}
