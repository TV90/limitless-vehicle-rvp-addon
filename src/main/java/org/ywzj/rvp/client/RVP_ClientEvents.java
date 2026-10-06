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
import org.ywzj.rvp.client.state.RVP_ClientHeliDockState;
import org.ywzj.rvp.client.state.RVP_ClientLoiterState;
import org.ywzj.rvp.helidock.RVP_HeliDockManager;
import org.ywzj.rvp.network.C2SHeliDockToggle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.rvp.client.state.RVP_ClientHbmMissileState;
import org.ywzj.rvp.client.state.RVP_ClientExternalRadarState;
import org.ywzj.rvp.client.state.RVP_ClientRemoteAmmoState;
import org.ywzj.rvp.client.state.RVP_ClientSeekerTone;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.client.state.RVP_ClientBroadcastVehicleInterpolator;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ClientSaclosState;
import org.ywzj.rvp.client.state.RVP_ClientTacticalRevealState;
import org.ywzj.rvp.client.firesupport.RVP_ClientFireSupportState;
import org.ywzj.rvp.client.gunner.RVP_ClientGunnerProfileState;
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
    /** [RVP] 待校平的雷达主控翻转（实体id 集合）：InputEvent.Key 同源边沿置位（§41），
     *  ClientTickEvent.END 下一 tick 把全车完好雷达校平到期望方向。 */
    private static final java.util.Set<Integer> ywzj_rvp$radarPendingFlip = new java.util.HashSet<>();
    /** [RVP] "雷达已损坏"提示上次弹出时间（10 秒节流）。 */
    private static long ywzj_rvp$radarBrokenNoticeAt;
    /** [RVP] 上一 tick 完好雷达的开启集合（实体id → ON 的雷达部件 id），主控开关方向判定用。 */
    private static final java.util.Map<Integer, java.util.Set<String>> ywzj_rvp$radarOnSnapshot =
            new java.util.HashMap<>();

    /**
     * [RVP] 雷达骨骼部件主控开关（§44 2026-09-30 定版：**恒生效**，不再要求"存在被击毁
     * 雷达"才接管）：按雷达键（本体 3 键 TOGGLE_RADAR）一律按主控语义校平——以按键前
     * （上一 tick 末）快照判定：
     * <ul>
     *   <li>全部未损坏雷达都在开 → 本次按键 = <b>全部关闭</b>（打反辐射导弹时关雷达反制）；</li>
     *   <li>有关闭的未损坏雷达（含刚修好自动开的）→ 本次按键 = <b>全部开启</b>（回到双雷达运行）。</li>
     * </ul>
     * 旧版仅在存在被击毁雷达时武装（§20.2 遗留条件）：修好自动开机形成的混合态（A 开 B 关）
     * 在全部修好后退回本体逐台翻转——按 3 变两台互换、混合态永远保持 → 一开一关交替、雷达
     * 废掉（用户实测）。恒武装后混合态按 3 补齐全开，全开/全关两态间切换。
     * 被击毁的雷达不由本语义触碰（服务端网关 + 客户端 enforce 保持其关闭）。
     * 纯客户端实现，翻转经 syncRadarPowerStates 自动同步服务端。
     */
    private static void tickRadarDestroyNotice(net.minecraft.world.entity.player.Player player) {
        AbstractVehicle vehicle = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null || vehicle.level() == null || !vehicle.level().isClientSide()) {
            ywzj_rvp$radarPendingFlip.remove(vehicle == null ? 0 : vehicle.getId());
            ywzj_rvp$radarOnSnapshot.remove(vehicle == null ? 0 : vehicle.getId());
            return;
        }

        // 分类：损坏雷达（RADAR 模块失效）/ 完好雷达，并记录完好雷达当前 ON 集合
        java.util.List<org.ywzj.vehicle.vehicle.part.RadarUnit> workingRadars = new java.util.ArrayList<>();
        java.util.Set<String> workingOnNow = new java.util.HashSet<>();
        int brokenCount = 0;
        for (org.ywzj.vehicle.vehicle.part.PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof org.ywzj.vehicle.vehicle.part.RadarUnit radarUnit) {
                if (org.ywzj.rvp.client.state.RVP_ClientBoneModuleState.isModuleActive(
                        vehicle.getId(), partUnit.getId(), org.ywzj.rvp.vehicle.BoneModuleType.RADAR)) {
                    workingRadars.add(radarUnit);
                    if (radarUnit.isOn()) {
                        workingOnNow.add(radarUnit.getId());
                    }
                } else {
                    brokenCount++;
                }
            }
        }
        // 刷新快照：以本 tick 状态为准（方向判定用上一 tick 快照——校平后的稳定态）
        java.util.Set<String> snapshot = ywzj_rvp$radarOnSnapshot.put(
                vehicle.getId(), new java.util.HashSet<>(workingOnNow));

        // [RVP] §41 重写（用户实机"概率性交替开关"复发）：旧版用 isDown 轮询上升沿在本 tick
        // 立即纠正——与本体 InputHandler（InputEvent.Key 回调，帧末 pollEvents 执行）是两个
        // 门控不同的写者（action==PRESS vs isDown 上升沿、有无 mc.screen 门），存在"只有一方
        // 执行"的错位窗口（快按 down/up 落同一帧间隙；按住 3 开/关界面后 REPEAT 恢复 isDown
        // 造成假上升沿）→ 快照基线被污染 → 下次方向判定反转 → 概率性交替开关。
        //
        // 新架构：边沿判定改用与本体**同源**的 InputEvent.Key 事件（action==PRESS 才置位，
        // 见 onRadarKeyEvent，天然排除 REPEAT 假边沿/界面态）；本方法只做**下一 tick 校平**：
        // 本体翻转无论落在哪个 tick（键事件时序错位吸收），校平都把全车完好雷达收敛到同一
        // 期望方向；坏雷达不触碰（客户端 enforce 每 tick 强压关闭，事件侧同帧即时压回防闪烁）。
        Integer vehicleId = vehicle.getId();
        if (!ywzj_rvp$radarPendingFlip.remove(vehicleId) || workingRadars.isEmpty()) {
            return;
        }
        // 方向判定：校平前快照（= 上一次校平后的稳定态）全开 → 本次全关（反辐射反制）；
        // 有未开的 → 本次全开（回到双雷达）。校平是幂等 toggle，本体同帧多翻的一次被吸收。
        boolean allWereOn = snapshot != null && !snapshot.isEmpty()
                && workingRadars.stream().allMatch(r -> snapshot.contains(r.getId()));
        boolean masterOn = !allWereOn;
        for (org.ywzj.vehicle.vehicle.part.RadarUnit radarUnit : workingRadars) {
            if (radarUnit.isOn() != masterOn) {
                radarUnit.toggle(masterOn);
            }
        }
        // 校平后立即重写快照为本 tick 稳定态（下次按键方向以此为准，不再被翻转过程污染）
        ywzj_rvp$radarOnSnapshot.put(vehicleId, new java.util.HashSet<>(masterOn
                ? workingRadars.stream().map(org.ywzj.vehicle.vehicle.part.RadarUnit::getId).toList()
                : java.util.List.of()));
        // [RVP] §44：损坏提示仅在真有被击毁雷达时弹——恒武装后全部完好时按 3 也会走到这里，
        // 不能误弹"雷达损坏"
        if (brokenCount > 0) {
            long now = System.currentTimeMillis();
            if (now - ywzj_rvp$radarBrokenNoticeAt > 10_000L) {
                ywzj_rvp$radarBrokenNoticeAt = now;
                player.displayClientMessage(
                        Component.translatable("message.ywzj_rvp.radar_broken"), true);
            }
        }
    }

    /**
     * [RVP] 雷达键鼠标绑定同源边沿（§41）：与本体 onKey(MouseButton.Pre) 同路由——雷达键
     * 绑定到鼠标键时键盘事件不会触发，主控 pending 须同样置位；其余判定与 onRadarKeyEvent
     * 完全一致（复用同一私有入口）。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onRadarMouseButtonEvent(net.minecraftforge.client.event.InputEvent.MouseButton.Pre event) {
        if (event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null
                || !(org.ywzj.vehicle.all.AllKeys.TOGGLE_RADAR.matches(event.getButton(), 0)
                     || org.ywzj.vehicle.all.AllKeys.TOGGLE_RADAR.matchesMouse(event.getButton()))) {
            return;
        }
        ywzj_rvp$onRadarActionPress();
    }

    /**
     * [RVP] 雷达键同源边沿监听（§41，InputEvent.Key，LOWEST 保证在本体 InputHandler 的
     * 同事件处理之后执行）：仅物理按下（action==PRESS，天然排除 REPEAT 假边沿）、无界面
     * （与本体 handleVehicleAction 同门）、在载具上即置 pending 校平标记（§44 恒武装，
     * 不再要求存在损坏雷达）。
     * 本体把**坏雷达**翻开的场景在此同帧即时压回（坏雷达不参与主控语义，不该闪开）。
     * 注意：只置标记不翻雷达——方向校平统一在下一 tick END 执行（见 tickRadarDestroyNotice）。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onRadarKeyEvent(net.minecraftforge.client.event.InputEvent.Key event) {
        if (event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            return;
        }
        if (org.ywzj.vehicle.all.AllKeys.TOGGLE_RADAR.matches(event.getKey(), event.getScanCode())
                || org.ywzj.vehicle.all.AllKeys.TOGGLE_RADAR.matchesMouse(event.getKey())) {
            ywzj_rvp$onRadarActionPress();
        }
    }

    /** [RVP] 雷达动作按下公共入口（键盘/鼠标两监听器汇聚）：坏雷达即时压回 + 置 pending 校平标记。 */
    private static void ywzj_rvp$onRadarActionPress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null) {
            return;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        boolean hasRadar = false;
        for (org.ywzj.vehicle.vehicle.part.PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof org.ywzj.vehicle.vehicle.part.RadarUnit radarUnit) {
                hasRadar = true;
                boolean broken = !org.ywzj.rvp.client.state.RVP_ClientBoneModuleState.isModuleActive(
                        vehicle.getId(), partUnit.getId(), org.ywzj.rvp.vehicle.BoneModuleType.RADAR);
                if (broken) {
                    // 本体无损坏 gate 把坏雷达翻开：同帧即时压回（LOWEST 晚于本体 handler）
                    if (radarUnit.isOn()) {
                        radarUnit.toggle(false);
                    }
                }
            }
        }
        // [RVP] §44（2026-09-30 用户定版）：主控校平**恒武装**——不再要求"存在被击毁雷达"才
        // 接管按 3。旧条件（anyBroken）下，修好自动开机（RVP_RadarModuleEnforcer.restoreRadar）
        // 形成的混合态（A 开 B 关）在全部修好后由本体逐台翻转接管：按 3 = 两台同时互换，
        // 混合态永远保持 → 一开一关交替、雷达废掉（用户实测：修好一台后按 3 反把修好的关掉，
        // 此后永远交替）。恒武装后方向判定（上一 tick 快照：非全开→全开、全开→全关）对
        // 所有按 3 统一生效；坏雷达同帧压回不受影响。
        if (hasRadar) {
            ywzj_rvp$radarPendingFlip.add(vehicle.getId());
        }
    }

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
        RVP_ClientGunnerProfileState.INSTANCE.clientTick();

        ywzj_rvp$syncLocalVehiclePlayerSeat();

        RVP_ClientBulletHitDebugState.clientTick();
        RVP_ClientHitIndicatorState.clientTick();
        RVP_ClientHbmMissileState.clientTick();
        RVP_ClientRemoteAmmoState.clientTick();
        RVP_ClientExternalRadarState.clientTick();
        // 调用本项目广播载具插值器，按本体 serverEntities 生命周期淘汰过期 HUD/火控轨迹。
        RVP_ClientBroadcastVehicleInterpolator.clientTick();
        RVP_ClientTacticalRevealState.clientTick();
        RVP_ClientGunnerVehicleState.clientTick();
        // 调用本项目客户端维修顺序侧表清理：维度切换/登出全清，剔除已卸载载具
        org.ywzj.rvp.client.state.RVP_ClientRepairOrderState.clientTick();
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

        // [RVP] 雷达骨骼部件（2026-09-27）：雷达损坏时按雷达键给出动作栏提示。
        // 本体 InputHandler 用 InputEvent.Key 事件驱动 TOGGLE_RADAR，不走 consumeClick
        // 计数——RVP 侧用 isDown 上升沿检测（每 tick 比对），提示 10 秒节流。
        tickRadarDestroyNotice(player);
        // [RVP] 引擎部件（2026-09-27）：瘫痪/受损档位变化时给驾驶员动作栏提示（各 10 秒节流）
        tickEngineDamageNotice(player);

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

        // 快速维修（;，2026-09-25 由 G 改键）：全部载具可用（未显式配置 maintenance 的载具
        // 走 resolveMaintenanceModule 的默认配置：冷却 15 秒、一次修复 30% 最大血量）→ 服务端权威触发
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
            } else if (hmd.isEoHmd()) {
                // EO 头瞄开启时按 5 关闭（与雷达头瞄同键同语义）。
                hmd.disableEoHmd();
                player.displayClientMessage(
                        Component.translatable("message.ywzj_rvp.hmd.off"), true);
            } else {
                // [RVP] 无 HMS 雷达时完全静默：不进 HMD、不清 STT 锁定、不弹提示。
                // 原代码 toggle() 失败仍弹 "hmd.off"，且在进入 HMD 前就误清了 STT 锁定。
                if (hmd.getRadarHmdUnit(lvp.getWeaponUnit()) != null) {
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
                    } else if (lvp.vehicle != null && ywzj_rvp$hasBrokenRadar(lvp.vehicle)) {
                        // [RVP] HMD 开启失败且本车雷达已被击毁：不再静默，提示损坏（10 秒节流）
                        long now = System.currentTimeMillis();
                        if (now - ywzj_rvp$radarBrokenNoticeAt > 10_000L) {
                            ywzj_rvp$radarBrokenNoticeAt = now;
                            player.displayClientMessage(
                                    Component.translatable("message.ywzj_rvp.radar_broken"), true);
                        }
                    }
                } else if (hmd.toggleEoHmd()) {
                    // [RVP] 光电头瞄：EO 武器站且载具无雷达的载具，5 键进入（雷达优先，无雷达才走此分支）。
                    player.displayClientMessage(
                            Component.translatable("message.ywzj_rvp.hmd.eo_on"), true);
                }
                // 雷达与 EO 条件都不满足：保持静默（原行为）。
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
        // [RVP] O 键：辅助设备面板（俯视图 + 设备状态栏目 + 快修顺序设置）。
        // 守卫：仅乘载具时可打开（面板展示的就是本车设备状态）。
        while (RVP_Keys.OPEN_EQUIP_PANEL.consumeClick()) {
            LocalVehiclePlayer equipPanelLvp = LocalVehiclePlayer.instance;
            if (equipPanelLvp != null && equipPanelLvp.onVehicle() && equipPanelLvp.vehicle != null) {
                mc.setScreen(new org.ywzj.rvp.client.screen.RVP_EquipPanelScreen(equipPanelLvp.vehicle));
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
        while (RVP_Keys.TOGGLE_HELI_DOCK.consumeClick()) {
            // 直升机着舰 P 键：驾驶旋翼机即可发（服务端校验驾驶员与阶段）
            if (player.getVehicle() instanceof RotaryWingVehicle) {
                RVP_Network.CHANNEL.sendToServer(new C2SHeliDockToggle());
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
    /**
     * [RVP] 本车是否存在 RADAR 模块已失效的雷达骨（HMD 开启失败时的损坏提示判据）。
     */
    private static boolean ywzj_rvp$hasBrokenRadar(AbstractVehicle vehicle) {
        for (org.ywzj.vehicle.vehicle.part.PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof org.ywzj.vehicle.vehicle.part.RadarUnit
                    && !org.ywzj.rvp.client.state.RVP_ClientBoneModuleState.isModuleActive(
                            vehicle.getId(), partUnit.getId(), org.ywzj.rvp.vehicle.BoneModuleType.RADAR)) {
                return true;
            }
        }
        return false;
    }

    /** [RVP] 引擎提示记忆：上次档位（0 正常/1 受损/2 瘫痪，-1 初始）与上次提示时间。 */
    private static int ywzj_rvp$engineNoticeStage = -1;
    private static long ywzj_rvp$engineNoticeAt;

    /**
     * [RVP] 引擎部件（2026-09-27）：驾驶员驾驶引擎受损/瘫痪的载具时给动作栏提示——
     * 档位变化即提示（进入受损/进入瘫痪各一次），同档 10 秒节流兜底（防止反复进出
     * 受损阈值刷屏）。数据源 = S2CEngineDamageState 档位侧表（服务端差分推送）。
     */
    private static void tickEngineDamageNotice(net.minecraft.world.entity.player.Player player) {
        AbstractVehicle vehicle = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null || vehicle.level() == null || !vehicle.level().isClientSide()) {
            ywzj_rvp$engineNoticeStage = -1;
            return;
        }
        // 取本车引擎骨最高档位（0/1/2；无配置 = -1 不提示）
        int stage = -1;
        var engineBones = org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager.INSTANCE.resolveEngineModules(vehicle);
        if (engineBones != null && !engineBones.isEmpty()) {
            stage = 0;
            for (String bone : engineBones.keySet()) {
                stage = Math.max(stage,
                        org.ywzj.rvp.client.state.RVP_ClientEngineDamageState.getStage(vehicle.getId(), bone));
            }
        }
        if (stage == ywzj_rvp$engineNoticeStage) {
            return; // 档位未变化
        }
        int prev = ywzj_rvp$engineNoticeStage;
        ywzj_rvp$engineNoticeStage = stage;
        if (stage <= 0 || stage <= prev) {
            return; // 恢复正常不提示；档位下降（受损→瘫痪仍算上升）才提示
        }
        long now = System.currentTimeMillis();
        if (now - ywzj_rvp$engineNoticeAt < 10_000L) {
            return;
        }
        ywzj_rvp$engineNoticeAt = now;
        player.displayClientMessage(Component.translatable(stage == 2
                ? "message.ywzj_rvp.engine_disabled" : "message.ywzj_rvp.engine_damaged"), true);
    }

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
        boolean heliDockLocked = player.getVehicle() instanceof AbstractVehicle vehicle
                && (RVP_ClientHeliDockState.isControlLocked()
                    || org.ywzj.rvp.helidock.RVP_HeliDockManager.isControlLocked(vehicle.getUUID()));
        if (player.getVehicle() instanceof AbstractVehicle vehicle
                && (RVP_ClientLoiterState.isVehicleLoitering(vehicle.getId()) || heliDockLocked)) {
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
