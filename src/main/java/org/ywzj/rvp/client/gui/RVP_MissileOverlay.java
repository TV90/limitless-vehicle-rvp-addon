package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.client.gui.VehicleAimAtOverlay;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * RVP IR A2A 导弹离轴限位圈 + 头瞄圈。
 * 限位圈精确复刻 VehicleAimAtOverlay 导引头大圈算法，仅参数改用 guide_head_max_angle。
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
        if (data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE) return;
        if (!data.usesGuidanceType(RVP_EnumGuidanceType.IR)) return;
        if (data.getLockMinHeight() <= 0) return;

        float fov = data.getMaxGuideHeadAngle();
        if (fov < 5f) return;

        double x = VehicleAimAtOverlay.getScreenAimX();
        double y = VehicleAimAtOverlay.getScreenAimY();
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
        Vec2 rot = weaponUnit.worldRot();
        Vec3 screenPosUp = VectorUtil.worldToScreen(weaponUnit.worldPivotPosition()
                .add(VectorUtil.rotToVec(rot.x - fov, rot.y).normalize().scale(256)));
        Vec3 screenPosDown = VectorUtil.worldToScreen(weaponUnit.worldPivotPosition()
                .add(VectorUtil.rotToVec(rot.x + fov, rot.y).normalize().scale(256)));
        Vec3 screenPosCenter = VectorUtil.worldToScreen(weaponUnit.worldPivotPosition()
                .add(VectorUtil.rotToVec(rot.x, rot.y).normalize().scale(256)));

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

        // 头瞄圈：锁定后跟踪目标
        Entity locked = weaponUnit.getLockedEntity();
        if (locked != null) {
            double ex = Mth.lerp(partialTick, locked.xo, locked.getX());
            double ey = Mth.lerp(partialTick, locked.yo, locked.getY());
            double ez = Mth.lerp(partialTick, locked.zo, locked.getZ());
            Vec3 off = locked.getBoundingBox().getCenter().subtract(locked.position());
            Vec3 sp = VectorUtil.worldToScreen(new Vec3(ex, ey, ez).add(off));
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
}
