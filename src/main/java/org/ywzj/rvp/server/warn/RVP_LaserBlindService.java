package org.ywzj.rvp.server.warn;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CLaserBlind;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 激光致盲服务（服务端，2026-09-17）。
 *
 * <p>{@code rvp:laser} 命中玩家 / gunner / 其骑乘的载具时累计受击次数；在
 * {@code blind_hit_window_tick} 窗口内攒满 {@code blind_hit_count} 次触发致盲——
 * <b>触发时命中计数清零、窗口重开、重新累计</b>（再次攒满才再次致盲并刷新时长；
 * 超窗未达标同样清零重计）。</p>
 *
 * <ul>
 *   <li>玩家致盲：向其客户端发 {@link S2CLaserBlind}（白色闪光滤镜，前段全亮尾段渐隐）；</li>
 *   <li>gunner 致盲：服务端致盲状态表标记（{@link #isBlinded}）——期间视觉索敌失效，
 *       仅雷达制导/雷达中段的 SARH/ARH/AIR 导弹可用（消费点
 *       {@code GunnerWeaponSuitability.canSelectForTarget}，且要求载具保有可用雷达）；</li>
 *   <li>命中载具：致盲其全部玩家/gunner 乘员；</li>
 *   <li>默认关闭：{@code blind_hit_count} 缺省 0，未显式配置的激光不累计不触发。</li>
 * </ul>
 *
 * <p>服务端逻辑，无客户端类型引用。</p>
 */
public final class RVP_LaserBlindService {

    /** 命中累计窗口侧表：目标 UUID → 计数与窗口起点。 */
    private static final Map<UUID, HitWindow> HIT_WINDOWS = new HashMap<>();
    /** gunner 致盲状态侧表：gunner UUID → 致盲截止 game tick。 */
    private static final Map<UUID, Long> GUNNER_BLIND_UNTIL = new HashMap<>();

    private record HitWindow(int count, long windowStartTick) {
    }

    private RVP_LaserBlindService() {
    }

    /**
     * 激光命中累计入口（{@code RVP_LaserWeapon.shoot} 命中任意实体时调用）。
     *
     * @param shooterVehicle 发射载具（用于载具命中时致盲其乘员；canHit 已排除射手与宿主）
     * @param hitEntity      本次射线命中的实体（玩家/gunner/载具/其它——其它类型忽略）
     * @param hitCount       触发致盲所需命中次数（≤0 表示该激光未启用致盲，直接短路）
     * @param windowTick     累计窗口（tick）
     * @param durationTick   致盲时长（tick）
     */
    public static void onLaserHit(@Nullable AbstractVehicle shooterVehicle, Entity hitEntity,
                                  int hitCount, int windowTick, int durationTick) {
        if (hitCount <= 0 || durationTick <= 0 || windowTick <= 0
                || hitEntity == null || !hitEntity.isAlive()
                || hitEntity instanceof AbstractVehicle vehicle && vehicle.isDestroyed()) {
            return;
        }
        UUID key = hitEntity.getUUID();
        long now = hitEntity.level().getGameTime();
        HitWindow window = HIT_WINDOWS.get(key);
        if (window == null || now - window.windowStartTick() > windowTick) {
            window = new HitWindow(1, now);
        } else {
            window = new HitWindow(window.count() + 1, window.windowStartTick());
        }
        if (window.count() >= hitCount) {
            // 触发致盲：命中计数清零、窗口重开、重新累计（再次攒满才再次致盲并刷新时长）
            HIT_WINDOWS.remove(key);
            applyBlind(shooterVehicle, hitEntity, now, durationTick);
        } else {
            HIT_WINDOWS.put(key, window);
        }
    }

    /** gunner 是否处于激光致盲状态（消费点：GunnerWeaponSuitability 武器可选性判定）。 */
    public static boolean isBlinded(GunnerEntity gunner) {
        Long until = GUNNER_BLIND_UNTIL.get(gunner.getUUID());
        if (until == null) {
            return false;
        }
        if (gunner.level().getGameTime() >= until) {
            GUNNER_BLIND_UNTIL.remove(gunner.getUUID());
            return false;
        }
        return true;
    }

    /** 触发致盲：命中玩家/gunner → 致盲自身；命中载具 → 致盲全部玩家/gunner 乘员。 */
    private static void applyBlind(@Nullable AbstractVehicle shooterVehicle, Entity hitEntity,
                                   long now, int durationTick) {
        long until = now + durationTick;
        if (hitEntity instanceof Player player) {
            blindPlayer(player, durationTick);
            return;
        }
        if (hitEntity instanceof GunnerEntity gunner) {
            GUNNER_BLIND_UNTIL.put(gunner.getUUID(), until);
            return;
        }
        if (hitEntity instanceof AbstractVehicle vehicle) {
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger instanceof Player player) {
                    blindPlayer(player, durationTick);
                } else if (passenger instanceof GunnerEntity gunner) {
                    GUNNER_BLIND_UNTIL.put(gunner.getUUID(), until);
                }
            }
        }
    }

    /** 向玩家客户端发致盲滤镜同步包（客户端白色闪光，前段全亮尾段渐隐）。 */
    private static void blindPlayer(Player player, int durationTick) {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            RVP_Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER
                    .with(() -> serverPlayer), new S2CLaserBlind(durationTick));
        }
    }
}
