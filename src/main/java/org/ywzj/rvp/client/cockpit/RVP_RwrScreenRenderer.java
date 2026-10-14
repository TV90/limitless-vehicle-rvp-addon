package org.ywzj.rvp.client.cockpit;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.util.Map;

/**
 * RWR 告警屏内容渲染器（2026-10-14 座舱屏幕批，2026-10-14b 回退至 20:32 基线）。
 *
 * <p>同心圆 + 威胁方位射线 + 机型文字 + 导弹/锁定告警红色 1Hz 闪烁（语义与 HUD RWR
 * 同款）。圆盘底为 GuiHelper.drawCircle 实心熄屏黑（不透明，先于罗盘元素画）。
 * 已知遗留问题：drawCircle（HUD_CIRCLE 自定义 shader）在离屏 RT 环境下覆盖区域的
 * alpha 通道会被写成 0（RGB 正常）——中心圆盘区域直采时透出底下机体骨图案，
 * 2026-10-14 用户实机反馈，待后续修复（详见交接文档）。</p>
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
        // UV 占比对齐 MCHR（RWR_UV_DIAMETER_RATIO=0.75）：圆盘直径=短边×0.75，四周留 12.5% 防出血。
        int outer = (int) (Math.min(width, height) * 0.375f);
        var pose = graphics.pose();
        // 圆盘底不单独画（2026-10-14b 定案）：GuiHelper.drawCircle 的 HUD_CIRCLE 自定义
        // shader 在离屏 RT 下把覆盖区 alpha 写成 0（实测圆盘内 a=0、四角 a=255），任何
        // 颜色的实心底/半透明罩都会透出底下机体骨。全屏 fill 已是统一不透明熄屏黑，
        // 圆盘视觉由最外圈绿环勾出即可。

        // 顶部告警文字（导弹来袭 > 被锁定，红色 1Hz 闪烁与 HUD 同款语义；屏骨字体放大）
        WarningReceiver receiver = vehicle.warningReceiver;
        if (receiver != null) {
            boolean missile = anyWarn(receiver, WarnType.MISSILE_LAUNCH);
            boolean lock = anyWarn(receiver, WarnType.RADAR_LOCK);
            long blink = System.currentTimeMillis() / 500 % 2;
            if (missile) {
                RVP_CockpitCanvas.drawScaledCenteredString(graphics, pose,
                        Component.translatable("ui.missile_incoming").getString(),
                        cx, cy - outer - 14, blink == 0 ? Color.RED : 0xFF551410);
            } else if (lock) {
                RVP_CockpitCanvas.drawScaledCenteredString(graphics, pose,
                        Component.translatable("ui.being_locked").getString(),
                        cx, cy - outer - 14, blink == 0 ? Color.RED : 0xFF551410);
            }
        }

        // 罗盘圈组（比例沿用 HUD RWR 的 3/20/25，映射到外径 outer；屏骨字体放大）。
        // 圈用 Canvas 自绘（position_color 立即模式，绕开 HUD_CIRCLE shader 的 RT alpha 失效）。
        RVP_CockpitCanvas.drawScaledCenteredString(graphics, pose, "RWR", cx, cy - outer + 10, Color.GREEN);
        RVP_CockpitCanvas.drawDiscImmediate(pose, cx, cy, 3, Color.GREEN);
        RVP_CockpitCanvas.drawRingImmediate(pose, cx, cy, outer * 0.92f + 2, outer * 0.92f - 2, Color.GREEN);
        RVP_CockpitCanvas.drawRingImmediate(pose, cx, cy, outer * 0.74f + 2, outer * 0.74f - 2, Color.GREEN);

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
                    // 锁定/来袭威胁的虚线粗射线（2026-10-15 红线合规化）：原调本体
                    // RenderHelper.drawLine——它是立即模式且自带 enableBlend 却不复位，
                    // 本体这套 draw* 工具链在离屏 RT 下已被证明不可靠（交接文档红线①）。
                    // 现改为 RVP_CockpitCanvas 的立即绘制（与屏内其它图元同一条已验路径），
                    // dash=4 / gap=1 的观感照旧。
                    float dx = (float) direction.x;
                    float dz = (float) direction.z;
                    float len = (float) Math.sqrt(dx * dx + dz * dz);
                    if (len > 1.0E-4F) {
                        float ux = dx / len;
                        float uz = dz / len;
                        for (float cur = 0.0F; cur < len; cur += 5.0F) {
                            float end = Math.min(cur + 4.0F, len);
                            RVP_CockpitCanvas.drawLineImmediate(pose, ux * cur, uz * cur,
                                    ux * end, uz * end, 2.0F, Color.GREEN);
                        }
                    }
                }
                direction = direction.normalize().scale(scale * outer * 0.88f);
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
                        info == null || info.isEmpty() ? "?" : info, 0, 0, color);
            }
            pose.popPose();
        }
    }

    /** @return 是否存在指定类型告警。 */
    private static boolean anyWarn(WarningReceiver receiver, WarnType type) {
        return receiver.targets.values().stream().anyMatch(target -> target.warnType() == type);
    }

    /**
     * 威胁实体解析：优先本客户端实体，回退服务端实体快照（与 RVP_RadarOverlay 同款）。
     *
     * <p>2026-10-15：主路径改走载具所在世界（{@code vehicle.level()}），不再经本体
     * {@code LocalVehiclePlayer.getPlayer()}——本体状态被上游清空时该引用及其
     * {@code serverEntities} 会一并失效，威胁符号会整片消失。</p>
     */
    private static Entity resolveEntity(AbstractVehicle vehicle, int entityId) {
        Entity entity = vehicle.level().getEntity(entityId);
        if (entity != null) {
            return entity;
        }
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        LocalVehiclePlayer.ServerEntity serverEntity =
                lvp == null ? null : lvp.serverEntities.get(entityId);
        return serverEntity == null ? null : serverEntity.entity;
    }

    /** 延迟取 MC 字体的小封装（避免静态初始化时序问题）。 */
    private static net.minecraft.client.gui.Font font() {
        return net.minecraft.client.Minecraft.getInstance().font;
    }
}
