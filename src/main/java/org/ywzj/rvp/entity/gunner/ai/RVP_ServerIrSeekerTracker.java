package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataIR;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.weapon.core.RVP_ServerIrSeekerArmedTable;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;

/**
 * [RVP] AI 炮手 IR 导引头 dwell tracker（服务端，2026-10-21，方案 D3）。
 *
 * <p>AI 炮手开火走 {@code WeaponUnit.shoot}（服务端），不经客户端发射门——若服务端
 * dispatchShots 对 IR 弹只认 armed 表，AI 不自算 dwell 就会永久拒射。本类在 AI 选定
 * 开火目标后每 tick 调 {@link #tickIrDwell}：目标切换清零、包线内累加、满
 * {@code seeker_lock_dwell_tick} 写服务端 armed 表（与玩家共用 D，不新增 AI 专用字段）。
 * AI 站本来就自动指向目标，实际延迟就是 D tick。</p>
 */
public final class RVP_ServerIrSeekerTracker {

    private RVP_ServerIrSeekerTracker() {}

    /** 单站单弹种的 AI 驻留状态：当前目标 id + 连续在包线内的 tick 数。 */
    private static final class DwellState {
        int targetId = -1;
        int dwellTicks;
    }

    private static final Map<WeaponUnit, Map<ResourceLocation, DwellState>> DWELL = new HashMap<>();

    /**
     * AI 开火前逐 tick 调用：包线内累加 dwell，满 D 写 {@link RVP_ServerIrSeekerArmedTable}
     * 并返回 true（允许 shoot）；目标切换清零；出包线清零；目标丢失返回 false。
     */
    public static boolean tickIrDwell(WeaponUnit root, RVP_WeaponBase rvpWeapon, Entity target) {
        RVP_WeaponData data = rvpWeapon.getData();
        ResourceLocation weaponId = data.getWeaponId();
        int dwellMax = RVP_GuidanceDataIR.resolveLockDwellTick(data.getGuidanceData());
        if (root == null || root.getVehicle() == null || target == null || !target.isAlive()) {
            clear(root, weaponId);
            return false;
        }
        DwellState state = DWELL.computeIfAbsent(root, u -> new HashMap<>())
                .computeIfAbsent(weaponId, id -> new DwellState());
        if (state.targetId != target.getId()) {
            // 目标切换 → 清零重算（方案 §8）。
            state.targetId = target.getId();
            state.dwellTicks = 0;
        }
        // 几何用"武器自己的站"，根站只作表键（与客户端 tracker / 收包校验同口径）。
        WeaponUnit geo = rvpWeapon.getWeaponUnit() != null ? rvpWeapon.getWeaponUnit() : root;
        if (RVP_IrLockHelper.isTargetWithinAcquireLimits(geo, target, data)) {
            state.dwellTicks++;
        } else {
            // 出包线（角度/距离/高度/LOS 任一不过）→ 清零。
            state.dwellTicks = 0;
        }
        if (state.dwellTicks >= dwellMax) {
            RVP_ServerIrSeekerArmedTable.arm(root, weaponId,
                    target.getId(), root.getVehicle().level().getGameTime());
            return true;
        }
        return false;
    }

    /** 清除指定站 + 弹种的 AI 驻留状态（目标丢失/载具移除时；授权表读取侧天然拒射）。 */
    public static void clear(WeaponUnit root, ResourceLocation weaponId) {
        Map<ResourceLocation, DwellState> byWeapon = DWELL.get(root);
        if (byWeapon != null) {
            byWeapon.remove(weaponId);
        }
    }
}
