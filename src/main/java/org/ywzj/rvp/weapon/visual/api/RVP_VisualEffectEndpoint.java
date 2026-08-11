package org.ywzj.rvp.weapon.visual.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 公共消费门面；专服保持 no-op，物理客户端在 bootstrap 中安装真实 dispatcher。 */
public final class RVP_VisualEffectEndpoint {
    /** 专服默认消费端，不引用任何客户端类。 */
    private static final RVP_VisualEffectSink NO_OP = event -> { };
    /** 当前消费端。 */
    private static volatile RVP_VisualEffectSink sink = NO_OP;
    /** 防止客户端消费端被重复安装。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private RVP_VisualEffectEndpoint() {
    }

    public static void install(RVP_VisualEffectSink newSink) {
        Objects.requireNonNull(newSink, "newSink");
        if (!INSTALLED.compareAndSet(false, true)) {
            throw new IllegalStateException("RVP visual effect sink is already installed");
        }
        sink = newSink;
    }

    public static void accept(RVP_VisualEffectEvent event) {
        sink.accept(Objects.requireNonNull(event, "event"));
    }
}
