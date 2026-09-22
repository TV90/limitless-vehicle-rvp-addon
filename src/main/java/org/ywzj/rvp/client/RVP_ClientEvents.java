package org.ywzj.rvp.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Items;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;
import org.ywzj.rvp.client.gui.RVP_RocketCcipOverlay;
import org.ywzj.rvp.client.gui.RVP_HmdOverlay;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.rvp.client.screen.RVP_TacticalMapScreen;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.client.shader.RVP_CrtUiLiteHandler;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.client.state.RVP_ClientLoiterState;
import org.ywzj.rvp.client.state.RVP_ClientHbmMissileState;
import org.ywzj.rvp.client.state.RVP_ClientExternalRadarState;
import org.ywzj.rvp.client.state.RVP_ClientRemoteAmmoState;
import org.ywzj.rvp.client.state.RVP_ClientSeekerTone;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ClientSaclosState;
import org.ywzj.rvp.client.state.RVP_ClientTacticalRevealState;
import org.ywzj.rvp.client.firesupport.RVP_ClientFireSupportState;
import org.ywzj.rvp.client.state.RVP_ClientGunnerVehicleState;
import org.ywzj.rvp.client.state.RVP_ArtilleryFireControlState;
import org.ywzj.rvp.client.state.RVP_RocketCcipState;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.client.laser.RVP_ClientLaserDriver;
import org.ywzj.rvp.client.state.RVP_ClientBulletHitDebugState;
import org.ywzj.rvp.client.state.RVP_ClientHitIndicatorState;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.countermeasure.network.C2SFireCountermeasure;
import org.ywzj.rvp.network.C2SDeployDeployableUav;
import org.ywzj.rvp.network.C2SSwitchDeployableUav;
import org.ywzj.rvp.network.C2SToggleUavLoiter;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.config.RVP_DeployableUavConfig;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.rvp.util.RVP_CcipUtil;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.vehicle.all.AllKeys;
import org.ywzj.vehicle.client.shader.CrtHandler;
import org.ywzj.vehicle.client.shader.ThermalHandler;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.CcipUtil;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;
import org.ywzj.rvp.network.C2SWingSweepToggle;
import org.ywzj.rvp.wingsweep.RVP_WingSweepState;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_ClientEvents {

    private static int ywzj_rvp$markerRefreshTick;
    private static final List<VehicleMarker> ywzj_rvp$vehicleMarkers = new ArrayList<>();
    private static boolean ywzj_rvp$artilleryFireKeyDown;

    private record VehicleMarker(int vehicleId, int argb) {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        // 调用本项目客户端 profile 状态：收到 profile 快照后重建动态终端创造栏变体。
        RVP_ClientFireSupportState.INSTANCE.clientTick();

        ywzj_rvp$syncLocalVehiclePlayerSeat();

        RVP_ClientBulletHitDebugState.clientTick();
        RVP_ClientHitIndicatorState.clientTick();
        RVP_ClientHbmMissileState.clientTick();
        RVP_ClientRemoteAmmoState.clientTick();
        RVP_ClientExternalRadarState.clientTick();
        RVP_ClientTacticalRevealState.clientTick();
        RVP_ClientGunnerVehicleState.clientTick();
        // 调试：确认本体 RWR 覆盖层读取的 warningReceiver.targets 里是否有伪造 RADAR_LOCK
        if (org.ywzj.rvp.client.state.RVP_ClientEcmDebugState.isDebugOn()
                && player.tickCount % 20 == 0) {
            org.ywzj.rvp.client.state.RVP_ClientEcmDebugState.appendLog(
                    org.ywzj.rvp.client.RVP_ClientRwrProbe.probe());
        }

        if (mc.level != null) {
            RVP_TacticalMapCache.processChunkUpdates(mc.level, player.getX(), player.getZ(), 6);
            RVP_TacticalMapCache.uploadDirtyTextures();
        }

        while (RVP_Keys.DEBUG_OVERLAY.consumeClick()) {
            boolean on = org.ywzj.rvp.client.RVP_DebugOverlayState.toggle();
            player.displayClientMessage(
                    Component.translatable(on ? "message.ywzj_rvp.debug_overlay.on" : "message.ywzj_rvp.debug_overlay.off"),
                    true);
        }

        // 干扰物发射（H 热焰弹/烟雾 / LeftAlt 箔条）：向服务端发送齐射请求
        while (RVP_Keys.FIRE_FLARE.consumeClick()) {
            ywzj_rvp$fireCountermeasure(RVP_EnumCountermeasureType.FLARE);
        }
        while (RVP_Keys.FIRE_CHAFF.consumeClick()) {
            ywzj_rvp$fireCountermeasure(RVP_EnumCountermeasureType.CHAFF);
        }
        while (RVP_Keys.FIRE_ECM.consumeClick()) {
            ywzj_rvp$fireEcm();
        }
        while (RVP_Keys.FIRE_SMOKE.consumeClick()) {
            ywzj_rvp$fireCountermeasure(RVP_EnumCountermeasureType.SMOKE);
        }

        // 快速维修（G）：乘坐在已配置 maintenance 的载具内按键 → 服务端权威触发
        while (RVP_Keys.USE_MAINTENANCE.consumeClick()) {
            LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
            if (lvp != null && lvp.vehicle != null && lvp.onVehicle()) {
                RVP_Network.CHANNEL.sendToServer(
                        new org.ywzj.rvp.maintenance.network.C2SUseMaintenance(lvp.vehicle.getId()));
            }
        }

        // [RVP] 可变后掠翼手动切换：复用本体 FUNCTIONAL ↑/↓（边沿检测 consumeClick，
        // 与本体 InputHandler 的 isDown 轮询互不干扰）。守卫：驾驶员 + 无矢量固定翼 +
        // 双隐藏部件存在；不满足时仅吞掉点击（白按无提示，见方案 §1.4）
        while (AllKeys.FUNCTIONAL_UP.consumeClick()) {
            LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
            if (ywzj_rvp$wingSweepGuard(lvp)) {
                RVP_Network.CHANNEL.sendToServer(new C2SWingSweepToggle(lvp.vehicle.getId(), true));
            }
        }
        while (AllKeys.FUNCTIONAL_DOWN.consumeClick()) {
            LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
            if (ywzj_rvp$wingSweepGuard(lvp)) {
                RVP_Network.CHANNEL.sendToServer(new C2SWingSweepToggle(lvp.vehicle.getId(), false));
            }
        }

        // HMD 模式切换：STT 状态下按 5 键先取消 STT 再进入 HMD
        while (RVP_Keys.HMD_TOGGLE.consumeClick()) {
            LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
            // [RVP] 真守卫：仅在载具且有武器站时 HMD 键才有意义。原判断 instance == null
            // 对单例恒为假，导致单兵按 5（原版快捷栏键）也走 else 分支误弹 "HMD 关闭"。
            if (lvp == null || !lvp.onVehicle() || lvp.getWeaponUnit() == null) {
                continue;
            }
            RVP_ClientHmdState hmd = RVP_ClientHmdState.getInstance();
            if (hmd.isRadarHmd()) {
                // 按 5 只切换雷达 HMD；当前武器自动启用的 IR HMD 必须继续工作。
                hmd.disableRadarHmd();
                player.displayClientMessage(
                        Component.translatable("message.ywzj_rvp.hmd.off"), true);
            } else {
                // [RVP] 无 HMS 雷达时完全静默：不进 HMD、不清 STT 锁定、不弹提示。
                // 原代码 toggle() 失败仍弹 "hmd.off"，且在进入 HMD 前就误清了 STT 锁定。
                if (hmd.getRadarHmdUnit(lvp.getWeaponUnit()) == null) {
                    continue;
                }
                // 检查是否有 STT 锁定
                WeaponUnit weaponUnit = lvp.getWeaponUnit();
                RadarUnit radar = RVP_RadarRoleHelper.getLockedRadar(weaponUnit);
                if (radar != null && radar.getLockedEntity() != null) {
                    RVP_RadarRoleHelper.clearAllRadarLocks(weaponUnit);
                    weaponUnit.setLockedEntity(null);
                }
                if (RVP_ExternalRadarLinkHelper.hasClientExternalLockState(lvp.vehicle,
                        mc.level != null ? mc.level.dimension().location() : null)) {
                    RVP_ExternalRadarLinkHelper.clearClientLockRequest(weaponUnit);
                }
                boolean on = hmd.toggleRadarHmd();
                // [RVP] 仅在 HMD 真正开启时提示；toggle 失败（理论上已被上方守卫挡住）静默
                if (on) {
                    player.displayClientMessage(
                            Component.translatable("message.ywzj_rvp.hmd.on"), true);
                }
            }
        }
        while (RVP_Keys.TOGGLE_LASER_DESIGNATION.consumeClick()) {
            RVP_ClientSaclosState.toggleVehicleLaser(player);
        }

        // 火控稳定器切换（T 键）：RF 机枪火控稳定模式（STABLE/SEMI_AUTO/OFF）+ GPS 单点/多点
        // 模式。T 键为本体未占用的键，无需拦截本体处理，故以 ClientTick 轮询公共 API 实现，
        // 替代原 InputHandler.handleVehicleAction mixin 注入。
        while (RVP_Keys.FIRE_CONTROL_STABILIZER.consumeClick()) {
            LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
            if (lvp == null || lvp.getPlayer() == null || !lvp.onVehicle()) {
                continue;
            }
            WeaponUnit weaponUnit = lvp.getWeaponUnit();
            if (weaponUnit == null) {
                continue;
            }
            if (RVP_ClientGPSUtil.tryHandleModeToggleKey()) {
                continue;
            }
            RVP_FireControlStabilizerState.tryHandleToggleKey(weaponUnit);
        }

        ywzj_rvp$applyScopeOverrides();

        boolean artilleryMapPassthrough = mc.screen instanceof RVP_TacticalMapScreen screen
                && screen.allowsVehicleInputPassthrough();
        if (mc.screen != null && !artilleryMapPassthrough) {
            return;
        }
        RVP_ArtilleryFireControlState.tick(mc);
        boolean artilleryFireKeyDown = mc.screen instanceof RVP_TacticalMapScreen artilleryScreen
                && artilleryScreen.isArtilleryMode()
                && InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE);
        if (artilleryFireKeyDown && !ywzj_rvp$artilleryFireKeyDown
                && LocalVehiclePlayer.instance != null) {
            WeaponUnit artilleryUnit = LocalVehiclePlayer.instance.getWeaponUnit();
            if (artilleryUnit != null) {
                artilleryUnit.getCurrentWeapon().ifPresent(currentWeapon -> {
                    if (currentWeapon instanceof RVP_WeaponBase rvpWeapon) {
                        rvpWeapon.queueProgrammaticShot();
                    }
                    currentWeapon.doClientShoot();
                });
            }
        }
        ywzj_rvp$artilleryFireKeyDown = artilleryFireKeyDown;

        while (RVP_Keys.OPEN_GPS_PANEL.consumeClick()) {
            if (player.getMainHandItem().is(RVP_Items.FIRE_SUPPORT_TERMINAL.get())
                    || player.getOffhandItem().is(RVP_Items.FIRE_SUPPORT_TERMINAL.get())) {
                // 调用本项目双端安全桥：K 键持有终端时打开炮火工具上下文。
                RVP_ClientActionsAccess.openFireSupportTerminal();
            } else {
                mc.setScreen(new RVP_TacticalMapScreen(ywzj_rvp$resolveMapMode()));
            }
        }
        while (RVP_Keys.DEPLOY_DEPLOYABLE_UAV.consumeClick()) {
            LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
            // [RVP] N 键守卫：在载具且该车配置了可部署无人机（deployable_uav_enabled）才发包。
            // 单兵/未配置车辆静默——防无效包与服务端"未在载具/未配置"提示刷屏
            // （actionbar 只有一条，会覆盖 SBW 等其它 mod 的按键反馈）。
            // 配置缓存由 VehicleDataManagerMixin 在 VehicleDataManager.apply 时双端各自填充，客户端可读。
            if (lvp != null && lvp.onVehicle()
                    && RVP_DeployableUavConfigCache.get(lvp.vehicle.getVehicleId()).isConfigured()) {
                RVP_Network.CHANNEL.sendToServer(new C2SDeployDeployableUav());
            }
        }
        while (RVP_Keys.SWITCH_DEPLOYABLE_UAV.consumeClick()) {
            LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
            // [RVP] M 键守卫：仅要求在载具。客户端无法识别"当前载具是无人机实例"
            // （RVP_LinkedUavStateTable 为服务端 UUID 表，无 S2C 同步），驾驶子机按 M 切回
            // 母车必须放行；"无子机可切"等不适用场景由服务端静默兜底。
            if (lvp != null && lvp.onVehicle()) {
                RVP_Network.CHANNEL.sendToServer(new C2SSwitchDeployableUav());
            }
        }
        while (RVP_Keys.TOGGLE_UAV_LOITER.consumeClick()) {
            LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
            // [RVP] F 键守卫：自身或关联子机配置了盘旋参数才发包；地面子机（如 96l6 雷达车）
            // 无盘旋语义，静默，不再误报"未配置盘旋参数"。
            if (lvp != null && lvp.onVehicle() && ywzj_rvp$loiterKeyApplicable(lvp.vehicle)) {
                RVP_Network.CHANNEL.sendToServer(new C2SToggleUavLoiter());
            }
        }

        RVP_ClientHitlState.tick(mc, player);
        // IR HMD 自动检测（必须在雷达 HMD 逻辑之前）
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        hmdState.checkIrHmd();
        hmdState.tick();
        // 调用本项目 IR HMD 状态更新后再判定锁定音，保证新锁/脱锁当 Tick 生效，
        // 且不再读到被本体 NONE 传感器火控短暂清空的过渡状态。
        RVP_ClientSeekerTone.tick();

        ywzj_rvp$refreshVehicleMarkers(mc, player);

        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit != null) {
            weaponUnit.indexedWeapons.forEach(w -> {
                if (w instanceof RVP_WeaponBase rvp) {
                    rvp.getFireController().syncClientInput();
                }
            });
            AbstractVehicleWeapon<?> selectedWeapon = RVP_LaserWeapons.unwrap(weaponUnit.getCurrentWeapon().orElse(null));
            if (selectedWeapon instanceof RVP_WeaponBase selectedRvp) {
                selectedRvp.getFireController().syncClientInput();
            }
        }
        if (weaponUnit != null
                && !weaponUnit.getCurrentWeapon().isEmpty()
                && player.getVehicle() instanceof AbstractVehicle vehicle) {
            AbstractVehicleWeapon<?> currentWeapon = weaponUnit.getCurrentWeapon().get();
            if (currentWeapon instanceof RVP_WeaponBase weapon
                    && ywzj_rvp$shouldUpdateBombCcip(weapon)) {
                RVP_RocketCcipState.clear(vehicle.getId());
                ywzj_rvp$updateBombCcip(vehicle, weaponUnit, weapon);
            } else if (!RVP_RocketCcipOverlay.isBallisticRocketWeapon(currentWeapon, weaponUnit)) {
                RVP_RocketCcipState.clear(vehicle.getId());
            }
        }

        if (LocalVehiclePlayer.instance.onVehicle() || RVP_ClientHitlState.isDesignateMode()) {
            RVP_ClientSaclosState.tick(mc, player);
        }
    }

    /**
     * 无人机被击毁传送回母车后，客户端 LocalVehiclePlayer 可能未同步到母车：
     * 座位变更包在母车区块尚未加载时到达会被丢弃，或残留指向被击毁的无人机，
     * 导致能驾驶/开火（vanilla 骑乘 + controlUnit operator 已同步）但载具 UI 与相机失效。
     * 这里按实际骑乘状态自愈：玩家骑乘在载具上但 LocalVehiclePlayer 未指向该载具时，
     * 用该载具当前座位重新 toSeat，恢复载具 UI 与相机。
     */
    private static void ywzj_rvp$syncLocalVehiclePlayerSeat() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null || lvp.getPlayer() == null) {
            return;
        }
        if (!(lvp.getPlayer().getVehicle() instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (lvp.vehicle == vehicle && lvp.seat != null) {
            return; // 已同步，无需修复
        }
        AbstractVehicle.Seat seat = vehicle.seats.stream()
                .filter(s -> s.passengerId == lvp.getPlayer().getId())
                .findFirst()
                .orElse(null);
        if (seat == null) {
            return;
        }
        lvp.toSeat(seat, vehicle);
        lvp.toLeave = false;
    }

    /** 干扰物发射键：当前驾驶载具时向服务端发送一次齐射请求（flare / chaff 分键）。 */
    /**
     * [RVP] 可变后掠翼手动切换客户端守卫：驾驶员 + 无推力矢量部件的固定翼 + 双隐藏
     * 部件存在（与 {@code RVP_WingSweepState}/{@code C2SWingSweepToggle} 服务端校验
     * 同口径，防无效包）。注意 {@code onVehicle()} 对乘员也为真，必须另行校验驾驶员。
     */
    private static boolean ywzj_rvp$wingSweepGuard(LocalVehiclePlayer lvp) {
        if (lvp == null || lvp.vehicle == null || !lvp.onVehicle()) {
            return false;
        }
        if (!(lvp.vehicle instanceof FixedWingVehicle fixedWing) || fixedWing.thrustUnit != null) {
            return false;
        }
        if (lvp.vehicle.getDriver() != Minecraft.getInstance().player) {
            return false;
        }
        return lvp.vehicle.getPartUnit(RVP_WingSweepState.PART_MANUAL_ID).orElse(null) instanceof SwitchableUnit
                && lvp.vehicle.getPartUnit(RVP_WingSweepState.PART_FORM_ID).orElse(null) instanceof SwitchableUnit;
    }

    /**
     * [RVP] F 键（盘旋开关）客户端守卫：仅当"自身带盘旋配置"（AC130 空中炮艇 / 驾驶 suav 自身）
     * 或"当前载具配置了可部署无人机且子机带盘旋配置"（母车放飞 suav 场景）时才视为适用。
     * 地面子机（如 Buk-M3 / IRIS-T 的 96l6、irist_slm_tads 轮式雷达车）无盘旋配置 → 静默不发，
     * 不再触发服务端"未配置盘旋参数"提示。
     * 注意：客户端不可用 {@link org.ywzj.rvp.uav.RVP_DeployableUavService#getLinkedChild}
     * 判子机（其内部要求 ServerLevel，客户端恒返回 empty），故子机盘旋能力按两侧均由
     * VehicleDataManagerMixin 填充的配置缓存（子机载具 JSON 的 rvp_loiter_* 字段）判定。
     */
    private static boolean ywzj_rvp$loiterKeyApplicable(AbstractVehicle vehicle) {
        // 场景一：当前载具自身配置了盘旋参数（rvp_loiter_enabled）
        if (RVP_LoiterConfigCache.get(vehicle.getVehicleId()).isConfigured()) {
            return true;
        }
        // 场景二：当前载具配置了可部署无人机，且其子机载具配置了盘旋参数
        RVP_DeployableUavConfig uavConfig = RVP_DeployableUavConfigCache.get(vehicle.getVehicleId());
        return uavConfig.isConfigured()
                && RVP_LoiterConfigCache.get(uavConfig.vehicleId()).isConfigured();
    }

    private static void ywzj_rvp$fireCountermeasure(RVP_EnumCountermeasureType type) {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null || lvp.vehicle == null || !lvp.onVehicle()) {
            return;
        }
        // 座位权限客户端预判（服务端权威校验兜底）：未配置 allowed_seat_indexes 时仅一号位可用；
        // 非授权座位静默不发包（对齐按键审计"不适用静默"结论）
        int seatIndex = lvp.seat == null ? -1 : lvp.seat.seatIndex;
        var data = org.ywzj.rvp.countermeasure.RVP_CountermeasureConfigManager.INSTANCE
                .resolve(lvp.vehicle.getVehicleId());
        var system = data == null ? null : data.system(type);
        if (system == null || !system.isSeatAllowed(seatIndex)) {
            return;
        }
        // 调用 RVP 公共网络通道，发送 C2SFireCountermeasure 触发服务端状态机
        RVP_Network.CHANNEL.sendToServer(new C2SFireCountermeasure(lvp.vehicle.getId(), type));
    }

    /** 主动ECM 发射：按键命中且载具存在存活的 ECM_ACTIVE 骨块时向服务端发送请求。 */
    private static void ywzj_rvp$fireEcm() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null || lvp.vehicle == null || !lvp.onVehicle()) {
            return;
        }
        AbstractVehicle vehicle = lvp.vehicle;
        // 检查载具是否存在存活的 ECM_ACTIVE 骨块（按设计文档 §5：存活校验）
        var devices = org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager.INSTANCE.resolveEcmActiveDevices(vehicle);
        if (devices == null || devices.isEmpty()) {
            return;
        }
        boolean hasAlive = false;
        // 座位权限客户端预判（服务端权威校验兜底）：任一"存活且允许本座位"的 ECM 骨块才发包
        int seatIndex = lvp.seat == null ? -1 : lvp.seat.seatIndex;
        for (String boneName : devices.keySet()) {
            // 调用 RVP 骨块状态表判断该骨块的 ECM_ACTIVE 模块是否存活
            if (org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable.isModuleActive(
                    vehicle.getUUID(), boneName, org.ywzj.rvp.vehicle.BoneModuleType.ECM_ACTIVE)
                    && devices.get(boneName).isSeatAllowed(seatIndex)) {
                hasAlive = true;
                break;
            }
        }
        if (!hasAlive) {
            return;
        }
        // 发送 C2SFireEcm 到服务端，携带载具实体 id
        RVP_Network.CHANNEL.sendToServer(new org.ywzj.rvp.network.C2SFireEcm(vehicle.getId()));
    }

    private static RVP_TacticalMapScreen.MapMode ywzj_rvp$resolveMapMode() {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance != null ? LocalVehiclePlayer.instance.getWeaponUnit() : null;
        if (weaponUnit == null) {
            return RVP_TacticalMapScreen.MapMode.TACTICAL;
        }
        AbstractVehicleWeapon<?> currentWeapon = weaponUnit.getCurrentWeapon().orElse(null);
        if (!(RVP_LaserWeapons.unwrap(currentWeapon) instanceof RVP_WeaponBase weapon)) {
            return RVP_TacticalMapScreen.MapMode.TACTICAL;
        }
        return weapon.getData().getMiscData().isArtilleryMap()
                ? RVP_TacticalMapScreen.MapMode.ARTILLERY
                : RVP_TacticalMapScreen.MapMode.TACTICAL;
    }

    private static void ywzj_rvp$applyScopeOverrides() {
        if (LocalVehiclePlayer.instance == null || LocalVehiclePlayer.instance.viewType != LocalVehiclePlayer.ViewType.SCOPE) {
            RVP_CrtUiLiteHandler.setActive(false);
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null || weaponUnit.getOpticalSightType() != WeaponUnitData.OpticalSightType.CRT) {
            RVP_CrtUiLiteHandler.setActive(false);
            return;
        }
        if (!(weaponUnit.getData() instanceof WeaponUnitDataExt ext) || !ext.ywzj_rvp$disableCrtEffect()) {
            RVP_CrtUiLiteHandler.setActive(false);
            return;
        }
        if (CrtHandler.isActive()) {
            CrtHandler.setActive(false);
        }
        if (!RVP_CrtUiLiteHandler.isActive()) {
            RVP_CrtUiLiteHandler.setActive(true);
        }
        if (weaponUnit.withThermalImager() && LocalVehiclePlayer.instance.thermalImaging) {
            if (!ThermalHandler.isActive()) {
                ThermalHandler.setActive(true);
            }
        } else if (ThermalHandler.isActive()) {
            ThermalHandler.setActive(false);
        }
    }

    private static boolean ywzj_rvp$shouldUpdateBombCcip(RVP_WeaponBase weapon) {
        if (weapon == null || weapon.getData().getWeaponKind() != org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.BOMB) {
            return false;
        }
        return weapon.getData().usesGuidanceType(RVP_EnumGuidanceType.GPS)
                || "eo_ccip".equalsIgnoreCase(weapon.getData().getFireControlSensorMode());
    }

    private static void ywzj_rvp$updateBombCcip(AbstractVehicle vehicle, WeaponUnit weaponUnit,
                                                RVP_WeaponBase weapon) {
        if (RVP_WeaponSensorHelper.effectiveSensorType(weaponUnit) != WeaponUnitData.FireControlSensorType.CCIP) {
            return;
        }
        if (weapon.getData().usesGuidanceType(RVP_EnumGuidanceType.GPS) && RVP_ClientGPSState.isActive()) {
            weaponUnit.weaponHitPosO = null;
            weaponUnit.weaponHitPos = null;
        } else {
            Vec3 releasePos = RVP_AimContexts.muzzle(weaponUnit.aimContext());
            Vec3 aimDir = VectorUtil.rotToVec(weaponUnit.aimContext().direction.x, weaponUnit.aimContext().direction.y).normalize();
            Vec3 startVelocity = aimDir.scale(weapon.getData().resolveMuzzleSpeed(org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.BOMB));
            if (weapon.getData().isInheritVehicleVelocity()) {
                startVelocity = startVelocity.add(vehicle.getDeltaMovement());
            }
            Vec3 ccipHit = RVP_CcipUtil.computeBombImpact(
                    vehicle.level(), releasePos, startVelocity, weapon.getData());
            weaponUnit.weaponHitPosO = weaponUnit.weaponHitPos;
            weaponUnit.weaponHitPos = ccipHit;
        }
    }

    private static void ywzj_rvp$refreshVehicleMarkers(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) {
            ywzj_rvp$vehicleMarkers.clear();
            return;
        }
        if ((ywzj_rvp$markerRefreshTick++ % 10) != 0) {
            return;
        }
        ywzj_rvp$vehicleMarkers.clear();
        // O(实体) 遍历已加载载具，替代 ±512 立方体 getEntitiesOfClass（1024³，客户端标记刷新）
        double range = 512.0;
        AABB box = player.getBoundingBox().inflate(range);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractVehicle vehicle)
                    || !(vehicle.getDriver() instanceof GunnerEntity)
                    || !entity.getBoundingBox().intersects(box)) {
                continue;
            }
            GunnerEntity gunner = null;
            Entity driver = vehicle.getDriver();
            if (driver instanceof GunnerEntity g) {
                gunner = g;
            }
            if (gunner == null) {
                continue;
            }
            int argb = ywzj_rvp$getGunnerVehicleMarkerColor(player, gunner);
            if ((argb >>> 24) == 0) {
                continue;
            }
            ywzj_rvp$vehicleMarkers.add(new VehicleMarker(vehicle.getId(), argb));
        }
    }

    private static int ywzj_rvp$getGunnerVehicleMarkerColor(LocalPlayer player, GunnerEntity gunner) {
        if (gunner.getProfileFaction() == RVP_EnumGunnerFaction.ENEMY) {
            return 0xFFFF2B2B;
        }
        if (gunner.isOwnedBy(player)) {
            return 0xFF2B6CFF;
        }
        if (player.getTeam() != null && gunner.getTeam() != null) {
            boolean allied = gunner.getTeam().isAlliedTo(player.getTeam());
            return allied ? 0xFF2B6CFF : 0xFFFF2B2B;
        }
        if (gunner.getProfileFaction() == RVP_EnumGunnerFaction.FRIENDLY) {
            return 0xFF2B6CFF;
        }
        return 0xFFFF2B2B;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (ywzj_rvp$vehicleMarkers.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (VehicleMarker marker : ywzj_rvp$vehicleMarkers) {
            Entity e = mc.level.getEntity(marker.vehicleId());
            if (!(e instanceof AbstractVehicle vehicle)) {
                continue;
            }
            Vec3 markerPos = new Vec3(
                    vehicle.getX(),
                    vehicle.getBoundingBox().maxY + 4.0,
                    vehicle.getZ()
            );
            double dist = cameraPos.distanceTo(markerPos);
            if (dist > 768.0) {
                continue;
            }
            float scale = Mth.clamp(0.06f * (64.0f / (float) Math.max(1.0, dist)), 0.03f, 0.2f);

            int argb = marker.argb();
            int a = (argb >>> 24) & 0xFF;
            int r = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int b = argb & 0xFF;

            poseStack.pushPose();
            poseStack.translate(markerPos.x - cameraPos.x, markerPos.y - cameraPos.y, markerPos.z - cameraPos.z);
            poseStack.mulPose(event.getCamera().rotation());
            poseStack.scale(scale, scale, scale);

            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            var mat = poseStack.last().pose();
            buffer.vertex(mat, 0.0f, -1.0f, 0.0f).color(r, g, b, a).endVertex();
            buffer.vertex(mat, -0.8f, 0.6f, 0.0f).color(r, g, b, a).endVertex();
            buffer.vertex(mat, 0.8f, 0.6f, 0.0f).color(r, g, b, a).endVertex();
            BufferUploader.drawWithShader(buffer.end());

            poseStack.popPose();
        }

        RenderSystem.disableBlend();
    }

    @SubscribeEvent
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        if (!event.isClientSide()) {
            return;
        }
        if (event.getWeapon() != null && event.getWeapon().getWeaponUnit() != null) {
            RVP_CustomMountRenderLogic.noteClientFire(event.getWeapon().getWeaponUnit());
        }
        int operatorId = event.getOperator() != null ? event.getOperator().getId() : -1;
        RVP_ClientLaserDriver.pulseFromFireEvent(
                event.getVehicle(),
                event.getWeapon(),
                event.getVehicle().level().getGameTime(),
                operatorId);
        if (RVP_ClientSaclosState.isSaclosWeapon(event.getWeapon())) {
            RVP_ClientSaclosState.onSaclosWeaponFired();
        }
    }

    @SubscribeEvent
    public static void onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        // RenderGuiOverlayEvent.Post 每帧对每个已注册 overlay 各触发一次（40+ 次），
        // 只锚定每帧必渲染的原生 CHAT_PANEL 层执行一次，其余事件忽略（修复多弹/多实体时帧率腰斩）
        if (event.getOverlay().id() != VanillaGuiOverlay.CHAT_PANEL.id()) {
            return;
        }
        if (RVP_ClientHmdState.getInstance().isHmdMode()) {
            RVP_HmdOverlay.render(event.getGuiGraphics());
        }
    }

    /**
     * 盘旋屏蔽：在 ClientTickEvent.START 阶段（LOWEST 优先级，在 InputHandler 之后执行）
     * 覆盖本地 controlUnit 的运动字段，防止鼠标指向干扰自动盘旋制导。
     * InputHandler (NORMAL 优先级) 会先执行并写入 controlUnit，
     * 然后本处理器清零运动字段 + 设 yRotKeep=true，
     * FixedWingVehicle.tick() 在 START 和 END 之间执行，读到的是清零后的值。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTickLoiterSuppress(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (player.getVehicle() instanceof AbstractVehicle vehicle
                && RVP_ClientLoiterState.isVehicleLoitering(vehicle.getId())) {
            var cu = vehicle.controlUnit;
            cu.forward = false;
            cu.backward = false;
            cu.left = false;
            cu.right = false;
            cu.up = false;
            cu.down = false;
            cu.leftYaw = false;
            cu.rightYaw = false;
            cu.yRotKeep = true;
        }
    }
}
