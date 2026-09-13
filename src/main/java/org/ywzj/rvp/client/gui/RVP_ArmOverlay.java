package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.StringUtils;
import org.ywzj.rvp.client.state.RVP_ClientArmState;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.List;

/**
 * [RVP] ARM 导引头目标 HUD（客户端）。
 *
 * <p>标记风格与本体雷达弹（RF）锁定 UI 对齐（{@code VehicleScopeOverlay.renderAimLockTarget}）：
 * 未锁定辐射源 = 单层绿框（本体"雷达可锁定的目标"同款 {@code drawSquare(15)}），
 * 预选锁定辐射源 = 双层绿框 15+10（本体"雷达锁定目标"同款），框下方渲染目标雷达的
 * {@code radar_type} 型号文案（排版对齐本体 NCTR 文案：0.8 缩放居中）。</p>
 */
public class RVP_ArmOverlay {

    /** 未锁定辐射源框尺寸（对齐本体 renderAimLockTarget drawSquare(15)）。 */
    private static final int CONTACT_SQUARE = 15;
    /** 锁定辐射源内层框尺寸（对齐本体 drawSquare(10)）。 */
    private static final int LOCKED_SQUARE_INNER = 10;
    /** 未锁定辐射源框颜色（对齐本体 Color.GREEN）。 */
    private static final int CONTACT_COLOR = Color.GREEN;
    /** 锁定文案与框的间距 / 缩放（对齐本体 radarInfo NCTR 排版）。 */
    private static final int LABEL_OFFSET_Y = 22;
    private static final float LABEL_SCALE = 0.8f;
    /** 型号文案最大长度（对齐 NCTR 截断）。 */
    private static final int LABEL_MAX_LENGTH = 14;

    public static void render(GuiGraphics guiGraphics) {
        RVP_ClientArmState state = RVP_ClientArmState.getInstance();
        if (!state.isActive()) {
            return;
        }

        List<RVP_ClientArmState.Contact> contacts = state.getContacts();
        if (contacts.isEmpty()) {
            return;
        }

        int screenWidth = guiGraphics.guiWidth();
        int screenHeight = guiGraphics.guiHeight();
        int lockedVehicleId = state.getLockedVehicleId();
        int lockedRadarIndex = state.getLockedRadarIndex();

        for (RVP_ClientArmState.Contact contact : contacts) {
            Vec3 screen = VectorUtil.worldToScreen(contact.position());
            if (screen.z <= 0) {
                continue;
            }
            double sx = screen.x;
            double sy = screen.y;
            if (sx < 0 || sx > screenWidth || sy < 0 || sy > screenHeight) {
                continue;
            }

            boolean isLocked = contact.vehicleId() == lockedVehicleId && contact.radarIndex() == lockedRadarIndex;

            // 目的：与本体雷达弹锁定 UI 同款风格——drawSquare 以中心平移后 (0,0) 绘制，
            // 未锁定单层绿框，锁定双层绿框（15+10）
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(sx, sy, 0);
            RenderHelper.drawSquare(guiGraphics, 0, 0, CONTACT_SQUARE, CONTACT_COLOR);
            if (isLocked) {
                RenderHelper.drawSquare(guiGraphics, 0, 0, LOCKED_SQUARE_INNER, CONTACT_COLOR);
            }
            guiGraphics.pose().popPose();

            // 目的：锁定目标框下方渲染其雷达型号（radar_type），排版对齐本体 NCTR 文案
            if (isLocked) {
                String radarType = resolveRadarType(contact.vehicleId(), contact.radarIndex());
                if (!radarType.isBlank()) {
                    guiGraphics.pose().pushPose();
                    guiGraphics.pose().translate(sx, sy + LABEL_OFFSET_Y, 0);
                    guiGraphics.pose().scale(LABEL_SCALE, LABEL_SCALE, LABEL_SCALE);
                    guiGraphics.drawCenteredString(Minecraft.getInstance().font,
                            StringUtils.abbreviate(radarType, LABEL_MAX_LENGTH), 0, 0, CONTACT_COLOR);
                    guiGraphics.pose().popPose();
                }
            }
        }
    }

    /**
     * 解析锁定辐射源的雷达型号（radar_type，载具 JSON 雷达单元配置）。
     * 客户端：vehicleId → 实体 → 部件表按 radarIndex 找 {@link RadarUnit} → {@link RadarUnitData}。
     * 解析失败 / 未配置 / ECM 伪脉冲（radarIndex=-1，无真实雷达）返回空串（不渲染文案）。
     */
    private static String resolveRadarType(int vehicleId, int radarIndex) {
        if (radarIndex < 0) {
            return "";
        }
        Entity entity = Minecraft.getInstance().level == null
                ? null : Minecraft.getInstance().level.getEntity(vehicleId);
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return "";
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit radarUnit && radarUnit.getIndex() == radarIndex) {
                String type = radarUnit.getData().getRadarType();
                return type == null ? "" : type.trim();
            }
        }
        return "";
    }
}
