package org.ywzj.rvp.network;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 旧核爆消息的公共消费门面，用于消除 network 包对 client 包的直接依赖。 */
public final class RVP_NuclearVisualEndpoint {
    /** 专服默认消费端。 */
    private static final Consumer<S2CNuclearVisualEffect> NO_OP = message -> { };
    /** 当前消费端。 */
    private static volatile Consumer<S2CNuclearVisualEffect> sink = NO_OP;
    /** 防止物理客户端重复安装。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private RVP_NuclearVisualEndpoint() {
    }

    public static void install(Consumer<S2CNuclearVisualEffect> newSink) {
        Objects.requireNonNull(newSink, "newSink");
        if (!INSTALLED.compareAndSet(false, true)) {
            throw new IllegalStateException("RVP nuclear visual sink is already installed");
        }
        sink = newSink;
    }

    public static void accept(S2CNuclearVisualEffect message) {
        sink.accept(message);
    }
}
