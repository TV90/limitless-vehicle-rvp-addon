package org.ywzj.rvp.client.screen;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.network.C2SSelectModdingSubWeapon;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.List;

/**
 * RVP 自定义武器变体选择屏幕（ApricityUI HTML 渲染）。
 *
 * <p>左侧列出载具上所有 {@code modding_only_multi} 武器站，右侧列出选中站的子武器；
 * 点击右侧某项发送 {@link C2SSelectModdingSubWeapon} 切换。与本体改造工具屏幕同为
 * AUI 实现，可由本体屏幕直接 {@code setScreen} 打开。</p>
 *
 * <p>分组载波（如 T90M/VT4 的 AP 组 + HE 组 {@code merge_into_previous_slot} 并槽）按
 * <b>每个分组合各一栏</b>展示（标题取该槽 JSON 的 {@code save_id}），点击组内弹种后
 * 外层大组同步切到该组——与 ZBL08A 那种独立两栏的观感一致，F 键大组切换行为不变。</p>
 */
public class RVP_AuiVariantScreen extends ApricityScreen {

    private static final String TEMPLATE = "screens/rvp_variants.html";

    /** 一个武器站条目。isPylon=true 表示挂架队（进下半区，金色）；group≥0 表示分组载波的组序号。 */
    private record StationEntry(String partId, int weaponIndex, WeaponUnit unit, VehicleMultiWeapons multi,
                                boolean isPylon, int groupIndex, String groupTitle) {
        StationEntry(String partId, int weaponIndex, WeaponUnit unit, VehicleMultiWeapons multi) {
            this(partId, weaponIndex, unit, multi, false, -1, null);
        }
    }

    private final AbstractVehicle vehicle;
    private final Screen parent;
    private final List<StationEntry> stations = new ArrayList<>();
    private Document document;
    private Element ammoStationList;
    private Element pylonStationList;
    private Element weaponList;
    private Element weaponEmpty;
    private Element closeButton;
    private Element previewAnchor;
    private int selectedStationIndex;
    /** 本地乐观选中的子武器索引：点击弹种立即高亮，不等服务器回包（否则第一次点击无视觉反馈像"要双击"）。 */
    private int selectedSubIndex = -1;
    private boolean previewDragging;
    private float viewScale = 1.0f;
    private float viewRotX;
    private float viewRotY;
    private float viewShiftX;
    private float viewShiftY;

    public RVP_AuiVariantScreen(AbstractVehicle vehicle, Screen parent) {
        super(TEMPLATE);
        this.vehicle = vehicle;
        this.parent = parent;
        rebuildStations();
        setPauseGame(false);
        setShowDefaultBackground(false);
    }

    private void rebuildStations() {
        stations.clear();
        // 自定义弹种（多弹种，F 键屏蔽）：进上半区；分组载波按组拆栏
        for (var configEntry : RVP_VehicleExtendedConfigManager.INSTANCE.getModdingGroupEntries(vehicle)) {
            PartUnit<?> partUnit = vehicle.getPartUnit(configEntry.partId()).orElse(null);
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            if (configEntry.weaponIndex() < 0 || configEntry.weaponIndex() >= weaponUnit.weapons.size()) {
                continue;
            }
            // 分组载波：外层 multi 的第 group 组即该栏展示的子武器列表
            VehicleMultiWeapons target = null;
            if (configEntry.groupIndex() >= 0) {
                if (weaponUnit.weapons.get(configEntry.weaponIndex()) instanceof VehicleMultiWeapons outer
                        && configEntry.groupIndex() < outer.getSubWeapons().size()
                        && outer.getSubWeapons().get(configEntry.groupIndex()) instanceof VehicleMultiWeapons groupMulti) {
                    target = groupMulti;
                }
            } else {
                target = RVP_VehicleExtendedConfigManager.INSTANCE
                        .resolveModdingTargetMulti(weaponUnit, configEntry.weaponIndex());
            }
            if (target == null) {
                continue;
            }
            stations.add(new StationEntry(configEntry.partId(), configEntry.weaponIndex(), weaponUnit, target,
                    false, configEntry.groupIndex(), configEntry.groupId()));
        }
        // 自定义挂架（按 partUnitId 分组，挂架队）：进下半区
        var mountGroups = new java.util.LinkedHashMap<String, List<org.ywzj.rvp.config.RVP_CustomMountConfig>>();
        for (var cfg : org.ywzj.rvp.config.RVP_CustomMountConfigCache.get(vehicle.getVehicleId())) {
            mountGroups.computeIfAbsent(cfg.partUnitId(), k -> new ArrayList<>()).add(cfg);
        }
        for (var entry : mountGroups.entrySet()) {
            String partId = entry.getKey();
            PartUnit<?> partUnit = vehicle.getPartUnit(partId).orElse(null);
            if (!(partUnit instanceof WeaponUnit weaponUnit)) continue;
            // 纯挂架站：用挂架配置本身构造一个可点条目（单弹种挂架也需可选中以预览）
            // 将挂架队的所有 weaponId 去重后作为"弹种"列表展示
            java.util.LinkedHashSet<net.minecraft.resources.ResourceLocation> weaponIds = new java.util.LinkedHashSet<>();
            for (var cfg : entry.getValue()) {
                if (cfg.weaponId() != null) weaponIds.add(cfg.weaponId());
            }
            if (weaponIds.isEmpty()) continue;
            // 为纯挂架构造一个虚拟 multi 壳：取该 WeaponUnit 的首个武器作为占位 multi
            VehicleMultiWeapons multi = null;
            for (var w : weaponUnit.weapons) {
                if (w instanceof VehicleMultiWeapons m) { multi = m; break; }
            }
            // 若挂架队本身不是多弹种，仍需一个可展示的 multi 壳：从 weaponIds 构造一个临时的显示用 multi
            // 纯挂架：用该 WeaponUnit 的全部顶层武器构造一个"伪多弹种"视图
            // 这样挂架队A的 AGM-A/AGM-B（variable_aam 的 pl_12/pl_15）或 AGM 的 yj_91/kd_88a 都会完整显示
            if (multi == null) {
                if (weaponUnit.weapons.isEmpty()) {
                    continue;
                }
                // 收集该挂架队全部顶层武器（可能 2 个，如 pl_12+pl_15）
                multi = new VehicleMultiWeapons(weaponUnit.getVehicle(), weaponUnit, 0,
                        new ArrayList<>(weaponUnit.weapons), "mount_" + partId);
            }
            // 标记为挂架站（isPylon=true），避免与上半区多弹种重复
            stations.add(new StationEntry(partId, 0, weaponUnit, multi, true, -1, null));
        }
        if (selectedStationIndex >= stations.size()) {
            selectedStationIndex = Math.max(0, stations.size() - 1);
        }
    }

    @Override
    protected void init() {
        super.init();
        document = getLinkedDocument();
        if (document == null) {
            return;
        }
        ammoStationList = document.getElementById("ammoStationList");
        // 兼容旧 ID（单栏布局）与新 ID（分栏布局：上自定义弹种/下自定义挂架）
        if (ammoStationList == null) {
            ammoStationList = document.getElementById("station-list");
        }
        pylonStationList = document.getElementById("pylonStationList");
        weaponList = document.getElementById("weapon-list");
        weaponEmpty = document.getElementById("weapon-list-empty");
        if (weaponEmpty == null) {
            weaponEmpty = document.getElementById("weapon-empty");
        }
        closeButton = document.getElementById("close-button");
        if (closeButton != null) {
            closeButton.addEventListener("click", event -> onClose());
        }
        previewAnchor = document.getElementById("weapon-vehicle-preview");
        if (previewAnchor == null) {
            previewAnchor = document.getElementById("modding-preview");
        }
        // 初始化预览视角（与本体一致）
        viewRotX = 180 - vehicle.getXRot();
        viewRotY = 180 + (net.minecraft.client.Minecraft.getInstance().player != null ? net.minecraft.client.Minecraft.getInstance().player.getYRot() : 0);
        buildStationList();
        refreshWeaponPanel();
    }

    private void buildStationList() {
        if (ammoStationList == null && pylonStationList == null) {
            return;
        }
        if (ammoStationList != null) ammoStationList.setTextContent("");
        if (pylonStationList != null) pylonStationList.setTextContent("");
        for (int index = 0; index < stations.size(); index++) {
            StationEntry station = stations.get(index);
            boolean isPylon = station.isPylon();
            Element entry = document.createElement("div");
            entry.setClassName("weapon-entry" + (isPylon ? " pylon-active" : " ammo-active"));
            entry.setAttribute("role", "button");
            entry.setAttribute("tabindex", "0");
            Element name = document.createElement("span");
            name.setClassName("weapon-entry-name");
            // 挂架队显示 part 名；分组栏优先 save_id，普通弹种站显示 multi 名
            String display;
            if (isPylon) {
                display = station.partId();
            } else if (station.groupTitle() != null && !station.groupTitle().isBlank()) {
                display = station.groupTitle();
            } else {
                display = station.multi.getDisplayName().getString();
            }
            name.setTextContent(display);
            Element count = document.createElement("span");
            count.setClassName("weapon-entry-count");
            int total = isPylon
                    ? org.ywzj.rvp.config.RVP_CustomMountConfigCache.get(vehicle.getVehicleId()).stream()
                            .filter(c -> c.partUnitId().equals(station.partId())).toList().size()
                    : station.multi.getSubWeapons().size();
            int current = isPylon ? 0 : station.multi.getSubWeapons().indexOf(station.multi.getSelectedWeapon());
            count.setTextContent(isPylon ? total + " 项" : (current + 1) + "/" + total);
            entry.appendChild(name);
            entry.appendChild(count);
            int selectionIndex = index;
            entry.addEventListener("click", event -> selectStation(selectionIndex));
            Element targetList = isPylon ? pylonStationList : ammoStationList;
            if (targetList != null) {
                targetList.appendChild(entry);
            } else if (ammoStationList != null) {
                ammoStationList.appendChild(entry);
            }
        }
        refreshStationActive();
    }

    private void selectStation(int index) {
        if (index < 0 || index >= stations.size()) {
            return;
        }
        selectedStationIndex = index;
        // 切站后重置本地乐观选中索引，重新从服务器当前状态读取
        selectedSubIndex = -1;
        refreshStationActive();
        refreshWeaponPanel();
    }

    private void refreshStationActive() {
        // 单栏兼容 + 分栏：分别刷新两栏的高亮（全局索引）
        if (ammoStationList != null) {
            List<Element> entries = ammoStationList.querySelectorAll(".weapon-entry");
            for (int i = 0; i < entries.size(); i++) {
                // entries 在分栏布局中是连续的全局索引的子集，需映射回全局
                // 简化：按全局索引高亮（当前仅上半区有内容）
                entries.get(i).setClassName(i == selectedStationIndex ? "weapon-entry active ammo-active" : "weapon-entry ammo-active");
            }
        }
        if (pylonStationList != null) {
            List<Element> pylonEntries = pylonStationList.querySelectorAll(".weapon-entry");
            // 下半区全局索引偏移 = 上半区数量
            int ammoCount = ammoStationList != null ? ammoStationList.querySelectorAll(".weapon-entry").size() : 0;
            for (int i = 0; i < pylonEntries.size(); i++) {
                int globalIdx = ammoCount + i;
                pylonEntries.get(i).setClassName(globalIdx == selectedStationIndex ? "weapon-entry active pylon-active" : "weapon-entry pylon-active");
            }
        }
        // 兼容旧单栏
        if (ammoStationList == null && pylonStationList == null) {
            // fallback 已在 buildStationList 处理
        }
    }

    private void refreshWeaponPanel() {
        if (weaponList == null) {
            return;
        }
        weaponList.setTextContent("");
        if (stations.isEmpty()) {
            if (weaponEmpty != null) {
                weaponEmpty.setAttribute("style", "");
            }
            return;
        }
        StationEntry station = stations.get(selectedStationIndex);
        boolean isPylon = station.isPylon();
        List<net.minecraft.world.entity.Entity> noop = new ArrayList<>();
        // 混合站：挂架队（isPylon）展示该 WeaponUnit 的全部顶层武器；多弹种站展示 multi 的子弹种
        List<? extends org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon<?>> displayWeapons;
        if (isPylon) {
            displayWeapons = new ArrayList<>(station.unit.weapons);
        } else {
            displayWeapons = station.multi.getSubWeapons();
        }
        for (int index = 0; index < displayWeapons.size(); index++) {
            var sub = displayWeapons.get(index);
            Element entry = document.createElement("div");
            entry.setClassName("weapon-entry");
            entry.setAttribute("role", "button");
            entry.setAttribute("tabindex", "0");
            Element nameSpan = document.createElement("span");
            nameSpan.setClassName("weapon-entry-name");
            nameSpan.setTextContent(sub.getDisplayName().getString());
            entry.appendChild(nameSpan);
            int subIndex = index;
            entry.addEventListener("click", event -> {
                // 本地乐观切换：立即记录选中并刷新高亮，不等服务器回包
                selectedSubIndex = subIndex;
                if (isPylon) {
                    // 纯挂架：走本体顶层选弹（partIndex/weaponIndex）
                    int partIndex = -1;
                    var parts = vehicle.getPartUnits();
                    for (int i = 0; i < parts.size(); i++) {
                        if (parts.get(i) == station.unit) { partIndex = i; break; }
                    }
                    if (partIndex >= 0) {
                        net.minecraftforge.network.PacketDistributor.SERVER.noArg();
                        org.ywzj.vehicle.network.Channel.CHANNEL.sendToServer(
                                new org.ywzj.vehicle.network.message.ClientVehicleSelectPartWeapon(
                                        vehicle.getId(), partIndex, subIndex));
                    }
                } else {
                    // 普通弹种/分组栏：groupIndex = -1 为整槽单组（旧寻址），≥0 为分组载波组序号
                    RVP_Network.CHANNEL.sendToServer(new C2SSelectModdingSubWeapon(
                            vehicle.getId(), station.partId(), station.weaponIndex(), subIndex, station.groupIndex()));
                }
                Minecraft.getInstance().getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                refreshWeaponPanel();
            });
            weaponList.appendChild(entry);
        }
        if (weaponEmpty != null) {
            weaponEmpty.setAttribute("style", "display: none;");
        }
        // 高亮选中：挂架队按 WeaponUnit 当前武器索引，弹种队按 multi 选中；
        // 分组栏仅当外层大组当前激活本组时才显示组内高亮（外层在另一组时本组无激活项）
        List<Element> subs = weaponList.querySelectorAll(".weapon-entry");
        int current;
        if (selectedSubIndex >= 0 && isGroupActive(station)) {
            current = selectedSubIndex;
        } else if (station.isPylon()) {
            current = station.unit.getCurrentWeaponIndex();
            if (current < 0 || current >= station.unit.weapons.size()) current = 0;
        } else if (station.groupIndex() >= 0) {
            current = -1;
        } else {
            current = station.multi.getSubWeapons().indexOf(station.multi.getSelectedWeapon());
        }
        for (int i = 0; i < subs.size(); i++) {
            subs.get(i).setClassName(i == current ? "weapon-entry active" : "weapon-entry");
        }
    }

    /** 分组载波的外层大组当前是否激活本站对应的组（用于组内高亮判定）。 */
    private boolean isGroupActive(StationEntry station) {
        if (station.groupIndex() < 0) {
            return true;
        }
        if (station.weaponIndex() < 0 || station.weaponIndex() >= station.unit.weapons.size()
                || !(station.unit.weapons.get(station.weaponIndex()) instanceof VehicleMultiWeapons outer)) {
            return false;
        }
        return outer.getSelectedIndex() == station.groupIndex();
    }

    @Override
    public void onClose() {
        // 关闭后返回上一屏幕（本体改造工具）
        super.onClose();
        Minecraft.getInstance().setScreen(parent);
    }

    private record Bounds(double x, double y, double x2, double y2) {
        boolean contains(double mx, double my) { return mx >= x && mx <= x2 && my >= y && my <= y2; }
    }

    private Bounds getPreviewBounds() {
        if (previewAnchor == null || document == null) return null;
        Element.DOMRect rect = previewAnchor.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) return null;
        double scale = document.getViewport().renderScale();
        return new Bounds(rect.x * scale, rect.y * scale, (rect.x + rect.width) * scale, (rect.y + rect.height) * scale);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        Bounds bounds = getPreviewBounds();
        if (bounds != null && bounds.contains(mouseX, mouseY)) {
            viewScale = net.minecraft.util.Mth.clamp(viewScale * (float) Math.pow(1.1, delta), 0.1f, 10.0f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Bounds bounds = getPreviewBounds();
        if ((button == 0 || button == 1) && bounds != null && bounds.contains(mouseX, mouseY)) {
            previewDragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (previewDragging && button == 0) {
            viewRotY += (float) dragX;
            viewRotX += (float) dragY;
            return true;
        }
        if (previewDragging && button == 1) {
            viewShiftX += (float) dragX;
            viewShiftY += (float) dragY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (previewDragging && (button == 0 || button == 1)) {
            previewDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderVehiclePreview(guiGraphics);
    }

    private void renderVehiclePreview(net.minecraft.client.gui.GuiGraphics guiGraphics) {
        Bounds bounds = getPreviewBounds();
        if (bounds == null) {
            return;
        }
        double width = bounds.x2() - bounds.x();
        double height = bounds.y2() - bounds.y();
        double scale = Math.min(width, height) * 1.5 / Math.max(vehicle.getStructureLength(), 3) * viewScale;
        guiGraphics.enableScissor((int) bounds.x(), (int) bounds.y(), (int) Math.ceil(bounds.x2()), (int) Math.ceil(bounds.y2()));
        boolean scissorEnabled = true;
        com.mojang.blaze3d.vertex.PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        try {
            poseStack.translate((float) (bounds.x() + bounds.x2()) / 2 + viewShiftX, (float) (bounds.y() + bounds.y2()) / 2 + viewShiftY, 512);
            poseStack.mulPoseMatrix(new org.joml.Matrix4f().scaling((float) scale, (float) scale, (float) -scale));
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(viewRotX));
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(viewRotY));
            net.minecraft.world.phys.Vec3 centerOffset = vehicle.getBoundingBox().getCenter().subtract(vehicle.position());
            poseStack.translate((float) -centerOffset.x, (float) -centerOffset.y, (float) -centerOffset.z);
            net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher();
            dispatcher.setRenderShadow(false);
            com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();
            com.mojang.blaze3d.systems.RenderSystem.runAsFancy(() ->
                    dispatcher.render(vehicle, 0, 0, 0, 0, 1.0F, poseStack, guiGraphics.bufferSource(), 15728880));
            guiGraphics.flush();
            guiGraphics.disableScissor();
            scissorEnabled = false;
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, false);
        } finally {
            dispatcherResetShadow();
            com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
            poseStack.popPose();
            if (scissorEnabled) {
                guiGraphics.disableScissor();
            }
        }
    }

    private void dispatcherResetShadow() {
        net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().setRenderShadow(true);
    }
}