package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_ClientArmState;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_IrHudProfile;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.client.state.RVP_ClientBroadcastVehicleInterpolator;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * RVP IR A2A 导弹离轴限位圈 + 头瞄圈。
 * 限位圈精确复刻 VehicleAimAtOverlay 导引头大圈算法，仅参数改用 guide_head_max_angle。
 * [RVP] ARM 反辐射导弹分支：本体大圈已由 {@code WeaponUnitGetSeekerFovMixin} 归零屏蔽，
 * 此处自渲染离轴圈（同 IR 算法）+ 预选锁定雷达上的 RF 风格红双小圈头瞄圈。
 */
public class RVP_MissileOverlay implements IGuiOverlay {

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) return;
        var weaponOpt = weaponUnit.getCurrentWeapon();
        if (weaponOpt.isEmpty()) return;
        Object rawData = weaponOpt.get().getData();
        if (!(rawData instanceof RVP_WeaponData data)) return;
        if (!data.isHomingProjectile()) return;
        // [RVP] ARM 分支：离轴圈 + 预选头瞄圈自渲染（不依赖武器站 sensor 配置，载具间表现统一）
        if (data.usesGuidanceType(RVP_EnumGuidanceType.ARM)) {
            renderArmCircles(guiGraphics, weaponUnit, data, partialTick);
            return;
        }
        if (!data.usesGuidanceType(RVP_EnumGuidanceType.IR)) return;
        if (RVP_IrHudProfile.resolve(RVP_IrLockHelper.getLaunchAltitudeRange(data)) == RVP_IrHudProfile.GROUND) return;

        float fov = data.resolveLaunchOffAxisLockAngle();
        if (fov < 5f) return;

        double x = screenWidth * 0.5D;
        double y = screenHeight * 0.5D;
        Vec3 weaponHitPosO = weaponUnit.weaponHitPosO;
        Vec3 weaponHitPos = weaponUnit.weaponHitPos;
        if (weaponHitPos != null) {
            Vec3 screenHitPos = VectorUtil.worldToScreen(weaponHitPos);
            if (screenHitPos != null && screenHitPos.z >= 0) {
                Vec3 screenHitPosO = weaponHitPosO != null ? VectorUtil.worldToScreen(weaponHitPosO) : null;
                if (screenHitPosO != null) {
                    x = Mth.lerp(partialTick, screenHitPosO.x, screenHitPos.x);
                    y = Mth.lerp(partialTick, screenHitPosO.y, screenHitPos.y);
                } else {
                    x = screenHitPos.x;
                    y = screenHitPos.y;
                }
            }
        }

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(x, y, 0);

        float alpha = 0.4f + (float) Math.sin((double) weaponUnit.getLockCoolingTick() / 5 * Math.PI) * 0.2f;
        int aimCircleColor = (Color.WHITE & 0x00FFFFFF) | ((int) (alpha * 255) << 24);

        // 导引头大圈 — 精确复刻 VehicleAimAtOverlay
        if (weaponUnit.isSeekerOn() && weaponUnit.getLockedEntity() == null && !data.isEnableIrHmd()) {
            GuiHelper.drawCircle(poseStack, 0, 0, 15, aimCircleColor, 0.03f, 0, 0);
        }
        // 目的：离轴圈绘制基准与锁定保持判定一致（2026-09-15 修复大离轴"圈内却脱锁"）——
        // 判定按 off_axis_stacks_with_station_rotation 选基准：true=武器站当前指向，
        // false=中立安装轴（PL-10 等缺省，离轴圈锚定机头轴）；此前圈恒画在站指向、
        // 判定锥钉在中立轴，站被伺服转走后圈内目标实际已在锥外 → 脱锁 → 锁定音消失。
        // 非 HMD 武器的保持判定以武器站当前指向为基准，维持原绘制。
        boolean irHmdActive = RVP_ClientHmdState.getInstance().isIrHmd();
        boolean stackWithStation = irHmdActive
                && RVP_ClientHmdState.getInstance().isIrOffAxisStacksWithStationRotation();
        Vec2 rot = weaponUnit.worldRot();
        Vec3 centerDir;
        Vec3 upDir;
        Vec3 downDir;
        if (stackWithStation) {
            centerDir = VectorUtil.rotToVec(rot.x, rot.y).normalize();
            upDir = VectorUtil.rotToVec(rot.x - fov, rot.y).normalize();
            downDir = VectorUtil.rotToVec(rot.x + fov, rot.y).normalize();
        } else {
            // 中立安装轴基准：与判定同源（worldVec(0,0)），±fov 沿云台俯仰方向取边界
            centerDir = RVP_IrLockHelper.resolveIrBoresightDir(weaponUnit, false);
            upDir = weaponUnit.worldVec(-fov, 0f).normalize();
            downDir = weaponUnit.worldVec(fov, 0f).normalize();
        }
        Vec3 screenPosUp = VectorUtil.worldToScreen(weaponUnit.worldPivotPosition()
                .add(upDir.scale(256)));
        Vec3 screenPosDown = VectorUtil.worldToScreen(weaponUnit.worldPivotPosition()
                .add(downDir.scale(256)));
        Vec3 screenPosCenter = VectorUtil.worldToScreen(weaponUnit.worldPivotPosition()
                .add(centerDir.scale(256)));

        if (screenPosUp != null && screenPosDown != null && screenPosCenter != null) {
            double px = screenPosDown.x - screenPosUp.x;
            double py = screenPosDown.y - screenPosUp.y;
            float r = (float) Math.sqrt(px * px + py * py) / 2;
            poseStack.pushPose();
            poseStack.translate(screenPosCenter.x - x, screenPosCenter.y - y, 0);
            GuiHelper.drawCircle(poseStack, 0, 0, r, aimCircleColor, 0.01f, 0, 0);
            poseStack.popPose();
        }

        poseStack.popPose();

        // 头瞄圈：只展示真实的 IR 导引头锁定。
        // HMD 激活时调用本项目 HMD 状态读取 IR 通道自己的目标，禁止把雷达写入的共享火控目标
        // 误画成 IR 锁定圈；非 HMD IR 模式保持读取本体 WeaponUnit 锁定目标的既有行为。
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        Entity locked = null;
        if (weaponUnit.isSeekerOn()) {
            locked = hmdState.isIrHmd() ? hmdState.getLockedEntity() : weaponUnit.getLockedEntity();
        }
        if (locked != null) {
            // 调用本项目广播载具插值器，与雷达硬锁绿框共用连续锚点，避免红圈单独阶梯跳动。
            Vec3 renderCenter = RVP_ClientBroadcastVehicleInterpolator.resolveRenderCenter(locked, partialTick);
            Vec3 sp = VectorUtil.worldToScreen(renderCenter);
            if (sp != null && sp.z >= 0) {
                float sa = Math.sin((double) weaponUnit.getLockCoolingTick() / 5 * Math.PI) > 0 ? 0.8f : 1.0f;
                int sc = (Color.RED & 0x00FFFFFF) | ((int) (sa * 255) << 24);
                poseStack.pushPose();
                poseStack.translate(sp.x, sp.y, 0);
                GuiHelper.drawCircle(poseStack, 0, 0, 15, sc, 0.03f, 0, 0);
                poseStack.popPose();
            }
        }
    }

    /**
     * [RVP] ARM 反辐射导弹自渲染圈（seekerOn 时）：
     * 离轴圈复用 IR 离轴圈算法（武器站指向为中心，半径 = guide_head_max_angle 投影），
     * 颜色/呼吸节奏与 IR 一致；头瞄圈画在预选锁定雷达位置上（RF 导引头锁定风格红双小圈
     * r=5/4，闪烁节奏复刻 IR 头瞄圈），与 {@link RVP_ArmOverlay} 的双层绿框叠加。
     */
    private static void renderArmCircles(GuiGraphics guiGraphics, WeaponUnit weaponUnit,
                                         RVP_WeaponData data, float partialTick) {
        // 目的：未开导引头不绘制（与 IR 分支 isSeekerOn 门控一致）
        if (!weaponUnit.isSeekerOn()) {
            return;
        }
        float fov = data.resolveLaunchOffAxisLockAngle();
        if (fov < 5f) {
            return;
        }
        PoseStack poseStack = guiGraphics.pose();

        // [RVP] 格斗模式基准：离轴框中心/半径基于机体速度方向（机体怎么飞框怎么走），
        // 不再跟随武器站指向；速度近零（地面/悬停）回退机头朝向（Entity.lookAngle）
        AbstractVehicle playerVehicle = LocalVehiclePlayer.instance.vehicle;
        if (playerVehicle == null) {
            return;
        }
        Vec3 velocity = playerVehicle.getDeltaMovement();
        Vec3 baseDir = velocity.lengthSqr() > 1.0E-4
                ? velocity.normalize()
                : playerVehicle.getLookAngle();
        Vec3 basePos = playerVehicle.position().add(0.0D, playerVehicle.getBoundingBox().getYsize() * 0.5D, 0.0D);
        Vec2 baseRot = VectorUtil.vecToRot(baseDir);
        Vec3 screenPosUp = VectorUtil.worldToScreen(basePos
                .add(VectorUtil.rotToVec(baseRot.x - fov, baseRot.y).normalize().scale(256)));
        Vec3 screenPosDown = VectorUtil.worldToScreen(basePos
                .add(VectorUtil.rotToVec(baseRot.x + fov, baseRot.y).normalize().scale(256)));
        Vec3 screenPosCenter = VectorUtil.worldToScreen(basePos
                .add(baseDir.scale(256)));
        if (screenPosUp != null && screenPosDown != null && screenPosCenter != null) {
            double px = screenPosDown.x - screenPosUp.x;
            double py = screenPosDown.y - screenPosUp.y;
            float r = (float) Math.sqrt(px * px + py * py) / 2;
            float alpha = 0.4f + (float) Math.sin((double) weaponUnit.getLockCoolingTick() / 5 * Math.PI) * 0.2f;
            int aimCircleColor = (Color.GREEN & 0x00FFFFFF) | ((int) (alpha * 255) << 24);
            // 目的：内接——四角框对角线 = 离轴圆直径（size = r√2，四角落在圆上）
            int size = Math.max((int) (r * (float) Math.sqrt(2.0D)), 8);
            int corner = Math.max((int) (r / 4), 4);
            RenderHelper.drawSquareCorners(guiGraphics, (int) screenPosCenter.x, (int) screenPosCenter.y,
                    size, corner, aimCircleColor);
        }

        // 目的：无预选锁定时，在武器站瞄准点显示绿色小四角框（替代本体 RF 导引头双白圈，
        // 本体双圈已由 VehicleAimAtOverlaySeekerColorMixin 对 ARM 抑制）；位置与 IR 小圈
        // 同款插值瞄准点（weaponHitPos / weaponHitPosO 投影）
        int lockedVehicleId = RVP_ClientArmState.getInstance().getLockedVehicleId();
        int lockedRadarIndex = RVP_ClientArmState.getInstance().getLockedRadarIndex();
        boolean preselectLocked = lockedVehicleId >= 0 && lockedRadarIndex >= 0;
        if (!preselectLocked) {
            Vec3 weaponHitPos = weaponUnit.weaponHitPos;
            Vec3 weaponHitPosO = weaponUnit.weaponHitPosO;
            if (weaponHitPos != null) {
                Vec3 screenHitPos = VectorUtil.worldToScreen(weaponHitPos);
                if (screenHitPos != null && screenHitPos.z >= 0) {
                    double ax;
                    double ay;
                    Vec3 screenHitPosO = weaponHitPosO != null ? VectorUtil.worldToScreen(weaponHitPosO) : null;
                    if (screenHitPosO != null) {
                        ax = Mth.lerp(partialTick, screenHitPosO.x, screenHitPos.x);
                        ay = Mth.lerp(partialTick, screenHitPosO.y, screenHitPos.y);
                    } else {
                        ax = screenHitPos.x;
                        ay = screenHitPos.y;
                    }
                    RenderHelper.drawSquareCorners(guiGraphics, (int) ax, (int) ay, 10, 3, Color.GREEN);
                }
            }
        }

        // 头瞄圈：预选锁定雷达位置上的 RF 风格红双小圈（本体 RF 导引头锁定圈 r=5/4 同款）
        RVP_ClientArmState state = RVP_ClientArmState.getInstance();
        if (preselectLocked) {
            for (RVP_ClientArmState.Contact contact : state.getContacts()) {
                if (contact.vehicleId() != lockedVehicleId || contact.radarIndex() != lockedRadarIndex) {
                    continue;
                }
                Vec3 sp = VectorUtil.worldToScreen(contact.position());
                if (sp != null && sp.z >= 0) {
                    float sa = Math.sin((double) weaponUnit.getLockCoolingTick() / 5 * Math.PI) > 0 ? 0.8f : 1.0f;
                    int sc = (Color.RED & 0x00FFFFFF) | ((int) (sa * 255) << 24);
                    poseStack.pushPose();
                    poseStack.translate(sp.x, sp.y, 0);
                    GuiHelper.drawCircle(poseStack, 0, 0, 5, sc, 0.05f, 0, 0);
                    GuiHelper.drawCircle(poseStack, 0, 0, 4, sc, 0.06f, 0, 0);
                    poseStack.popPose();
                }
                break;
            }
        }
    }
}
