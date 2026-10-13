package org.ywzj.rvp.client.cockpit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.StringUtils;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.util.Map;

/**
 * RWR 告警屏内容渲染器（2026-10-14 座舱屏幕批）：威胁罗盘绘制到 512² 屏画布。
 *
 * <p>绘制原语搬自 {@code RVP_RadarOverlay}（同心圆+方位射线+距离映射+机型文字+淡出），
 * 数据源为本体 {@code WarningReceiver.targets}（本地乘客视角既有同步，零新增网络）。
 * 屏面内容随本车航向旋转（射线方位=威胁相对本车朝向），与 HUD RWR 语义一致。</p>
 */
public final class RVP_RwrScreenRenderer {

    /** 搜索告警机型文字的平顶淡出阈值（毫秒），与 RVP_RadarOverlay 同语义。 */
    private static final float SEARCH_TEXT_STEADY_MS = 600f;

    private RVP_RwrScreenRenderer() {}

    /** 渲染 RWR 罗盘帧。 */
    public static void render(GuiGraphics graphics, int width, int height, AbstractVehicle vehicle) {
        // 熄屏底色（不透明深灰近黑，transparent 渲染下 alpha=1 区域为实心）
        graphics.fill(0, 0, width, height, 0xFF0A0C0E);
        int cx = width / 2;
        int cy = height / 2;
        int outer = (int) (Math.min(width, height) * 0.45f);
        var pose = graphics.pose();

        // 顶部告警文字（导弹来袭 > 被锁定，红色 1Hz 闪烁与 HUD 同款语义）
        WarningReceiver receiver = vehicle.warningReceiver;
        if (receiver != null) {
            boolean missile = anyWarn(receiver, WarnType.MISSILE_LAUNCH);
            boolean lock = anyWarn(receiver, WarnType.RADAR_LOCK);
            long blink = System.currentTimeMillis() / 500 % 2;
            if (missile) {
                graphics.drawCenteredString(font(),
                        Component.translatable("ui.missile_incoming").getString(), cx, cy - outer - 14,
                        blink == 0 ? Color.RED : 0xFF551410);
            } else if (lock) {
                graphics.drawCenteredString(font(),
                        Component.translatable("ui.being_locked").getString(), cx, cy - outer - 14,
                        blink == 0 ? Color.RED : 0xFF551410);
            }
        }

        // 罗盘圈组（比例沿用 HUD RWR 的 3/20/25，映射到外径 outer）
        graphics.drawCenteredString(font(), "RWR", cx, cy - outer + 10, Color.GREEN);
        GuiHelper.drawCircle(pose, cx, cy, 3, Color.GREEN, 0.1f, 0, 0);
        GuiHelper.drawCircle(pose, cx, cy, outer * 0.92f, Color.GREEN, 0.01f, 0, 0);
        GuiHelper.drawCircle(pose, cx, cy, outer * 0.74f, Color.GREEN, 0.01f, 0, 0);
        GuiHelper.drawCircle(pose, cx, cy, outer, Color.BG_DARK_DIM, 1f, 0, 0);

        if (receiver == null) {
            return;
        }
        // 威胁符号：方位=威胁相对本车朝向（车头朝上），距离线性映射 0~512 格到 0.12R~1.0R
        for (Map.Entry<Integer, WarningReceiver.WarnTarget> warnEntry : receiver.targets.entrySet()) {
            Entity entity = resolveEntity(vehicle, warnEntry.getKey());
            if (entity == null) {
                continue;
            }
            WarningReceiver.WarnTarget warnTarget = warnEntry.getValue();
            double distance = entity.position().distanceTo(vehicle.position());
            double scale = Math.min(1, distance / 512);
            // 调用本体相对方位解算：车体系下威胁方向（x=右/z=下对应屏面 x/y），随航向旋转。
            Vec3 direction = vehicle
                    .relativeRotDirection(entity.position().subtract(vehicle.position()), true)
                    .normalize()
                    .scale(scale * -outer);
            pose.pushPose();
            {
                if (warnTarget.warnType() == WarnType.RADAR_LOCK || warnTarget.warnType() == WarnType.MISSILE_LAUNCH) {
                    // 调用本体绘制工具：锁定/来袭威胁画粗射线（与 HUD RWR 同款）。
                    RenderHelper.drawLine(pose, direction, 0.8f, Color.GREEN, 4, 1);
                }
                direction = direction.normalize().scale(scale * outer * 1.12);
                pose.translate(cx + direction.x, cy + direction.z, 0);
                pose.scale(0.9f, 0.9f, 0.9f);
                String info = warnTarget.info();
                int color = Color.GREEN;
                if (warnTarget.warnType() == WarnType.RADAR_SEARCH) {
                    // 调用本项目淡出语义（与 RVP_RadarOverlay 平顶淡出一致）：停刷新 600→1600ms 渐隐。
                    float timeDiff = (float) (System.currentTimeMillis() - warnTarget.receivedTime());
                    float alpha = timeDiff <= SEARCH_TEXT_STEADY_MS ? 1.0f
                            : 1.0f - Math.min(1000f, timeDiff - SEARCH_TEXT_STEADY_MS) / 1000f;
                    int alphaInt = Math.max(4, (int) (alpha * 255));
                    color = (alphaInt << 24) | (Color.GREEN & 0x00FFFFFF);
                }
                graphics.drawCenteredString(font(),
                        StringUtils.isEmpty(info) ? "?" : info, 0, 0, color);
            }
            pose.popPose();
        }
    }

    /** @return 是否存在指定类型告警。 */
    private static boolean anyWarn(WarningReceiver receiver, WarnType type) {
        return receiver.targets.values().stream().anyMatch(target -> target.warnType() == type);
    }

    /** 威胁实体解析：优先本客户端实体，回退服务端实体快照（与 RVP_RadarOverlay 同款）。 */
    private static Entity resolveEntity(AbstractVehicle vehicle, int entityId) {
        Entity entity = LocalVehiclePlayer.instance.getPlayer().level().getEntity(entityId);
        if (entity != null) {
            return entity;
        }
        LocalVehiclePlayer.ServerEntity serverEntity =
                LocalVehiclePlayer.instance.serverEntities.get(entityId);
        return serverEntity == null ? null : serverEntity.entity;
    }

    /** 延迟取 MC 字体的小封装（避免静态初始化时序问题）。 */
    private static net.minecraft.client.gui.Font font() {
        return net.minecraft.client.Minecraft.getInstance().font;
    }
}
