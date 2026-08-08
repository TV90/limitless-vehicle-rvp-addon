package org.ywzj.rvp.debug;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/**
 * RVP 弹体生命周期监测器的 Forge 事件桥。
 *
 * <p>实体自身 tick 无法观察停止追踪和区块卸载，因此本类负责把世界级事件转发给
 * {@link RVP_ProjectileLifecycleDebug}。所有处理均限制在服务端 RVP 弹体。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ProjectileLifecycleDebugEvents {
    /** 纯静态事件订阅类，禁止实例化。 */
    private RVP_ProjectileLifecycleDebugEvents() {}

    /**
     * 接收实体停止服务端追踪事件。
     *
     * <p>RemovalReason 非空时可权威判断 discard/kill/卸载等终止原因；为空时只能确认没有权威移除，
     * 监测器会再结合区块 loaded/entity-ticking 状态给出明确标注为 STATE_INFERENCE 的冻结原因。</p>
     *
     * @param event Forge 实体离开世界/停止追踪事件
     */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        // 排除客户端事件，避免同一弹体生命周期被服务端和客户端重复记录。
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // 只监测类型化 RVP 弹体，不处理其他模组和原版实体。
        if (!(event.getEntity() instanceof RVP_BaseBullet projectile)) {
            return;
        }
        // RemovalReason 是否为空的状态机分流集中在监测器内，事件桥不复制业务判断。
        RVP_ProjectileLifecycleDebug.noteLeftLevel(serverLevel, projectile);
    }

    /**
     * 在每个服务端 tick 结束阶段驱动停滞 watchdog。
     *
     * @param event Forge 服务端 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 只在 END 阶段扫描一次，避免 START/END 重复计时和遍历。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // watchdog 内部还会按 20 tick 间隔限流，并且不会强制加载区块。
        RVP_ProjectileLifecycleDebug.watchdogTick(event.getServer());
    }
}
