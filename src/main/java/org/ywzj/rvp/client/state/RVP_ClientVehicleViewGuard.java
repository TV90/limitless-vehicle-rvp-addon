package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.cockpit.RVP_CockpitDiag;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 客户端观瞄视角残留守卫。
 *
 * <p>载具被移除（/kill、被击毁、区块卸载）或玩家异常离车后，若迟迟收不到母车的
 * {@code ServerVehicleSeatsChange} 座位同步（车辆已不存在的正常路径不会补发），
 * {@link LocalVehiclePlayer#vehicle} 会一直指向已不存在的车辆，导致 {@code onVehicle()==true}，
 * 出现"人不在载具上却仍有 zbl08a 观瞄视角"的残留。</p>
 *
 * <p>这里在客户端每 tick 核对：本地缓存的车辆与玩家实际坐骑不一致且持续超过宽限期时，
 * <b>且缓存载具确实已失效</b>（被移除/死亡/已被甩开超过 {@link #STALE_RANGE} 格）时，
 * 才强制 {@link LocalVehiclePlayer#toSeat} 清空观瞄状态（本体 {@code checkState} 下一 tick
 * 会把 viewType 复位为第三人称）。宽限期覆盖 M 键无人机↔母车切换期间服务端 startRiding
 * 先于座位同步到达的正常窗口（约 1-2 tick）。</p>
 *
 * <p><b>2026-10-15 加固</b>：原实现"只要不一致就清"过于激进——实测开 j16d 时旁边 Buk-M3
 * 开火会让原版坐骑关系短暂断裂约 11 秒，载具本体却仍在原地、玩家也确实在机上；此时清空
 * 会让座舱屏内容源降级（详见 {@code RVP_CockpitScreenManager}）。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientVehicleViewGuard {

    /** 缓存车辆与玩家实际坐骑不一致的宽限 tick 数（M 键切换正常窗口约 1-2 tick，留足余量）。 */
    private static final int MISMATCH_GRACE_TICKS = 40;

    /**
     * "载具还在身边"的判定距离（格）：缓存载具仍存活且距玩家不超过该值时，
     * 视为"原版坐骑关系短暂断裂"而非"玩家已离车"——此时不清空本体状态。
     * 与 {@code RVP_CockpitScreenManager.SCREEN_FALLBACK_RANGE} 同口径。
     */
    private static final double STALE_RANGE = 64.0;

    private static int mismatchTick;

    private RVP_ClientVehicleViewGuard() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
        if (instance == null) {
            return;
        }
        AbstractVehicle cached = instance.vehicle;
        if (cached == null) {
            mismatchTick = 0;
            return;
        }
        Entity actualRide = player.getVehicle();
        if (actualRide == cached) {
            mismatchTick = 0;
            return;
        }
        if (++mismatchTick > MISMATCH_GRACE_TICKS) {
            mismatchTick = 0;
            // 2026-10-15 修复：不能"只要不一致就清空"。
            // 实测（开 j16d、旁边 Buk-M3 开火）：原版坐骑关系会短暂断裂约 11 秒
            // （player.getVehicle() 既不是本体的缓存车，也不满足本类依赖的回写条件），
            // 而载具本体好好地停在原地、玩家也确实还在机上。一刀清空 ⇒ onVehicle()=false
            // ⇒ 屏内容源降级成"旁边那台没雷达/没告警接收器的车" ⇒ 三屏空画面（用户观感=闭屏），
            // 且本体 checkState 会把 viewType 复位成第三人称。
            // 因此只在"缓存载具确实失效"（被移除/死亡/已被甩开）时才清——这才是本类
            // 原本要治的"人不载具却残留观瞄视角"；车还活着且就在身边时保持状态不动。
            double staleSq = STALE_RANGE * STALE_RANGE;
            boolean vehicleGone = cached.isRemoved() || !cached.isAlive()
                    || cached.getBoundingBox().distanceToSqr(player.position()) > staleSq;
            if (vehicleGone) {
                RVP_CockpitDiag.log("观瞄守卫：缓存载具已失效（removed=" + cached.isRemoved()
                        + " alive=" + cached.isAlive() + "）⇒ 清空 LocalVehiclePlayer（原版坐骑="
                        + actualRide + "）");
                instance.toSeat(null, null);
            } else {
                RVP_CockpitDiag.log("观瞄守卫：原版坐骑与缓存载具(" + cached.getVehicleId()
                        + ")不一致但载具仍在身边 ⇒ 保持不清空（原版坐骑=" + actualRide + "）");
            }
        }
    }
}
