package org.ywzj.rvp.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** HITL 额外加载的全服预算；只由服务端读取，不能由武器配置提高上限。 */
public final class RVP_HitlRemoteConfig {
    /** 独立配置规范，避免客户端画质改变服务端预算。 */
    private static final ForgeConfigSpec SPEC;
    /** 同一服务器允许的远程观察会话数。 */
    private static final ForgeConfigSpec.IntValue SESSIONS;
    /** 单会话最大观察半径，单位区块。 */
    private static final ForgeConfigSpec.IntValue RADIUS;
    /** 配置武器可采用的最大控制距离，单位格。 */
    private static final ForgeConfigSpec.IntValue RANGE;
    /** 全服每 tick 新增的区块中心票据数。 */
    private static final ForgeConfigSpec.IntValue LOAD;
    /** 全服每 tick 首次发送的完整区块包数。 */
    private static final ForgeConfigSpec.IntValue SEND;
    /** 已请求但尚未就绪的区块中心数量上限。 */
    private static final ForgeConfigSpec.IntValue PENDING;
    /** 每个观察者最多额外跟踪的实体数，含活动导弹。 */
    private static final ForgeConfigSpec.IntValue ENTITIES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SESSIONS = builder.comment("全服同时活动的 HITL 远程观察会话上限；武器还须显式 enabled=true。")
                .defineInRange("max_sessions", 4, 1, 16);
        RADIUS = builder.comment("每个导弹相机的最大区块半径；方形窗口包含 (2r+1)^2 个区块。")
                .defineInRange("max_radius_chunks", 8, 2, 12);
        RANGE = builder.comment("hitl_max_control_dist 的服务器上限，单位格；不等于相机窗口大小。")
                .defineInRange("max_control_distance_blocks", 4096, 16, 16384);
        LOAD = builder.comment("全服每 tick 新增票据中心数。Minecraft 邻域依赖可能加载更多实际区块。")
                .defineInRange("new_chunk_tickets_per_tick", 4, 1, 16);
        SEND = builder.comment("全服每 tick 首次发送的完整区块包数；原版方块/光照差量继续正常同步。")
                .defineInRange("chunk_packets_per_tick", 8, 1, 32);
        PENDING = builder.comment("尚未就绪的票据中心上限；生成跟不上时暂停新增请求。")
                .defineInRange("max_pending_chunks", 64, 4, 256);
        ENTITIES = builder.comment("单观察者额外同步实体上限，优先导弹与近处目标。")
                .defineInRange("max_entities_per_session", 256, 16, 1024);
        SPEC = builder.build();
    }

    private RVP_HitlRemoteConfig() {}

    /** 注册配置文件；所有运行读取只在服务端会话管理器执行。 */
    public static void register(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, SPEC, "ywzj_rvp-hitl-remote.toml");
    }

    public static int sessions() { return SPEC.isLoaded() ? SESSIONS.get() : 4; }
    public static int radius() { return SPEC.isLoaded() ? RADIUS.get() : 8; }
    public static int range() { return SPEC.isLoaded() ? RANGE.get() : 4096; }
    public static int load() { return SPEC.isLoaded() ? LOAD.get() : 4; }
    public static int send() { return SPEC.isLoaded() ? SEND.get() : 8; }
    public static int pending() { return SPEC.isLoaded() ? PENDING.get() : 64; }
    public static int entities() { return SPEC.isLoaded() ? ENTITIES.get() : 256; }
}
