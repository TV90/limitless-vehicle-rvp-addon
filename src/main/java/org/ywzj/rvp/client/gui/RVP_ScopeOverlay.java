package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.config.UIPresetManager.UIPosition;
import org.ywzj.rvp.config.UIPresetManager.UIPreset;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.client.state.RVP_ClientBroadcastVehicleInterpolator;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.network.S2CExternalRadarSnapshot;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.client.gui.VehicleAimAtOverlay;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.RotatableUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
import org.ywzj.vehicle.vehicle.weapon.VehicleMissile;

import static org.ywzj.vehicle.util.RenderHelper.drawRectByCorner;
import static org.ywzj.vehicle.util.RenderHelper.drawSquare;

/**
 * 观瞄覆盖层（准心 + 射界 + 目标框）。
 * <p>
 * 复制自 {@code VehicleScopeOverlay}，仅修改位置计算使用 {@link UIPresetManager}。
 * <strong>不渲染骨骼俯视图</strong>（由 {@code show_skeleton} 控制）。
 * </p>
 *
 * <h3>与原版的差异（标记为 {@code // [RVP]}）</h3>
 * <ul>
 *   <li>准心偏移 → 读 {@code preset.scope_crosshair}</li>
 *   <li>射界偏移 → 读 {@code preset.scope_envelope}</li>
 *   <li>删除 {@code renderVehicleHeading()} 调用</li>
 * </ul>
 */
public class RVP_ScopeOverlay implements IGuiOverlay {

    public static double fov;
    public static int color = Color.GREEN;

    /** BVR 外置扫描框调试开关（默认关闭；排查外置雷达框不渲染时置 true）。 */
    private static final boolean RVP_BVR_DEBUG = false;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        if (LocalVehiclePlayer.instance.viewType != LocalVehiclePlayer.ViewType.SCOPE) {
            // [RVP] 非观瞄视角：RF 武器+无光学瞄（或存在外置雷达条目）时由本 overlay
            // 统一绘制锁定目标框，并接管本体 VehicleAimAtOverlay 的重复绘制
            //（见 VehicleAimAtOverlaySeekerColorMixin 的 hudLockTakeoverActive 重定向）。
            if (hudLockTakeoverActive()) {
                renderAimLockTarget(guiGraphics, partialTick);
            } else {
                WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
                // 本体 HUD 仅在武器站静态传感器为 RF 时绘制雷达硬锁框；PL-10 等非 RF
                // 武器下仍可能由 HMD 建立真实雷达锁，因此这里只补画硬锁框，不接管扫描航迹。
                if (weaponUnit != null
                        && weaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF) {
                    // 调用本项目雷达角色解析，获取当前实际持锁的雷达而非只读主雷达。
                    RadarUnit lockedRadar = RVP_RadarRoleHelper.getLockedRadar(weaponUnit);
                    renderLocalRadarHardLock(guiGraphics, partialTick, weaponUnit, lockedRadar);
                }
            }
            return;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        // [RVP] 仅在有 ui_preset 时渲染
        if (vehicle.getVehicleId() == null) return;
        // HITL 视角激活时不渲染 scope 准星：TV 弹由 RVP_TVMissileOverlay 提供专用准星，
        // scope 的 CRT 落点框会与之重叠形成"双准星"
        if (org.ywzj.rvp.client.state.RVP_ClientHitlState.isActive()) return;
        String presetName = VehicleUIPresetCache.get(vehicle.getVehicleId());
        if (presetName == null || presetName.isEmpty()) return;
        // 准心（IR 寻的器模式跳过，HMD cueing 由雷达 overlay 提供）
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null || !(weaponUnit.isSeekerOn() && RVP_WeaponSensorHelper.effectiveSensorType(weaponUnit) == WeaponUnitData.FireControlSensorType.IR)) {
            renderCrosshair(guiGraphics, partialTick, vehicle);
        }
        // 射界
        renderWeaponEngagementEnvelope(guiGraphics, partialTick, screenWidth, screenHeight, vehicle);
        // 目标
        renderAimLockTarget(guiGraphics, partialTick);
        // [RVP] 骨骼俯视图：不渲染，由 show_skeleton 控制
    }

    // [RVP] keep renderVehicleHeading for reference, but not called from render()
    // 如需骨骼渲染，取消 render() 中的注释并在 VehicleScopeOverlayHeadingMixin 中控制
    @SuppressWarnings("unused")
    public void renderVehicleHeading(GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, AbstractVehicle vehicle) {
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            // [RVP] 骨骼位置读 preset.vehicle_bones
            String presetName = "default";
            if (vehicle.getVehicleId() != null) {
                String cached = VehicleUIPresetCache.get(vehicle.getVehicleId());
                if (cached != null && !cached.isEmpty()) presetName = cached;
            }
            UIPosition bonesPos = UIPresetManager.getVehicleBones(presetName);
            float bx = screenWidth / 2f + 116f;
            float by = screenHeight / 2f + 80f;
            if (bonesPos != null) {
                bx = bonesPos.computeX(screenWidth);
                by = bonesPos.computeY(screenHeight);
            }
            poseStack.translate(bx, by, 0f);
            float scale = (float) (5.0 / Math.max(4.0, vehicle.getStructureLength()));
            poseStack.scale(scale, scale, scale);
            float zRot = 180f;
            float zRotO = 180f;
            Player player = LocalVehiclePlayer.instance.getPlayer();
            PartUnit<?> playerPartUnit = vehicle.getOwnOperatorUnit(player);
            if (playerPartUnit instanceof RotatableUnit<?> rotatableUnit) {
                zRot -= rotatableUnit.worldRot().y - vehicle.getYRot();
                zRotO -= rotatableUnit.worldRot(rotatableUnit.xRotO, rotatableUnit.yRotO).y - vehicle.yRotO;
                if (Math.abs(zRot - zRotO) > 90) {
                    zRotO += zRotO < 0 ? 360f : -360f;
                }
            }
            poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, zRotO, zRot)));
            Vec3 pos = vehicle.position();
            Vector3f[] axes = vehicle.axes();
            Vector3f axisX = axes[0];
            Vector3f axisZ = axes[2];
            float vehicleYRotRad = (float) Math.toRadians(vehicle.getYRot());
            Vector3f rotCache = new Vector3f();
            for (VehicleCubeOBB vehicleCubeOBB : vehicle.getVehicleCubeOBBs()) {
                renderCubeOBB(vehicleCubeOBB, pos, axisX, axisZ, vehicleYRotRad, rotCache, poseStack, guiGraphics, Color.GREEN);
            }
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                int c = partUnit.getOwner() == player ? Color.BLUE : Color.GREEN;
                for (VehicleCubeOBB partCubeOBB : partUnit.getPartCubeOBBs()) {
                    renderCubeOBB(partCubeOBB, pos, axisX, axisZ, vehicleYRotRad, rotCache, poseStack, guiGraphics, c);
                }
                if (partUnit instanceof WeaponUnit weaponUnit) {
                    for (WeaponUnit subWeaponUnit : weaponUnit.getSubWeaponUnits()) {
                        for (VehicleCubeOBB partCubeOBB : subWeaponUnit.getPartCubeOBBs()) {
                            renderCubeOBB(partCubeOBB, pos, axisX, axisZ, vehicleYRotRad, rotCache, poseStack, guiGraphics, c);
                        }
                    }
                }
            }
        }
        poseStack.popPose();
    }

    private void renderCubeOBB(VehicleCubeOBB cubeOBB, Vec3 pos, Vector3f axisX, Vector3f axisZ, float vehicleYRotRad, Vector3f rotCache, PoseStack poseStack, GuiGraphics guiGraphics, int color) {
        Vec3 center = new Vec3(cubeOBB.obb().center());
        double dx = center.x - pos.x;
        double dy = center.y - pos.y;
        double dz = center.z - pos.z;
        float offsetX = (float) ((dx * axisX.x() + dy * axisX.y() + dz * axisX.z()) * 10.0);
        float offsetZ = (float) ((dx * axisZ.x() + dy * axisZ.y() + dz * axisZ.z()) * 10.0);
        poseStack.pushPose();
        poseStack.translate(offsetX, offsetZ, 0f);
        cubeOBB.obb().rotation().getEulerAnglesYXZ(rotCache);
        poseStack.mulPose(Axis.ZP.rotation(-vehicleYRotRad - rotCache.y));
        int hw = (int) (cubeOBB.width * 5.0);
        int hd = (int) (cubeOBB.depth * 5.0);
        drawRectByCorner(guiGraphics, -hw, hw, -hd, hd, color, 1);
        poseStack.popPose();
    }

    public void renderCrosshair(GuiGraphics guiGraphics, float partialTick, AbstractVehicle vehicle) {
        if (vehicle.getOwnOperatorUnit(LocalVehiclePlayer.instance.getPlayer()) instanceof WeaponUnit weaponUnit) {
            Vec3 posO = VectorUtil.worldToScreen(weaponUnit.weaponHitPosO);
            Vec3 pos = VectorUtil.worldToScreen(weaponUnit.weaponHitPos);
            PoseStack poseStack = guiGraphics.pose();
            poseStack.pushPose();
            {
                poseStack.translate(
                        Mth.lerp(partialTick, posO.x, pos.x),
                        Mth.lerp(partialTick, posO.y, pos.y),
                        0);
                if (weaponUnit.getOpticalSightType() == WeaponUnitData.OpticalSightType.CRT) {
                    poseStack.pushPose();
                    {
                        poseStack.translate(-0.5, -0.5, 0);
                        drawSquare(guiGraphics, 0, 0, 5, color);
                    }
                    poseStack.popPose();
                    poseStack.pushPose();
                    {
                        poseStack.translate(-0.5, 0, 0);
                        guiGraphics.fill(0, -32, 1, -8, color);
                        guiGraphics.fill(0, 8, 1, 32, color);
                    }
                    poseStack.popPose();
                    poseStack.pushPose();
                    {
                        poseStack.translate(0, -0.5, 0);
                        guiGraphics.fill(-32, 0, -8, 1, color);
                        guiGraphics.fill(32, 0, 8, 1, color);
                    }
                    poseStack.popPose();
                    guiGraphics.drawCenteredString(Minecraft.getInstance().font,
                            (LocalVehiclePlayer.instance.outOfRangeFinding ? ">" : "")
                                    + (int) LocalVehiclePlayer.instance.aimLocationDistance + " m", 0, 40, color);
                    guiGraphics.drawString(Minecraft.getInstance().font, "x" + String.format("%.1f", weaponUnit.getZoom()), 25, 16, color);
                    guiGraphics.drawString(Minecraft.getInstance().font, weaponUnit.withStabilizer() ? Component.translatable("ui.stabilizer_on").getString() : "", 25, 28, color);
                    // 焦点锁定
                    if (weaponUnit.withFocusLocker()) {
                        Vec3 focusLockPos = weaponUnit.getFocusLockPos();
                        if (focusLockPos != null) {
                            poseStack.pushPose();
                            {
                                poseStack.translate(0, -0.5, 0);
                                poseStack.rotateAround(Axis.ZP.rotationDegrees(45), 0, 0, 0);
                                RenderHelper.drawCrossHollow(guiGraphics, 0, 0, 40, 8, Color.GREEN);
                            }
                            poseStack.popPose();
                            guiGraphics.drawString(Minecraft.getInstance().font, Component.translatable("ui.focus_lock").getString(), 25, 40, color);
                        }
                    }
                } else {
                    poseStack.pushPose();
                    {
                        poseStack.translate(-0.5, -0.5, 0);
                        RenderHelper.drawRect(guiGraphics, 0, 0, 1, 1, color, 1f);
                    }
                    poseStack.popPose();
                }
                // 装填进度
                weaponUnit.getCurrentWeapon().ifPresent(weapon -> VehicleAimAtOverlay.renderReloadProgress(guiGraphics, weapon, 7f, 1.2f));
                weaponUnit.getCurrentSecondaryWeapon().ifPresent(weapon -> VehicleAimAtOverlay.renderReloadProgress(guiGraphics, weapon, 5.6f, 1f));
                if (!weaponUnit.independentWeapons.isEmpty()) {
                    VehicleAimAtOverlay.renderReloadProgress(guiGraphics, weaponUnit.independentWeapons.get(0), 4.4f, 0.8f);
                }
            }
            poseStack.popPose();
        }
    }

    public void renderWeaponEngagementEnvelope(GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, AbstractVehicle vehicle) {
        // [RVP] 射界位置读 preset.scope_envelope
        String presetName = "default";
        if (vehicle.getVehicleId() != null) {
            String cached = VehicleUIPresetCache.get(vehicle.getVehicleId());
            if (cached != null && !cached.isEmpty()) presetName = cached;
        }
        UIPreset preset = UIPresetManager.get(presetName);
        UIPosition envPos = preset != null ? preset.scopeEnvelope : null;
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;
        if (envPos != null) {
            centerX = envPos.computeX(screenWidth);
            centerY = envPos.computeY(screenHeight);
        }
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            poseStack.translate(centerX, centerY, 0);
            if (vehicle.getOwnOperatorUnit(LocalVehiclePlayer.instance.getPlayer()) instanceof WeaponUnit weaponUnit) {
                weaponUnit.getCurrentWeapon().ifPresent(vehicleWeapon -> {
                    poseStack.scale(0.5f, 0.5f, 0.5f);
                    int baseX = 0;
                    int baseY = 140;
                    float yRotRange = weaponUnit.getYRotMax() - weaponUnit.getYRotMin();
                    if (yRotRange <= 0 || yRotRange >= 360 || weaponUnit.yRotSpeed == 0) {
                        return;
                    }
                    drawRectByCorner(guiGraphics,
                            (int) (baseX + weaponUnit.getYRotMin()),
                            (int) (baseX + weaponUnit.getYRotMax()),
                            (int) (baseY + weaponUnit.getXRotMin()),
                            (int) (baseY + weaponUnit.getXRotMax()),
                            color, 1f);
                    if (vehicleWeapon instanceof VehicleMissile vehicleMissile) {
                        VehicleMissileWeaponData vehicleMissileWeaponData = vehicleMissile.getData();
                        drawRectByCorner(guiGraphics,
                                (int) (baseX + vehicleMissileWeaponData.getYRotMin()),
                                (int) (baseX + vehicleMissileWeaponData.getYRotMax()),
                                (int) (baseY + Math.max(vehicleMissileWeaponData.getXRotMin(), weaponUnit.getXRotMin())),
                                (int) (baseY + Math.min(vehicleMissileWeaponData.getXRotMax(), weaponUnit.getXRotMax())),
                                color, 1f);
                    }
                    int x = (int) Mth.lerp(partialTick, weaponUnit.yRotO, weaponUnit.getYRot());
                    int y = (int) Mth.lerp(partialTick, weaponUnit.xRotO, weaponUnit.getXRot());
                    guiGraphics.fill(baseX + x, baseY + y - 8, baseX + x + 1, baseY + y - 2, color);
                    guiGraphics.fill(baseX + x, baseY + y + 3, baseX + x + 1, baseY + y + 9, color);
                    guiGraphics.fill(baseX + x - 8, baseY + y, baseX + x - 2, baseY + y + 1, color);
                    guiGraphics.fill(baseX + x + 3, baseY + y, baseX + x + 9, baseY + y + 1, color);
                });
            }
        }
        poseStack.popPose();
    }

    /**
     * [RVP] HUD 非观瞄视角的锁定框接管门控：存在本机雷达硬锁、RF 武器 + 无光学瞄
     *（或存在外置雷达条目）时，
     * 锁定目标框由本 overlay（rvp_scope 非观瞄分支）统一绘制，本体 VehicleAimAtOverlay
     * 的 renderAimLockTarget 跳过（见 VehicleAimAtOverlaySeekerColorMixin 的接管重定向），
     * 避免本体/RVP 两套渲染器对同一目标各画一套框（锚点偏差在近距呈"双框"）。
     */
    public static boolean hudLockTakeoverActive() {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            return false;
        }
        if (LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE) {
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return false;
        }
        boolean rfAim = RVP_WeaponSensorHelper.effectiveSensorType(weaponUnit) == WeaponUnitData.FireControlSensorType.RF
                && weaponUnit.getOpticalSightType() == WeaponUnitData.OpticalSightType.NONE;
        // 调用本项目雷达角色解析，确保任何武器/光学配置下的真实雷达硬锁都由平滑渲染器接管。
        RadarUnit lockedRadar = RVP_RadarRoleHelper.getLockedRadar(weaponUnit);
        boolean localRadarHardLock = lockedRadar != null && lockedRadar.getLockedEntity() != null;
        // TWS 单缺口航迹若来自本体广播载具，也必须接管；否则带光学瞄具时会回落到本体离散锚点。
        boolean smoothableTwsContact = hasBroadcastVehicleRadarContact(weaponUnit);
        return localRadarHardLock || smoothableTwsContact || rfAim || hasExternalRadarEntries();
    }

    public static void renderAimLockTarget(GuiGraphics guiGraphics, float partialTick) {
        // 调试（BVR 扫描框排查，默认关闭）：无条件节流日志，定位外置扫描框不渲染
        if (RVP_BVR_DEBUG) {
            AbstractVehicle dbgVehicle = LocalVehiclePlayer.instance.vehicle;
            if (dbgVehicle != null && dbgVehicle.tickCount % 20 == 0) {
                WeaponUnit dbgWu = LocalVehiclePlayer.instance.getWeaponUnit();
                WeaponUnitData.FireControlSensorType dbgSt = dbgWu != null
                        ? RVP_WeaponSensorHelper.effectiveSensorType(dbgWu) : null;
                int extCount = dbgVehicle.level() != null
                        ? RVP_ExternalRadarLinkHelper.getClientEntries(dbgVehicle, dbgVehicle.level().dimension().location()).size() : -1;
                rvpBvrLog("renderAimLockTarget: viewType=" + LocalVehiclePlayer.instance.viewType
                        + " weaponUnit=" + (dbgWu == null ? "null" : dbgWu.getId())
                        + " sensorType=" + dbgSt
                        + " 外置条目=" + extCount
                        + " 车辆=" + dbgVehicle.getVehicleId());
            }
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        WeaponUnitData.FireControlSensorType sensorType = RVP_WeaponSensorHelper.effectiveSensorType(weaponUnit);
        // [RVP] 先解析两把锁：雷达锁（BVR 框分支）与武器站锁（导引头圈分支）。手动锁定时
        // 锁路由会双写同一目标——两分支若都画，锚点不同（bbox 中心 vs 探测记录位置）会在
        // 近距分离成"两个近乎重叠的框"。同目标时导引头圈并入 BVR 框同锚点组合绘制。
        RadarUnit mainRadarUnit = RVP_RadarRoleHelper.getLockedRadar(weaponUnit);
        Entity radarLocked = mainRadarUnit == null ? null : mainRadarUnit.getLockedEntity();
        // 武器站锁定目标（雷达锁分支接管同目标时跳过，避免双框）
        if (weaponUnit.getLockedEntity() != null && weaponUnit.getLockedEntity() != radarLocked) {
            Entity entity = weaponUnit.getLockedEntity();
            // 调用本项目广播载具插值器，避免超远距武器锁定圈随 5 Tick 广播阶梯跳动。
            Vec3 targetPosition = RVP_ClientBroadcastVehicleInterpolator.resolveRenderCenter(entity, partialTick);
            Vec3 screenPos = VectorUtil.worldToScreen(targetPosition);
            if (screenPos.z >= 0) {
                PoseStack poseStack = guiGraphics.pose();
                poseStack.pushPose();
                {
                    poseStack.translate(screenPos.x, screenPos.y, 0);
                    if (weaponUnit.isSeekerOn()) {
                        if (sensorType == WeaponUnitData.FireControlSensorType.IR) {
                            GuiHelper.drawCircle(guiGraphics.pose(), 0, 0, 15, Color.RED, 0.03f, 0, 0);
                        } else if (sensorType == WeaponUnitData.FireControlSensorType.RF) {
                            GuiHelper.drawCircle(guiGraphics.pose(), 0, 0, 5, Color.RED, 0.05f, 0, 0);
                            GuiHelper.drawCircle(guiGraphics.pose(), 0, 0, 4, Color.RED, 0.06f, 0, 0);
                        }
                    }
                    if (sensorType == WeaponUnitData.FireControlSensorType.EO) {
                        RenderHelper.drawSquare(guiGraphics, 0, 0, 15, Color.GREEN);
                    }
                }
                poseStack.popPose();
            }
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        Minecraft mc = Minecraft.getInstance();
        Entity externalLockedEntity = null;
        S2CExternalRadarSnapshot.Entry externalLockedEntry = null;
        int externalLockedEntityId = Integer.MIN_VALUE;
        if (sensorType == WeaponUnitData.FireControlSensorType.RF && vehicle != null && mc.level != null) {
            externalLockedEntityId = RVP_ExternalRadarLinkHelper.getClientLockedEntityId(vehicle, mc.level.dimension().location());
            externalLockedEntity = RVP_ExternalRadarLinkHelper.getClientLockedEntity(vehicle, mc.level.dimension().location());
            if (externalLockedEntityId != Integer.MIN_VALUE) {
                externalLockedEntry = RVP_ExternalRadarLinkHelper.getClientEntry(vehicle, mc.level.dimension().location(), externalLockedEntityId);
            }
        }

        // 雷达锁定目标
        // 本机雷达锁定：能解析出探测对象才画框（否则可能只是外置锁定被镜像进本机雷达的 lockedEntity，
        // 目标并不在本机雷达探测表里——如 bukm3 这类"外置雷达车提供探测+发射车自带雷达"的载具）。
        // 外置锁定作为兜底分支：本机锁画不出时再画外置锁，避免 BVR 框缺失。
        // 雷达硬锁属于雷达通道状态，不取决于当前选择武器的传感器类型；调用独立绘制入口后，
        // IR 弹也能与自己的红色锁定圈同时显示雷达双绿色实心框。
        boolean drewLockBox = renderLocalRadarHardLock(
                guiGraphics, partialTick, weaponUnit, mainRadarUnit);
        if (!drewLockBox && sensorType == WeaponUnitData.FireControlSensorType.RF
                && externalLockedEntry != null
                && vehicle != null) {
            // 外置雷达若已解析到本体广播克隆，则复用同一平滑锚点，保持硬锁双框连续。
            Vec3 targetPos = externalLockedEntity != null
                    ? RVP_ClientBroadcastVehicleInterpolator.resolveRenderCenter(externalLockedEntity, partialTick)
                    : RVP_ExternalRadarLinkHelper.position(externalLockedEntry);
            Vec3 screenPos = VectorUtil.worldToScreen(targetPos);
            if (screenPos.z >= 0) {
                PoseStack poseStack = guiGraphics.pose();
                poseStack.pushPose();
                {
                    poseStack.translate(screenPos.x, screenPos.y, 0);
                    RenderHelper.drawSquare(guiGraphics, 0, 0, 15, Color.GREEN);
                    RenderHelper.drawSquare(guiGraphics, 0, 0, 10, Color.GREEN);
                    alliesInfoExternal(guiGraphics, externalLockedEntry);
                    radarInfoExternal(guiGraphics, poseStack, externalLockedEntry, externalLockedEntity, vehicle);
                }
                poseStack.popPose();
            }
        }

        // 雷达可锁定的目标
        if (mainRadarUnit != null) {
            for (RadarUnit.DetectedObject detectedObject : weaponUnit.getRadarDetectedEntities()) {
                Entity lockedEntity = mainRadarUnit.getLockedEntity();
                if (lockedEntity != null && detectedObject.entity.getId() == lockedEntity.getId()) {
                    continue;
                }
                // 调用本项目广播载具插值器；普通实体会自动回退到本体单 Tick 插值。
                Vec3 detectedPosition = RVP_ClientBroadcastVehicleInterpolator.resolveRenderCenter(
                        detectedObject.entity, partialTick, detectedObject.detectedPosition);
                Vec3 screenPos = VectorUtil.worldToScreen(detectedPosition);
                if (screenPos.z < 0) {
                    continue;
                }
                PoseStack poseStack = guiGraphics.pose();
                poseStack.pushPose();
                {
                    poseStack.translate(screenPos.x, screenPos.y, 0);
                    if (detectedObject.entity instanceof AmmoEntity) {
                        RenderHelper.drawSquareCorners(guiGraphics, 0, 0, 10, 3, Color.GREEN);
                    } else {
                        RenderHelper.drawSquareCorners(guiGraphics, 0, 0, 15, 5, Color.GREEN);
                        alliesInfo(guiGraphics, detectedObject);
                        radarInfo(guiGraphics, poseStack, detectedObject);
                    }
                }
                poseStack.popPose();
            }
        }
        // 外置雷达接触：不依赖传感器类型为 RF，有外置条目即渲染（本机雷达 loop 无传感器门控，
        // 若这里也门控 RF，则"外置雷达扫到但本机雷达未对准"的载具不会画 BVR 框）
        renderExternalRadarContacts(guiGraphics, partialTick, weaponUnit, mainRadarUnit,
                externalLockedEntityId);
    }

    /**
     * 绘制本机雷达的硬锁目标双实心框。
     *
     * <p>硬锁状态直接来自实际持锁的 {@link RadarUnit}，不以当前武器的 IR/RF 类型为门控；
     * 普通扫描航迹仍由 {@link #renderAimLockTarget(GuiGraphics, float)} 的原有接管条件控制。</p>
     *
     * @return 已成功把雷达硬锁目标绘制到屏幕时返回 {@code true}
     */
    private static boolean renderLocalRadarHardLock(GuiGraphics guiGraphics, float partialTick,
                                                    WeaponUnit weaponUnit, @Nullable RadarUnit lockedRadar) {
        if (lockedRadar == null || lockedRadar.getLockedEntity() == null) {
            return false;
        }
        Entity radarLocked = lockedRadar.getLockedEntity();
        // 调用本项目探测记录解析，确保锁定框只使用本机雷达实际持有的航迹数据。
        RadarUnit.DetectedObject detectedObject = resolveDetectedObject(weaponUnit, lockedRadar, radarLocked);
        if (detectedObject == null) {
            return false;
        }
        Entity detectedEntity = detectedObject.entity;
        // 调用本项目广播载具插值器：广播克隆跨广播周期平滑，普通实体保持单 Tick 插值。
        // 与 IR 锁定圈使用同一“插值后包围盒中心”锚点，保证两个通道锁定同一目标时视觉重合。
        Vec3 renderCenter = RVP_ClientBroadcastVehicleInterpolator.resolveRenderCenter(detectedEntity, partialTick);
        Vec3 screenPos = VectorUtil.worldToScreen(renderCenter);
        if (screenPos.z < 0) {
            return false;
        }
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            poseStack.translate(screenPos.x, screenPos.y, 0);
            RenderHelper.drawSquare(guiGraphics, 0, 0, 15, Color.GREEN);
            RenderHelper.drawSquare(guiGraphics, 0, 0, 10, Color.GREEN);
            alliesInfo(guiGraphics, detectedObject);
            radarInfo(guiGraphics, poseStack, detectedObject);
            // RF 导引头继续沿用原有“红双圈并入雷达框”的显示；IR 红圈由
            // RVP_MissileOverlay 按 IR 通道独立绘制，二者可在同一目标上并存。
            if (weaponUnit.getLockedEntity() == radarLocked
                    && weaponUnit.isSeekerOn()
                    && RVP_WeaponSensorHelper.effectiveSensorType(weaponUnit)
                    == WeaponUnitData.FireControlSensorType.RF) {
                GuiHelper.drawCircle(guiGraphics.pose(), 0, 0, 5, Color.RED, 0.05f, 0, 0);
                GuiHelper.drawCircle(guiGraphics.pose(), 0, 0, 4, Color.RED, 0.06f, 0, 0);
            }
        }
        poseStack.popPose();
        return true;
    }

    /** 本机是否有外置雷达条目（有外置雷达中继且探测到目标）。 */
    private static boolean hasExternalRadarEntries() {
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (vehicle == null || mc.level == null) {
            return false;
        }
        return !RVP_ExternalRadarLinkHelper.getClientEntries(vehicle, mc.level.dimension().location()).isEmpty();
    }

    /** 当前雷达合并航迹中是否包含需要跨广播周期平滑的远程载具。 */
    private static boolean hasBroadcastVehicleRadarContact(WeaponUnit weaponUnit) {
        for (RadarUnit.DetectedObject detectedObject : weaponUnit.getRadarDetectedEntities()) {
            if (detectedObject != null
                    && detectedObject.entity != null
                    && RVP_ClientBroadcastVehicleInterpolator.isBroadcastVehicle(detectedObject.entity)) {
                return true;
            }
        }
        return false;
    }

    private static void renderExternalRadarContacts(GuiGraphics guiGraphics, float partialTick,
                                                    WeaponUnit weaponUnit,
                                                    @Nullable RadarUnit mainRadarUnit,
                                                    int externalLockedEntityId) {
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        Minecraft mc = Minecraft.getInstance();
        if (vehicle == null || mc.level == null) {
            return;
        }
        int dbgCount = 0, dbgInOwn = 0, dbgLockedSkip = 0, dbgBehind = 0, dbgDrawn = 0;
        java.util.Collection<S2CExternalRadarSnapshot.Entry> entries =
                RVP_ExternalRadarLinkHelper.getClientEntries(vehicle, mc.level.dimension().location());
        for (S2CExternalRadarSnapshot.Entry entry : entries) {
            dbgCount++;
            if (mainRadarUnit != null && mainRadarUnit.getDetectedEntities().containsKey(entry.entityId())) {
                dbgInOwn++;
                continue;
            }
            if (entry.entityId() == externalLockedEntityId) {
                dbgLockedSkip++;
                continue;
            }
            Entity resolvedEntity = RVP_ExternalRadarLinkHelper.resolveClientEntity(entry.entityId());
            // 调用本项目广播载具插值器，让外置雷达扫描框与本机雷达框共享连续显示位置。
            Vec3 targetPos = resolvedEntity != null
                    ? RVP_ClientBroadcastVehicleInterpolator.resolveRenderCenter(resolvedEntity, partialTick)
                    : RVP_ExternalRadarLinkHelper.position(entry);
            Vec3 screenPos = VectorUtil.worldToScreen(targetPos);
            if (screenPos.z < 0) {
                dbgBehind++;
                continue;
            }
            dbgDrawn++;
            PoseStack poseStack = guiGraphics.pose();
            poseStack.pushPose();
            {
                poseStack.translate(screenPos.x, screenPos.y, 0);
                if (entry.ammo()) {
                    RenderHelper.drawSquareCorners(guiGraphics, 0, 0, 10, 3, Color.GREEN);
                } else {
                    RenderHelper.drawSquareCorners(guiGraphics, 0, 0, 15, 5, Color.GREEN);
                    alliesInfoExternal(guiGraphics, entry);
                    radarInfoExternal(guiGraphics, poseStack, entry, resolvedEntity, vehicle);
                }
            }
            poseStack.popPose();
        }
        // 调试（BVR 扫描框排查，默认关闭）：节流到每 20 tick 一次
        if (RVP_BVR_DEBUG && vehicle.tickCount % 20 == 0 && dbgCount > 0) {
            rvpBvrLog("ext扫描: 总数=" + dbgCount + " 已画=" + dbgDrawn
                    + " 在本机雷达表=" + dbgInOwn + " 跳过锁定=" + dbgLockedSkip + " 屏后=" + dbgBehind
                    + " | mainRadar=" + (mainRadarUnit == null ? "null" : mainRadarUnit.getId())
                    + " 本机表size=" + (mainRadarUnit == null ? "-" : mainRadarUnit.getDetectedEntities().size())
                    + " merged=" + weaponUnit.getRadarDetectedEntities().size());
        }
    }

    /** BVR 调试日志：写 gameDir/logs/rvp_radar_debug.log（客户端）。 */
    private static void rvpBvrLog(String line) {
        try {
            java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path f = dir.resolve("rvp_radar_debug.log");
            if (java.nio.file.Files.exists(f) && java.nio.file.Files.size(f) > 512 * 1024L) {
                java.nio.file.Files.delete(f);
            }
            String full = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                    + "][RVP-BVR] " + line + "\n";
            java.nio.file.Files.write(f, full.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 调试日志写失败不影响游戏
        }
    }

    private static void radarInfo(GuiGraphics guiGraphics, PoseStack poseStack, RadarUnit.DetectedObject detectedObject) {
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return;
        }
        String nctrLabel = resolveRadarNctrLabel(detectedObject.entity);
        Vec3 velocity = detectedObject.entity.getDeltaMovement();
        poseStack.pushPose();
        {
            poseStack.translate(0, 12, 0);
            RenderHelper.drawSquare(guiGraphics, 0, 0, 4, Color.GREEN);
            if (velocity.length() != 0) {
                poseStack.translate(0.5, 0, 0);
                Vec3 direction = velocity.normalize().scale(-8);
                direction = vehicle.relativeRotDirection(direction, true);
                RenderHelper.drawLine(poseStack, direction, 1f, color, -1, -1);
            }
        }
        poseStack.popPose();
        if (!nctrLabel.isBlank() && !(detectedObject.entity instanceof AmmoEntity)) {
            poseStack.pushPose();
            {
                poseStack.translate(0, 22, 0);
                poseStack.scale(0.8f, 0.8f, 0.8f);
                guiGraphics.drawCenteredString(Minecraft.getInstance().font, nctrLabel, 0, 0, Color.GREEN);
            }
            poseStack.popPose();
        }
        poseStack.pushPose();
        {
            if (detectedObject.entity instanceof AmmoEntity) {
                poseStack.translate(8, 12, 0);
            } else {
                poseStack.translate(12, -12, 0);
            }
            poseStack.scale(0.8f, 0.8f, 0.8f);
            double distance = detectedObject.detectedPosition.distanceTo(vehicle.position());
            guiGraphics.drawString(Minecraft.getInstance().font, String.format("%.2f m", distance), 0, 0, Color.GREEN, false);
            poseStack.translate(0, 12, 0);
            Vec3 approach = velocity.subtract(vehicle.getDeltaMovement());
            Vec3 relative = detectedObject.entity.position().subtract(vehicle.position());
            int sig = approach.dot(relative) > 0 ? -1 : 1;
            double approachRate = sig * approach.length() * 20;
            guiGraphics.drawString(Minecraft.getInstance().font, String.format("%.2f m/s", approachRate), 0, 0, Color.GREEN, false);
        }
        poseStack.popPose();
    }

    private static void radarInfoExternal(GuiGraphics guiGraphics, PoseStack poseStack,
                                          S2CExternalRadarSnapshot.Entry entry,
                                          @Nullable Entity resolvedEntity,
                                          AbstractVehicle vehicle) {
        String nctrLabel = entry.nctrLabel();
        Vec3 velocity = resolvedEntity != null ? resolvedEntity.getDeltaMovement() : RVP_ExternalRadarLinkHelper.velocity(entry);
        Vec3 detectedPos = resolvedEntity != null ? resolvedEntity.getBoundingBox().getCenter() : RVP_ExternalRadarLinkHelper.position(entry);
        poseStack.pushPose();
        {
            poseStack.translate(0, 12, 0);
            RenderHelper.drawSquare(guiGraphics, 0, 0, 4, Color.GREEN);
            if (velocity.lengthSqr() > 1.0E-6) {
                poseStack.translate(0.5, 0, 0);
                Vec3 direction = velocity.normalize().scale(-8);
                direction = vehicle.relativeRotDirection(direction, true);
                RenderHelper.drawLine(poseStack, direction, 1f, color, -1, -1);
            }
        }
        poseStack.popPose();
        if (!nctrLabel.isBlank()) {
            poseStack.pushPose();
            {
                poseStack.translate(0, 22, 0);
                poseStack.scale(0.8f, 0.8f, 0.8f);
                guiGraphics.drawCenteredString(Minecraft.getInstance().font, StringUtils.abbreviate(nctrLabel, 14), 0, 0, Color.GREEN);
            }
            poseStack.popPose();
        }
        poseStack.pushPose();
        {
            poseStack.translate(12, -12, 0);
            poseStack.scale(0.8f, 0.8f, 0.8f);
            double distance = detectedPos.distanceTo(vehicle.position());
            guiGraphics.drawString(Minecraft.getInstance().font, String.format("%.2f m", distance), 0, 0, Color.GREEN, false);
            poseStack.translate(0, 12, 0);
            Vec3 approach = velocity.subtract(vehicle.getDeltaMovement());
            Vec3 relative = detectedPos.subtract(vehicle.position());
            int sig = approach.dot(relative) > 0 ? -1 : 1;
            double approachRate = sig * approach.length() * 20;
            guiGraphics.drawString(Minecraft.getInstance().font, String.format("%.2f m/s", approachRate), 0, 0, Color.GREEN, false);
        }
        poseStack.popPose();
    }

    private static void alliesInfo(GuiGraphics guiGraphics, RadarUnit.DetectedObject detectedObject) {
        Team team = detectedObject.entity.getTeam();
        if (team != null && team.isAlliedTo(LocalVehiclePlayer.instance.getPlayer().getTeam())) {
            Integer teamColor = team.getColor().getColor();
            if (teamColor == null) {
                teamColor = color;
            } else {
                teamColor = 0xFF000000 | teamColor;
            }
            guiGraphics.hLine(-6, 6, -11, teamColor);
        }
    }

    private static void alliesInfoExternal(GuiGraphics guiGraphics, S2CExternalRadarSnapshot.Entry entry) {
        if (entry.affiliation() == S2CExternalRadarSnapshot.Affiliation.FRIEND
                || entry.affiliation() == S2CExternalRadarSnapshot.Affiliation.OWN) {
            guiGraphics.hLine(-6, 6, -11, Color.GREEN);
        }
    }

    private static String resolveRadarNctrLabel(Entity entity) {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return "";
        }
        RadarUnit radarUnit = resolveNctrRadar(weaponUnit, entity);
        if (radarUnit == null) {
            return "";
        }
        RadarUnitData data = radarUnit.getData();
        if (!(data instanceof RadarUnitDataExt ext)) {
            return "";
        }
        String mode = ext.ywzj_rvp$getNctrMode();
        if ("NONE".equalsIgnoreCase(mode)) {
            return "";
        }
        String label = "EARLY".equalsIgnoreCase(mode) ? resolveEarlyNctrLabel(entity) : resolveModernNctrLabel(entity);
        if (label == null || label.isBlank()) {
            return "";
        }
        return StringUtils.abbreviate(label, 14);
    }

    private static RadarUnit.DetectedObject resolveDetectedObject(WeaponUnit weaponUnit, RadarUnit preferredRadar, Entity entity) {
        if (weaponUnit == null || entity == null) {
            return null;
        }
        if (preferredRadar != null) {
            RadarUnit.DetectedObject direct = preferredRadar.getDetectedEntities().get(entity.getId());
            if (direct != null) {
                return direct;
            }
        }
        for (RadarUnit.DetectedObject detectedObject : weaponUnit.getRadarDetectedEntities()) {
            if (detectedObject != null && detectedObject.entity != null && detectedObject.entity.getId() == entity.getId()) {
                return detectedObject;
            }
        }
        return null;
    }

    private static RadarUnit resolveNctrRadar(WeaponUnit weaponUnit, Entity entity) {
        if (weaponUnit == null || entity == null) {
            return null;
        }
        RadarUnit lockedRadar = RVP_RadarRoleHelper.getLockedRadar(weaponUnit);
        if (isRadarNctrCapable(lockedRadar) && lockedRadar.getDetectedEntities().containsKey(entity.getId())) {
            return lockedRadar;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit == lockedRadar || !isRadarNctrCapable(radarUnit)) {
                continue;
            }
            if (radarUnit.getDetectedEntities().containsKey(entity.getId())) {
                return radarUnit;
            }
        }
        return isRadarNctrCapable(lockedRadar) ? lockedRadar : null;
    }

    private static boolean isRadarNctrCapable(RadarUnit radarUnit) {
        if (radarUnit == null) {
            return false;
        }
        RadarUnitData data = radarUnit.getData();
        if (!(data instanceof RadarUnitDataExt ext)) {
            return false;
        }
        return !"NONE".equalsIgnoreCase(ext.ywzj_rvp$getNctrMode());
    }

    private static String resolveEarlyNctrLabel(Entity entity) {
        String special = RVP_RadarContactHelper.resolveShortNctr(entity);
        if (special != null && !special.isBlank()) {
            return special;
        }
        if (entity instanceof FixedWingVehicle) {
            return "JET";
        }
        if (entity instanceof RotaryWingVehicle) {
            return "HELI";
        }
        // 船载具适配（2026-09-20）：本体新增 VesselVehicle 后早期 NCTR 模式不再显示 "?"
        if (entity instanceof org.ywzj.vehicle.entity.vehicle.VesselVehicle) {
            return "SHIP";
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            String label = RVP_RadarContactHelper.resolveBulletRadarLabel(bullet, Float.MAX_VALUE);
            if (label != null && !label.isBlank()) {
                return label;
            }
        }
        return "?";
    }

    private static String resolveModernNctrLabel(Entity entity) {
        String special = RVP_RadarContactHelper.resolveShortNctr(entity);
        if (special != null && !special.isBlank()) {
            return special;
        }
        if (entity instanceof AbstractVehicle vehicle) {
            return VehicleUIPresetCache.getNctrName(vehicle.getVehicleId());
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            String radarLabel = RVP_RadarContactHelper.resolveBulletRadarLabel(bullet, Float.MAX_VALUE);
            if (radarLabel != null && !radarLabel.isBlank()) {
                return radarLabel;
            }
            ResourceLocation weaponId = bullet.getWeaponId();
            if (weaponId != null) {
                String display = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                        .map(index -> index.data().getName())
                        .filter(name -> name != null && !name.isBlank())
                        .orElse(weaponId.getPath().toUpperCase());
                if (!display.isBlank()) {
                    return display;
                }
            }
        }
        return resolveEarlyNctrLabel(entity);
    }

}
