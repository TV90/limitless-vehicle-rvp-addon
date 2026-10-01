package org.ywzj.rvp.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.ext.WeaponInfoExt;
import org.ywzj.vehicle.all.AllKeys;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.control.InputHandler;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.WeaponInfo;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.List;

/**
 * 武器级 {@code full_salvo} 免疫（{@code rvp_full_salvo_immune}，见 {@code WeaponInfoMixin}）。
 *
 * <p>背景：本体 {@code InputHandler.handleShoot}（private static，签名
 * {@code (AbstractVehicle, LocalPlayer)}）的 FULL_SALVO 分支是<b>白名单</b>——只遍历
 * {@code WeaponUnit.fullSalvoWeapons}、完全不读 {@code getCurrentWeapon()}，名单外武器在
 * full_salvo 站上左键<b>哑火</b>。本 Mixin 注入其 HEAD（cancellable），在
 * 「左键按下 + full_salvo 站 + 当前选中武器标记免疫」这一最窄条件下接管：只发射当前选中武器，
 * 并照抄本体紧随其后的副武器分支语义（{@code cancel} 会把它一起跳过，必须补上）。</p>
 *
 * <p>四道窄门任一不满足即 return，走本体原逻辑——未配置 {@code rvp_full_salvo_immune} 的
 * 存量载具（含全部非 full_salvo 站）开火行为零变化。本注入<b>只读 + 接管 + cancel</b>，
 * 不修改任何本体状态（如 {@code fullSalvoWeapons} 列表内容）。</p>
 *
 * <p>按键直接引用 {@code org.ywzj.vehicle.all.AllKeys.MAIN_WEAPON_SHOOT /
 * SECONDARY_WEAPON_SHOOT}（public static final KeyMapping）——本体 InputHandler 即以
 * {@code import static AllKeys.*} 使用它们，它们<b>不是 InputHandler 的字段</b>，
 * {@code @Shadow} 匹配不到（方案文档 §5.4 此处有误，已按实际声明修正）。</p>
 *
 * <p>免疫判定用 index 对齐：{@code WeaponUnit.indexedWeapons} 与
 * {@code WeaponUnitData.getWeapons()}（WeaponInfo 列表）在 {@code combineAndInit} 中
 * 每 {@code indexedWeapons.add} 一次就 {@code index += 1}，同序对齐（三分支一致）。
 * ⚠️ 已知边界：子站 weapons 为空的代理条目 {@code continue}、武器 id 解析失败的条目
 * 也不占 index——这类坏数据下后续条目的 index 会错位；错位后果良性（读错旗标、
 * 默认 false = 本体原行为），正常数据下严格对齐。入口均做 null / 越界保护。</p>
 */
@Mixin(value = InputHandler.class, remap = false)
public class InputHandlerFullSalvoImmuneMixin {

    @Inject(method = "handleShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$immuneFullSalvo(AbstractVehicle vehicle, LocalPlayer player, CallbackInfo ci) {
        // 窄门 a：主武器键（左键）未按下 → 本体本就不走主武器分支，直接放行
        if (!AllKeys.MAIN_WEAPON_SHOOT.isDown()) {
            return;
        }
        // 窄门 b：当前操作单元不是武器站 → 与本体判定同款表达式
        if (!(vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit weaponUnit)) {
            return;
        }
        // 窄门 c：非 full_salvo 站 → 本体本就走常规路径，免疫旗标无意义
        if (weaponUnit.getFiringMode() != WeaponUnitData.FiringMode.FULL_SALVO) {
            return;
        }
        // 窄门 d：当前选中武器为空，或它不免疫 → 走本体白名单原逻辑
        AbstractVehicleWeapon<?> current = weaponUnit.getCurrentWeapon().orElse(null);
        if (current == null || !ywzj_rvp$isFullSalvoImmune(weaponUnit, current)) {
            return;
        }
        // 免疫武器：只发它自己（常规「当前选中即发射」路径）
        current.doClientShoot();
        // ci.cancel() 会跳过本体紧随其后的副武器分支（SECONDARY_WEAPON_SHOOT 独立判定），
        // 照抄本体同段语义补上，保证副武器行为与本体完全一致
        if (AllKeys.SECONDARY_WEAPON_SHOOT.isDown()) {
            weaponUnit.getCurrentSecondaryWeapon().ifPresent(AbstractVehicleWeapon::doClientShoot);
        }
        ci.cancel();
    }

    /**
     * 武器实例 → JSON 条目的免疫旗标：按 {@code getIndex()} 对齐反查
     * {@code WeaponUnitData.getWeapons()} 列表（对齐依据与坏数据边界见类注释）。
     */
    @Unique
    private static boolean ywzj_rvp$isFullSalvoImmune(WeaponUnit weaponUnit, AbstractVehicleWeapon<?> current) {
        List<WeaponInfo> weaponInfos = weaponUnit.getData().getWeapons();
        if (weaponInfos == null) {
            return false;
        }
        int index = current.getIndex();
        if (index < 0 || index >= weaponInfos.size()) {
            return false;
        }
        return weaponInfos.get(index) instanceof WeaponInfoExt ext && ext.ywzj_rvp$fullSalvoImmune();
    }
}
