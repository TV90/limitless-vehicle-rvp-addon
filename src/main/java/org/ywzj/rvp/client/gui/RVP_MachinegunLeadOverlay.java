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
import org.ywzj.vehicle.vehicle.pojo.AimContext;

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
        // 调用本项目每 tick 解算缓存；HUD 在相邻 EMA 相位补偿解间做帧插值，与实际火控保持一致。
        // 目标中心与提前点使用相同 partialTick，使连线锚点和预瞄圈保持同一时间基准。
        RVP_LeadSolution solution = RVP_MachinegunLeadState.resolveDisplayCurrent(weaponUnit, partialTick);
        RVP_AheadSolution aheadSolution = null;
        if (RVP_AheadProgrammer.isAheadWeapon(data)) {
            // 调用本项目发射单元解析，确保 AHEAD HUD 的测距原点与实际炮口一致。
            WeaponUnit launchWeaponUnit = RVP_MachinegunLeadSolver.resolveCurrentLaunchWeaponUnit(weaponUnit);
            if (launchWeaponUnit == null) {
                launchWeaponUnit = weaponUnit;
            }
            // 调用本体实际发射单元瞄准上下文，为 AHEAD 编程距离提供真实炮口。
            AimContext launchAim = launchWeaponUnit.aimContext();
            // 调用本项目 AHEAD 编程入口并复用缓存提前量，避免客户端为同一目标再次执行弹道解算。
            aheadSolution = RVP_AheadProgrammer.solveFromResolvedLead(
                    data, weaponUnit, launchAim, solution);
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
