package org.ywzj.rvp.client.screen.tool;

import org.ywzj.rvp.client.screen.RVP_MapChrome;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.firesupport.RVP_ClientPodFireSupportState;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.firesupport.C2SSavePodFireSupportSelection;
import org.ywzj.rvp.weapon.data.RVP_PodFireSupportEntryData;

import java.util.List;

/**
 * 坐标指示吊舱的战术地图火力配置工具（2026-10-13 坐标指示吊舱×火力支援整合）。
 *
 * <p>与终端 {@code RVP_FireSupportMapTool} 的差异：**只能选择内联条目并保存，不能直接
 * 发起打击**——实际打击由吊舱方块标记自动触发。侧栏仅含条目单选列表、当前已保存
 * 选择与保存按钮；地图画布保留背景但不接受选点（坐标来自标记射线）。</p>
 */
public class RVP_PodFireSupportMapTool implements RVP_TacticalMapTool {

    /** 第一条可见选项，工具条和保存区不随它滚动。 */
    private int firstRow;
    /** 每个选项的 GUI 行高。 */
    private static final int ROW_HEIGHT = 18;

    private RVP_MapChrome.ListArea rows(RVP_TacticalMapHost host) {
        return new RVP_MapChrome.ListArea(host.mapTop()+31, host.mapBottom()-72, ROW_HEIGHT);
    }
    private int saveY(RVP_TacticalMapHost host) { return host.mapBottom()-25; }

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
        return new ResourceLocation("rvp", "pod_fire_support");
    }

    @Override
    public void initialize(RVP_TacticalMapHost host) {
    }

    @Override
    public void tick(RVP_TacticalMapHost host) {
    }

    @Override
    public boolean mouseClicked(RVP_TacticalMapHost host, double mouseX, double mouseY, int button) {
        if (button != 0 || mouseX < host.sideLeft()+7 || mouseX >= host.sideRight()-7) return false;
        RVP_MapChrome.ListArea list = rows(host);
        firstRow = list.clamp(firstRow, entries.size());
        int index = list.rowAt(mouseY, firstRow, entries.size());
        if (index >= 0) {
            String id = entries.get(index).getId();
            draftEntryId = id.equals(draftEntryId) ? "" : id;
            return true;
        }
        if (mouseY >= saveY(host) && mouseY < saveY(host)+19) {
            // 使用原有保存包，将界面草稿交给服务端校验。
            RVP_Network.CHANNEL.sendToServer(new C2SSavePodFireSupportSelection(weaponPath, draftEntryId));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(RVP_TacticalMapHost host, double x, double y, double delta) {
        RVP_MapChrome.ListArea list = rows(host);
        if (x < host.sideLeft() || x >= host.sideRight() || y < list.top() || y >= list.bottom()) return false;
        firstRow = list.clamp(firstRow - (int)Math.signum(delta), entries.size());
        return true;
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
        int left = host.sideLeft()+7, right = host.sideRight()-7, width = right-left;
        int header = host.mapTop()+6;
        RVP_MapChrome.title(graphics, host.font(), tr("gui.ywzj_rvp.pod_fire_support.title"), left,header,right,header+20);
        RVP_MapChrome.ListArea list = rows(host);
        firstRow = list.clamp(firstRow, entries.size());
        for (int i=firstRow; i<Math.min(entries.size(), firstRow+list.visible()); i++) {
            var entry = entries.get(i);
            int y = list.y(i,firstRow);
            boolean selected = entry.getId().equals(draftEntryId);
            boolean hover = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y+ROW_HEIGHT;
            RVP_MapChrome.strip(graphics,left,y,right,y+ROW_HEIGHT,selected,hover);
            RVP_MapChrome.line(graphics, host.font(),entry.getDisplayCn(),left+5,y+5,width-10,selected ? RVP_MapChrome.INK : RVP_MapChrome.TEXT);
        }
        int save = saveY(host);
        String saved = RVP_ClientPodFireSupportState.INSTANCE.getSelection(weaponPath);
        String value = saved == null || saved.isBlank() ? tr("gui.ywzj_rvp.pod_fire_support.saved_none") : displayNameOf(saved);
        RVP_MapChrome.line(graphics,host.font(),tr("gui.ywzj_rvp.pod_fire_support.saved")+value,left,save-42,width,RVP_MapChrome.TEXT);
        var hint = host.font().split(Component.translatable("gui.ywzj_rvp.pod_fire_support.hint"),width);
        for (int i=0; i<Math.min(3,hint.size()); i++) graphics.drawString(host.font(),hint.get(i),left,save-31+i*10,RVP_MapChrome.MUTED,false);
        RVP_MapChrome.strip(graphics,left,save,right,save+19,true,false);
        String label = tr("gui.ywzj_rvp.pod_fire_support.save");
        RVP_MapChrome.buttonLabel(graphics, host.font(), net.minecraft.network.chat.Component.literal(label), left, save, right, save + 19, RVP_MapChrome.INK);
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
