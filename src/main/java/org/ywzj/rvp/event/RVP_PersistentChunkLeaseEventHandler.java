package org.ywzj.rvp.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.EntityUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * [RVP] 舰船持久区块保活（2026-10-03，载具 JSON 顶层 {@code rvp_persistent_chunk_lease: true}）。
 *
 * <p><b>根因</b>：RVP 远距载具租约提交的是本体 {@code TicketType.POST_TELEPORT} 票据，
 * 超时仅 <b>5 tick</b>；租约只在"新路径区块"时提交，静止载具路径退化为单格 ⇒ 首轮之后
 * 不再刷新 ⇒ 票过期 ⇒ 区块卸载 ⇒ 服务端丢失该实体 ⇒ 超视距雷达永远扫不到（与机型无关，
 * 与"载具是否运动"有关）。</p>
 *
 * <p><b>手段</b>：与本体 {@code AbstractVehicle} 的 {@code uav} 分支同一行代码——
 * 每 tick 对标记载具调用 {@link EntityUtil#keepChunkLoaded}（重复提交同 key 票即刷新计时，
 * 票永不过期）。<b>刻意不设置 {@code uav} 标志</b>：uav 另有 5 处副作用（隐藏乘客渲染 /
 * 取消过载计算 / 保留 FakePlayer / 可被 UavControllerItem 劫持 / 自身保活），本字段只取
 * "自身保活"这一条。</p>
 *
 * <p><b>硬约束：必须每 tick 调用，禁止加任何节流/计数器</b>——POST_TELEPORT 票超时只有
 * 5 tick，节流超过 4 tick 就会漏保。</p>
 *
 * <p><b>固有局限（只能保持、不能唤醒）</b>：本 handler 通过
 * {@code level.getEntities().getAll()} 遍历，只能看到<b>已加载</b>的载具——载具被放置的
 * 瞬间必然加载、即刻进入保活；但服务器重启后若该区域无人靠近，载具尚未实例化，保活链断裂，
 * 仍需一次"接触"才能重新进入保活（跨重启增强见方案文档 §5.2，本版不含）。</p>
 *
 * <p><b>无状态、零清理</b>：载具被移除后不再被遍历到 ⇒ 票自然过期 ⇒ 区块正常卸载。
 * 不对 {@code isDestroyed()} 做特判（与本体 uav 分支一致）：残骸也保持加载，供既有清理
 * 逻辑（如 {@code RVP_LinkedUavEventHandler} 残骸过期）正常遍历到并按时清理。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_PersistentChunkLeaseEventHandler {

    private RVP_PersistentChunkLeaseEventHandler() {
    }

    /** ServerTickEvent 天然只在服务端触发，无需额外 isClientSide 判断。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            tickLevel(level);
        }
    }

    /** 对本维度内启用了持久保活的载具逐个提交区块票（每 tick，无节流）。 */
    private static void tickLevel(ServerLevel level) {
        // 先快照：遍历中实体列表可能变动（照 RVP_LinkedUavEventHandler 的既有做法）
        List<AbstractVehicle> vehicles = new ArrayList<>();
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof AbstractVehicle vehicle) {
                vehicles.add(vehicle);
            }
        }
        for (AbstractVehicle vehicle : vehicles) {
            if (RVP_VehicleExtendedConfigManager.isPersistentChunkLease(vehicle)) {
                // 调用本体 public API（与本体 uav 分支同一行代码）：提交 POST_TELEPORT 票，
                // 内部提交两次（当前位与沿视线前探位，静止时同格幂等）
                EntityUtil.keepChunkLoaded(vehicle, vehicle.position());
            }
        }
    }
}
