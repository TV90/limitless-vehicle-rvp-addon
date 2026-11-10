package org.ywzj.rvp.event;

import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.ai.RVP_ServerIrSeekerTracker;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession;
import org.ywzj.rvp.util.RVP_ServerVehicleIndex;
import org.ywzj.rvp.weapon.core.RVP_FollowParentRotationHelper;
import org.ywzj.rvp.weapon.core.RVP_ServerIrSeekerArmedTable;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.rvp.weapon.core.RVP_WeaponSwitchSyncHelper;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * [RVP] 运行期侧表统一清理入口。
 *
 * <p>这些表原本按 WeaponUnit / 玩家 UUID / 载具实体 id 记录运行状态，若只依赖弱引用，
 * 载具卸载后仍可能因为 value 反向引用 key、或维度卸载不触发单实体 leave 而残留。
 * 本类在停服、关卡卸载、实体离场、玩家断线四个出口统一清理，保证换世界/长局不会
 * 把旧 ClientLevel / ServerLevel 对象图钉在静态表里。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_StateCleanupEvents {

    private RVP_StateCleanupEvents() {
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clearAll();
        // 载具索引只在 join 时登记，停服才做全量清空；普通维度卸载改用 clearLevel。
        RVP_ServerVehicleIndex.clearAll();
    }

    /**
     * 关卡卸载：服务端关卡关闭/单人退出世界时触发。
     *
     * <p>客户端关卡卸载由 {@code RVP_ClientStateCleanupEvents} 处理；这里不区分
     * 维度逐个清理，因为本批表都不带维度键，清空后下一 tick 会按当前世界自然重建。</p>
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        // 只清卸载的那个维度；其它维度已加载的载具仍要保留在索引里。
        // 这里不做全量 clearAll：服务端单维度卸载不代表世界结束，GPS 点位、
        // 空爆测距等用户设置不能因一次维度切换被清掉；世界退出由 ServerStopping 兜底。
        if (event.getLevel() instanceof Level level) {
            RVP_ServerVehicleIndex.clearLevel(level);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        // 累计进度差分缓存只是"上次已推送快照"的去重表，任何离开原因（含区块卸载/
        // 跨维度）清掉都安全：重新加载后如有累计伤害会整份补发，不收走用户设置。
        RVP_VehicleHitboxFactorManager.clearBoneDamageProgressCache(vehicle);
        // 只处理真正被销毁/丢弃的载具；区块卸载（UNLOADED_TO_CHUNK）和跨维度
        // 传送（CHANGED_DIMENSION）不能清空用户设置的空爆测距。
        Entity.RemovalReason reason = vehicle.getRemovalReason();
        if (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED) {
            RVP_AirburstRangeStore.clearVehicle(vehicle.getId());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // 断线后照射会话与 GPS 点位都不再有恢复意义。
        RVP_SaclosOperatorSession.clear(event.getEntity().getUUID());
        GPSTargetManager.remove(event.getEntity().getUUID());
    }

    /**
     * 清空全部非资源类运行期侧表。
     *
     * <p>公开给客户端清理入口复用：单人模式下客户端与服务端同 JVM，客户端卸载也该
     * 一并清掉这些共享静态表。</p>
     */
    public static void clearAll() {
        RVP_FollowParentRotationHelper.clearAll();
        RVP_WeaponSwitchSyncHelper.clearAll();
        RVP_WeaponLockStateTable.clearAll();
        RVP_ServerIrSeekerTracker.clearAll();
        RVP_ServerIrSeekerArmedTable.clearAll();
        RVP_SaclosOperatorSession.clearAll();
        GPSTargetManager.clearAll();
        RVP_AirburstRangeStore.clearAll();
        RVP_VehicleHitboxFactorManager.clearBoneDamageProgressCacheAll();
    }
}
