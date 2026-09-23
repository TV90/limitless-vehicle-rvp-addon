package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.weapon.ahead.RVP_AheadProgrammer;
import org.ywzj.rvp.weapon.ahead.RVP_AheadSolution;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.client.state.RVP_MachinegunLeadState;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public class RVP_MachinegunLeadOverlay implements IGuiOverlay {
    /** 机炮预瞄圈与目标连线使用的半透明绿色。 */
    private static final int LEAD_COLOR = (Color.GREEN & 0x00FFFFFF) | (0xDD << 24);

    @Override
    public void render(ForgeGui gui, net.minecraft.client.gui.GuiGraphics guiGraphics, float partialTick,
                       int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null) {
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        RVP_WeaponData data = weaponUnit == null ? null : RVP_MachinegunLeadSolver.resolveCurrentWeaponData(weaponUnit);
        if (weaponUnit == null || data == null) {
            if (weaponUnit != null) {
                RVP_MachinegunLeadState.clear(weaponUnit);
            }
            return;
        }
        // 调用本项目每 tick 解算缓存，使 HUD 与火控复用同一份结果且不随渲染帧率重复积分。
        // HUD 始终使用当前渲染 partialTick 插值真实目标中心，使连线锚点与锁定框逐帧重合。
        RVP_LeadSolution solution = RVP_MachinegunLeadState.resolveCurrent(weaponUnit, partialTick);
        RVP_AheadSolution aheadSolution = null;
        if (RVP_AheadProgrammer.isAheadWeapon(data)) {
            // 调用本项目 AHEAD 编程入口并复用缓存提前量，避免客户端为同一目标再次执行弹道解算。
            aheadSolution = RVP_AheadProgrammer.solveFromResolvedLead(
                    data, weaponUnit, weaponUnit.aimContext(), solution);
            renderAheadReadout(guiGraphics, screenWidth, screenHeight, aheadSolution);
        }
        if (solution == null) {
            return;
        }

        Vec3 leadScreen = VectorUtil.worldToScreen(solution.leadWorldPos());
        Vec3 targetScreen = VectorUtil.worldToScreen(solution.targetWorldPos());
        if (leadScreen == null || leadScreen.z < 0) {
            return;
        }

        PoseStack poseStack = guiGraphics.pose();
        if (targetScreen != null && targetScreen.z >= 0) {
            poseStack.pushPose();
            poseStack.translate(targetScreen.x, targetScreen.y, 0);
            RenderHelper.drawLine(
                    poseStack,
                    new Vec3(leadScreen.x - targetScreen.x, 0, leadScreen.y - targetScreen.y),
                    1.1f,
                    LEAD_COLOR,
                    4,
                    3
            );
            poseStack.popPose();
        }

        poseStack.pushPose();
        poseStack.translate(leadScreen.x, leadScreen.y, 0);
        GuiHelper.drawCircle(poseStack, 0, 0, 7, LEAD_COLOR, 0.05f, 0f, 1f);
        GuiHelper.drawCircle(poseStack, 0, 0, 2, LEAD_COLOR, 0.12f, 0f, 1f);
        poseStack.popPose();
    }

    private static void renderAheadReadout(net.minecraft.client.gui.GuiGraphics guiGraphics, int screenWidth,
                                           int screenHeight, RVP_AheadSolution solution) {
        Component text = solution != null && solution.isValid()
                ? Component.translatable("overlay.ywzj_rvp.ahead.range", solution.programmedDistanceMeters())
                : Component.translatable("overlay.ywzj_rvp.ahead.pending");
        guiGraphics.drawString(
                Minecraft.getInstance().font,
                text,
                15,
                15,
                LEAD_COLOR,
                false
        );
    }
}
