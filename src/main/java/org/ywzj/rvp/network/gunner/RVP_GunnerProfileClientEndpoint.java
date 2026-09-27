package org.ywzj.rvp.network.gunner;

/** 公共网络层到客户端 Gunner Profile 状态的分发端口，服务端不会加载客户端类。 */
public final class RVP_GunnerProfileClientEndpoint {
    /** 当前客户端安装的消费端；服务端保持空实现。 */
    private static Listener listener = message -> {};
    private RVP_GunnerProfileClientEndpoint() {}
    public static void install(Listener newListener) { listener = newListener == null ? message -> {} : newListener; }
    public static void accept(S2CGunnerProfileSnapshot message) { listener.onProfiles(message); }
    @FunctionalInterface public interface Listener { void onProfiles(S2CGunnerProfileSnapshot message); }
}
