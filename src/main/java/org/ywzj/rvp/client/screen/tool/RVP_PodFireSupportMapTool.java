package org.ywzj.rvp.client.screen.tool;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.firesupport.RVP_ClientPodFireSupportState;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.firesupport.C2SSavePodFireSupportSelection;
import org.ywzj.rvp.weapon.data.RVP_PodFireSupportEntryData;

import java.util.List;
import java.util.Locale;

/**
 * 坐标指示吊舱的战术地图火力配置工具（2026-10-13 坐标指示吊舱×火力支援整合）。
 *
 * <p>与终端 {@code RVP_FireSupportMapTool} 的差异：**只能选择内联条目并保存，不能直接
 * 发起打击**——实际打击由吊舱方块标记自动触发。侧栏仅含条目单选列表、当前已保存
 * 选择与保存按钮；地图画布保留背景但不接受选点（坐标来自标记射线）。</p>
 */
public class RVP_PodFireSupportMapTool implements RVP_TacticalMapTool {

    /** 侧栏主题色（与终端工具同款暖金）。 */
    private static final int ACCENT = 0xFFD9A544;
    /** 条目行高。 */
    private static final int ROW_HEIGHT = 18;

    /** 吊舱武器 path（保存包键）。 */
    private final String weaponPath;
    /** 当前武器的内联条目（构造时快照）。 */
    private final List<RVP_PodFireSupportEntryData> entries;
    /** 本地草稿选中项（打开时=服务端已保存选择）。 */
    private String draftEntryId;

    /**
     * @param weaponPath 吊舱武器 path
     * @param entries    当前武器内联条目（非空，注入点已保证）
     */
    public RVP_PodFireSupportMapTool(String weaponPath, List<RVP_PodFireSupportEntryData> entries) {
        this.weaponPath = weaponPath;
        this.entries = List.copyOf(entries);
        String saved = RVP_ClientPodFireSupportState.INSTANCE.getSelection(weaponPath);
        this.draftEntryId = saved == null ? "" : saved;
    }

    @Override
    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath("rvp", "pod_fire_support");
    }

    @Override
    public void initialize(RVP_TacticalMapHost host) {
    }

    @Override
    public void tick(RVP_TacticalMapHost host) {
    }

    @Override
    public boolean mouseClicked(RVP_TacticalMapHost host, double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        int left = host.sideLeft() + 7;
        int right = host.sideRight() - 7;
        int top = host.mapTop() + 31;
        // 条目单选列表点击
        for (int i = 0; i < entries.size(); i++) {
            int rowTop = top + i * ROW_HEIGHT;
            if (mouseX >= left && mouseX <= right && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT - 2) {
                RVP_PodFireSupportEntryData entry = entries.get(i);
                // 调用本项目选择状态：再点已选项 = 取消选择（保存空值即清除）。
                draftEntryId = entry.getId().equals(draftEntryId) ? "" : entry.getId();
                return true;
            }
        }
        // 保存按钮（条目列表下方两行处）
        int buttonTop = top + entries.size() * ROW_HEIGHT + 10;
        if (mouseX >= left && mouseX <= right && mouseY >= buttonTop && mouseY <= buttonTop + 18) {
            // 调用本项目网络通道：上行保存请求（服务端校验条目存在后持久化并 ACK）。
            RVP_Network.CHANNEL.sendToServer(new C2SSavePodFireSupportSelection(weaponPath, draftEntryId));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(RVP_TacticalMapHost host, double mouseX, double mouseY,
                                int button, double dragX, double dragY) {
        return false;
    }

    @Override
    public boolean mouseReleased(RVP_TacticalMapHost host, double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public void renderOverlay(RVP_TacticalMapHost host, GuiGraphics graphics,
                              int mouseX, int mouseY, float partialTick) {
        int left = host.sideLeft() + 7;
        int right = host.sideRight() - 7;
        int top = host.mapTop() + 31;
        // 标题（对齐终端工具：mapTop+7 起 18px 高的侧栏头）
        graphics.fill(left, host.mapTop() + 7, right, host.mapTop() + 25, 0xDD111820);
        graphics.fill(left, host.mapTop() + 7, right, host.mapTop() + 8, ACCENT);
        graphics.drawString(host.font(), tr("gui.ywzj_rvp.pod_fire_support.title"), left + 5,
                host.mapTop() + 12, 0xFFFFFFFF, false);
        // 条目单选列表
        for (int i = 0; i < entries.size(); i++) {
            RVP_PodFireSupportEntryData entry = entries.get(i);
            int rowTop = top + i * ROW_HEIGHT;
            boolean selected = entry.getId().equals(draftEntryId);
            boolean hover = mouseX >= left && mouseX <= right && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT - 2;
            graphics.fill(left, rowTop, right, rowTop + ROW_HEIGHT - 2,
                    selected ? 0xFF5F4D2E : (hover ? 0xFF3A3F46 : 0xFF30343A));
            graphics.fill(left, rowTop, left + 2, rowTop + ROW_HEIGHT - 2,
                    selected ? ACCENT : 0xFF666A70);
            graphics.drawString(host.font(), entry.getDisplayCn(), left + 8, rowTop + 5,
                    selected ? 0xFFFFFFFF : 0xFFC6CBD1, false);
            if (selected) {
                graphics.drawString(host.font(), "●", right - 12, rowTop + 5, ACCENT, false);
            }
        }
        // 当前已保存选择（服务端权威回显）
        String saved = RVP_ClientPodFireSupportState.INSTANCE.getSelection(weaponPath);
        String savedText = saved == null || saved.isBlank()
                ? tr("gui.ywzj_rvp.pod_fire_support.saved_none")
                : displayNameOf(saved);
        int savedTop = top + entries.size() * ROW_HEIGHT;
        graphics.drawString(host.font(), tr("gui.ywzj_rvp.pod_fire_support.saved"),
                left, savedTop + 2, 0xFF90959B, false);
        graphics.drawString(host.font(), savedText, left, savedTop + 12, ACCENT, false);
        // 保存按钮
        int buttonTop = savedTop + 28;
        graphics.fill(left, buttonTop, right, buttonTop + 18, 0xFF5F4D2E);
        graphics.fill(left, buttonTop, right, buttonTop + 1, ACCENT);
        String label = tr("gui.ywzj_rvp.pod_fire_support.save");
        Minecraft minecraft = Minecraft.getInstance();
        graphics.drawString(minecraft.font, label,
                left + Math.max(4, (right - left - minecraft.font.width(label)) / 2), buttonTop + 5,
                0xFFFFFFFF, false);
        // 底部提示：打击由吊舱标记自动触发
        graphics.drawString(host.font(), tr("gui.ywzj_rvp.pod_fire_support.hint"),
                left, buttonTop + 24, 0xFF90959B, false);
    }

    @Override
    public void onClose() {
    }

    /** @return 条目显示名；草稿指向已失效条目时给"已失效"标记。 */
    private String displayNameOf(String entryId) {
        for (RVP_PodFireSupportEntryData entry : entries) {
            if (entry.getId().equals(entryId)) {
                return entry.getDisplayCn();
            }
        }
        return tr("gui.ywzj_rvp.pod_fire_support.saved_stale");
    }

    /** 翻译键转可显示文本。 */
    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
