package org.ywzj.rvp.network.firesupport;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 公共网络层到物理客户端状态的安装端口；专用服务端始终使用 NOOP。 */
public final class RVP_FireSupportClientEndpoint {
    /** 未安装客户端状态前的安全空监听器。 */ private static final Listener NO_OP = new Listener() {};
    /** 当前物理侧监听器。 */ private static volatile Listener listener = NO_OP;
    /** 防止客户端重复安装。 */ private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private RVP_FireSupportClientEndpoint() {}

    /** 由客户端 bootstrap 唯一安装真实监听器。 */
    public static void install(Listener newListener) {
        Objects.requireNonNull(newListener, "newListener");
        if (!INSTALLED.compareAndSet(false, true)) throw new IllegalStateException("炮火支援客户端端口已安装");
        listener = newListener;
    }

    /** 分发完整 profile 快照。 */ public static void accept(S2CFireSupportProfileSnapshot message) { listener.onProfiles(message); }
    /** 分发请求结果。 */ public static void accept(S2CFireSupportRequestResult message) { listener.onRequestResult(message); }
    /** 分发任务状态变化。 */ public static void accept(S2CFireSupportMissionUpdate message) { listener.onMissionUpdate(message); }

    /** 客户端状态监听契约；默认方法使服务端 NOOP 不加载任何客户端类型。 */
    public interface Listener {
        /** 接收 profile 快照。 */ default void onProfiles(S2CFireSupportProfileSnapshot message) {}
        /** 接收请求结果。 */ default void onRequestResult(S2CFireSupportRequestResult message) {}
        /** 接收任务变化。 */ default void onMissionUpdate(S2CFireSupportMissionUpdate message) {}
    }
}
