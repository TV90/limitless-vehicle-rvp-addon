package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_DamageNumberState;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.List;

/**
 * 生物头顶伤害飘字（{@code -xx}）。
 *
 * <p>数据来自服务端权威结算（{@link org.ywzj.rvp.network.S2CDamageNumber}），本层只做视觉：
 * 世界坐标上飘 {@link RVP_DamageNumberState#RISE_BLOCKS} 格、投影到屏幕、按寿命淡出。
 * 直击与爆炸同 tick 的多段伤害在服务端已合并成一条，所以一次命中只出现一个数字。</p>
 *
 * <p>渲染在世界坐标投影点上、与相机朝向无关（不做 billboard 排序），因此高速移动时
 * 数字会随命中点留在原地——这是刻意的：跟随实体会让数字在快速目标上甩出视野。</p>
 */
public class RVP_DamageNumberOverlay implements IGuiOverlay {

    /** 普通伤害字色（白）。 */
    private static final int COLOR_NORMAL = 0xFFFFFFFF;

    /** 中等伤害字色（金）：>= 30。 */
    private static final int COLOR_HEAVY = 0xFFFFC24A;

    /** 高伤害字色（红）：>= 100（125mm 直击、近距爆炸一类）。 */
    private static final int COLOR_LETHAL = 0xFFFF5A4A;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick,
                       int screenWidth, int screenHeight) {
        List<RVP_DamageNumberState.Entry> entries = RVP_DamageNumberState.entries();
        if (entries.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        RVP_DamageNumberState.prune(now);

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack poseStack = guiGraphics.pose();
        for (RVP_DamageNumberState.Entry entry : entries) {
            float progress = (now - entry.bornMs()) / (float) RVP_DamageNumberState.LIFETIME_MS;
            if (progress >= 1.0F) {
                continue;
            }
            // 世界空间上飘 + 轻微水平漂移，多个数字不至于完全重叠
            Vec3 world = entry.pos().add(
                    entry.drift() * 0.35D * progress,
                    RVP_DamageNumberState.RISE_BLOCKS * progress,
                    0.0D);
            Vec3 screen = VectorUtil.worldToScreen(world);
            if (screen == null || screen.z < 0) {
                continue;
            }
            String text = "-" + Math.round(entry.amount());
            int width = font.width(text);
            // 后 40% 快速淡出，前段保持满不透明便于读数
            float fade = progress < 0.6F ? 1.0F : 1.0F - (progress - 0.6F) / 0.4F;
            int alpha = (int) (net.minecraft.util.Mth.clamp(fade, 0.0F, 1.0F) * 255.0F) << 24;
            float scale = entry.amount() >= 100.0F ? 1.25F : entry.amount() >= 30.0F ? 1.1F : 1.0F;

            poseStack.pushPose();
            poseStack.translate(screen.x, screen.y, 0.0D);
            poseStack.scale(scale, scale, 1.0F);
            guiGraphics.drawString(font, text, -width / 2, 0, alpha | colorOf(entry.amount()), true);
            poseStack.popPose();
        }
    }

    private static int colorOf(float amount) {
        if (amount >= 100.0F) {
            return COLOR_LETHAL;
        }
        if (amount >= 30.0F) {
            return COLOR_HEAVY;
        }
        return COLOR_NORMAL;
    }

}
