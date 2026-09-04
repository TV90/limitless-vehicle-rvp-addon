package org.ywzj.rvp.debug;

import java.util.List;

/**
 * RVP 统一调试日志开关注册表。
 *
 * <p>把此前"调试期遗忘取消、无条件自动输出"的高频日志收敛为命令可控的开关：
 * {@code /rvpdebug flags <name> on|off|status}、{@code /rvpdebug flags list}。
 * 每个开关对应一类日志，服务端日志由服务端命令控制、客户端日志由客户端命令控制
 * （单机/集成服务端时两端同 JVM 共享同一份静态状态）。
 */
public final class RVP_DebugFlags {

    /** 弹体引信诊断（近炸抑制 / 探测盒起爆）。服务端。 */
    public static final RVP_DebugFlag FUSE = new RVP_DebugFlag("fuse", "弹体引信诊断（近炸抑制/起爆）");
    /** 干扰 / 导引头对抗诊断（箔条、诱饵、脱锁）。服务端。 */
    public static final RVP_DebugFlag JAM = new RVP_DebugFlag("jam", "干扰/导引头对抗诊断");
    /** 干扰物系统（箔条 / 热焰 / 烟雾抛洒与出膛）。服务端。 */
    public static final RVP_DebugFlag CM = new RVP_DebugFlag("cm", "干扰物系统（箔条/热焰/烟雾）");
    /** 主动 / 被动 ECM 与 RWR 探针日志。双端。 */
    public static final RVP_DebugFlag ECM = new RVP_DebugFlag("ecm", "主动/被动 ECM 与 RWR 探针");
    /** 可部署 / 链式 UAV 状态。服务端。 */
    public static final RVP_DebugFlag UAV = new RVP_DebugFlag("uav", "可部署/链式 UAV 状态");
    /** AI 乘员行为（烟雾扫描、抛烟、ECM 判定）。服务端。 */
    public static final RVP_DebugFlag GUNNER = new RVP_DebugFlag("gunner", "AI 乘员行为");
    /** 生成 / 上车补弹。服务端。 */
    public static final RVP_DebugFlag SPAWN = new RVP_DebugFlag("spawn", "生成/上车补弹");
    /** 纯物理碰撞体重建与加载。服务端。 */
    public static final RVP_DebugFlag PHYSICS = new RVP_DebugFlag("physics", "纯物理碰撞体重建");
    /** 发射架部署状态。双端。 */
    public static final RVP_DebugFlag LAUNCH_DEPLOY = new RVP_DebugFlag("launch_deploy", "发射架部署状态");
    /** 客户端骨骼模块 / 状态同步。客户端。 */
    public static final RVP_DebugFlag CLIENT_STATE = new RVP_DebugFlag("client_state", "客户端骨骼模块/状态同步");
    /** 命中提示 UI。客户端。 */
    public static final RVP_DebugFlag HIT_UI = new RVP_DebugFlag("hit_ui", "命中提示 UI");
    /** HUD / Overlay 注册探针。客户端。 */
    public static final RVP_DebugFlag HUD = new RVP_DebugFlag("hud", "HUD/Overlay 注册探针");
    /** 火箭 CCIP 准星替换。客户端。 */
    public static final RVP_DebugFlag CCIP = new RVP_DebugFlag("ccip", "火箭 CCIP 准星");
    /** 头盔显示（IR 锁定丢弃 / 宽限日志）。客户端。 */
    public static final RVP_DebugFlag HMD = new RVP_DebugFlag("hmd", "头盔显示（IR 锁定日志）");

    /** 全部开关的有序只读列表，用于命令 {@code list} 展示。 */
    public static final List<RVP_DebugFlag> ALL = List.of(
            FUSE, JAM, CM, ECM, UAV, GUNNER, SPAWN, PHYSICS, LAUNCH_DEPLOY,
            CLIENT_STATE, HIT_UI, HUD, CCIP, HMD);

    private RVP_DebugFlags() {
    }

    /** 按名字查找开关；不存在时返回 {@code null}。 */
    public static RVP_DebugFlag get(String name) {
        if (name == null) {
            return null;
        }
        for (RVP_DebugFlag flag : ALL) {
            if (flag.getName().equalsIgnoreCase(name.trim())) {
                return flag;
            }
        }
        return null;
    }

    /**
     * 单个调试日志开关。
     * 所有访问经 volatile 保证跨线程（渲染线程 / 服务端主线程）可见。
     */
    public static final class RVP_DebugFlag {
        /** 命令中的开关名，全小写。 */
        private final String name;
        /** 中文说明，供 {@code list} 展示。 */
        private final String description;
        /** 是否开启日志输出；默认关闭。 */
        private volatile boolean enabled;

        RVP_DebugFlag(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}