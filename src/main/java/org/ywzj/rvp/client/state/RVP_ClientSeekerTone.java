package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.audio.VehicleSound;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * IR 导引头锁定提示音（循环音，锁定建立即响、脱锁即停）。
 *
 * <p>移植自本体 {@code WeaponUnit#setLockedEntity} 中的 {@code IR_TRACK_ALARM} 分支。
 * 本体该分支的触发条件是<b>静态</b> {@code fire_control_sensor_type == IR}，
 * 而 RVP 载具部件只配置 {@code rf / eo / ccip}、从不配置 {@code IR}，
 * 且 RVP 还有 {@code fire_control_sensor_type_override} 动态覆盖，
 * 因此本体的判定在 RVP 武器上<b>永不成立</b>——这就是 RVP 红外弹没有导引头音的原因。</p>
 *
 * <p>本类改用 RVP 自身条件判定，与本体解耦：当前武器是 RVP 红外发射武器
 * （{@link RVP_IrLockHelper#isIrLaunchWeapon}）且导引头开启、且存在存活锁定目标时循环播放。
 * 由于本体分支永不触发，不存在与本体重叠播放成双响的风险。</p>
 *
 * <p>与 {@link RVP_ClientMissileTrackAlert} 的 {@code IR_ALERT} 方向相反：
 * 后者是“<b>你被</b>红外弹锁定”的告警，本类是“<b>你锁定</b>了敌人”的导引头音。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_ClientSeekerTone {

    private RVP_ClientSeekerTone() {}

    /** 当前正在播放的导引头音；null 表示未播放。音量/音高/衰减对齐本体 IR_TRACK_ALARM。 */
    private static VehicleSound tone;

    /** 每客户端 tick 调用（由 {@code RVP_ClientEvents#onClientTick} 驱动）。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            stop();
            return;
        }
        RVP_WeaponBase weapon = currentWeapon();
        if (weapon == null || !shouldPlay(weapon)) {
            stop();
            return;
        }
        // 声音可能已自行停止（目标实体移除等），此时重建而非复用失效实例
        if (tone != null && tone.isStopped()) {
            tone = null;
        }
        if (tone == null) {
            // 挂在本地玩家骑乘的载具上（对标本体 IR_TRACK_ALARM 挂 vehicle.getId()）：
            // VehicleSound 对"相机所骑载具"的声音强制钳制在离相机 8 格处——任何视角
            //（含第三人称远距）都必然可闻；挂在玩家 id 则第三人称相机 16~28 格会超出
            // 16 格线性可听半径而无声（2026-09-16 实机反馈修复）。
            AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
            int attachId = vehicle != null ? vehicle.getId() : player.getId();
            SoundEvent ev = resolveLockTone(weapon.getData());
            tone = new VehicleSound(ev, 1f, 1f, 1f, true, 0, false, false, attachId);
            tone.play();
        }
    }

    /** 停止导引头音（脱锁、切换武器、离车、退出世界时）。 */
    public static void stop() {
        if (tone != null) {
            tone.stop();
            tone = null;
        }
    }

    private static boolean shouldPlay(RVP_WeaponBase weapon) {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null) {
            return false;
        }
        WeaponUnit unit = lvp.getWeaponUnit();
        if (unit == null || !unit.isSeekerOn()) {
            return false;
        }
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        // IR HMD 使用本项目独立锁定通道作为唯一真值源：PL-10 等 NONE 传感器武器站
        // 会被本体火控每 Tick 清掉共享锁，但 HMD 自身仍然有效；非 HMD 路径继续读本体共享锁。
        Entity locked = hmdState.isIrHmd() ? hmdState.getLockedEntity() : unit.getLockedEntity();
        if (locked == null || !locked.isAlive()) {
            return false;
        }
        return RVP_IrLockHelper.isIrLaunchWeapon(weapon.getData());
    }

    /** 取当前武器站的主武器（RVP 武器），非 RVP 武器返回 null。 */
    @Nullable
    private static RVP_WeaponBase currentWeapon() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null) {
            return null;
        }
        WeaponUnit unit = lvp.getWeaponUnit();
        if (unit == null) {
            return null;
        }
        return RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
    }

    /**
     * 解析本武器可选的锁定音：武器 data 配了 {@code lock_tone_sound} 且对应
     * {@code SoundEvent} 已注册则用之；否则回退全局默认 {@code RVP_Sounds.IR_TRACK_ALARM}。
     */
    private static SoundEvent resolveLockTone(RVP_WeaponData data) {
        ResourceLocation rl = data.getLockToneSound();
        if (rl != null) {
            SoundEvent ev = ForgeRegistries.SOUND_EVENTS.getValue(rl);
            if (ev != null) {
                return ev;
            }
        }
        return RVP_Sounds.IR_TRACK_ALARM.get();
    }
}
