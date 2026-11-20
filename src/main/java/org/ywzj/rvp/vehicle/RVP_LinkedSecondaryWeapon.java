package org.ywzj.rvp.vehicle;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;

import java.util.List;

/** 当副槽以子武器站为来源时，随子站的改装选择转发开火和弹药状态。 */
final class RVP_LinkedSecondaryWeapon extends VehicleWeaponAgent {
    RVP_LinkedSecondaryWeapon(AbstractVehicle vehicle, WeaponUnit mount, int index) {
        // 沿用本体代理的武器站、索引与存档规则，选项和备弹由子站持有。
        super(vehicle, mount, index);
    }

    @Nullable
    private AbstractVehicleWeapon<?> selectedWeapon() {
        // 读取改装台切换的同一个武器索引，挂载组也由本体解析。
        return getWeaponUnit().getCurrentWeapon().orElse(null);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean doClientShoot() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 走实际武器的门控和发包入口，开火回包据其槽位播放枪声。
        return weapon != null && weapon.doClientShoot();
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, @Nullable LivingEntity operator) {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 服务端直接调用副槽时也使用子站的发射实现。
        return weapon != null && weapon.shoot(aimContexts, operator);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void onClientFire() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 回包仍引用代理时，让实际武器执行原有声音和动画回调。
        if (weapon != null) {
            weapon.onClientFire();
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public SoundEvent getFireSound() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 分别选择轻、重改件时，界面与声音处理器读取其各自的声音事件。
        return weapon == null ? null : weapon.getFireSound();
    }

    @Override
    public BaseVehicleWeaponData getData() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 将显示与弹药筛选所用的数据关联到当前选项。子站尚未装配时沿用代理默认数据。
        return weapon == null ? super.getData() : weapon.getData();
    }

    @Override
    public Component getDisplayName() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 界面读取所选改件的显示名。
        return weapon == null ? super.getDisplayName() : weapon.getDisplayName();
    }

    @Override
    public int getRemainAmmo() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 副武器 HUD 直接读取此槽，余弹来自实际武器。
        return weapon == null ? 0 : weapon.getRemainAmmo();
    }

    @Override
    public int getMaxCapacity() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 沿用实际武器和挂点的备弹上限。
        return weapon == null ? 0 : weapon.getMaxCapacity();
    }

    @Override
    public boolean hasAmmo() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 沿用当前武器的射击备弹判定。
        return weapon != null && weapon.hasAmmo();
    }

    @Override
    public int getReloadTime() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 装填进度与当前改件共用一份状态。
        return weapon == null ? 0 : weapon.getReloadTime();
    }

    @Override
    public boolean isReloading() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 读取当前改件的装填状态。
        return weapon != null && weapon.isReloading();
    }

    @Override
    public boolean canReload() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 用实际武器判断装填资格和弹药来源。
        return weapon != null && weapon.canReload();
    }

    @Override
    public void startReload() {
        AbstractVehicleWeapon<?> weapon = selectedWeapon();
        // 把副槽的装填请求交给实际武器。
        if (weapon != null) {
            weapon.startReload();
        }
    }

    @Override
    public void tick() {
        // 子站的本体武器循环负责推进冷却和装填。
    }
}
