package org.ywzj.rvp.client.screen;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
 * RVP 二选一武器变体选择屏幕（ApricityUI HTML 渲染）。
 *
 * <p>左侧列出载具上所有 {@code modding_only_multi} 武器站，右侧列出选中站的子武器；
 * 点击右侧某项发送 {@link C2SSelectModdingSubWeapon} 切换。与本体改造工具屏幕同为
 * AUI 实现，可由本体屏幕直接 {@code setScreen} 打开。</p>
 */
public class RVP_AuiVariantScreen extends ApricityScreen {

    private static final String TEMPLATE = "screens/rvp_variants.html";

    /** 一个 modding_only_multi 武器站条目。 */
    private record StationEntry(String partId, int weaponIndex, WeaponUnit unit, VehicleMultiWeapons multi) {
    }

    private final AbstractVehicle vehicle;
    private final Screen parent;
    private final List<StationEntry> stations = new ArrayList<>();
    private Document document;
    private Element stationList;
    private Element stationEmpty;
    private Element weaponList;
    private Element weaponEmpty;
    private Element closeButton;
    private int selectedStationIndex;
    /** 本地乐观选中的子武器索引：点击弹种立即高亮，不等服务器回包（否则第一次点击无视觉反馈像"要双击"）。 */
    private int selectedSubIndex = -1;

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
        for (var configEntry : RVP_VehicleExtendedConfigManager.INSTANCE.getModdingOnlyEntries(vehicle)) {
            PartUnit<?> partUnit = vehicle.getPartUnit(configEntry.partId()).orElse(null);
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            if (configEntry.weaponIndex() < 0 || configEntry.weaponIndex() >= weaponUnit.weapons.size()) {
                continue;
            }
            VehicleMultiWeapons multi = RVP_VehicleExtendedConfigManager.INSTANCE
                    .resolveModdingTargetMulti(weaponUnit, configEntry.weaponIndex());
            if (multi == null) {
                continue;
            }
            stations.add(new StationEntry(configEntry.partId(), configEntry.weaponIndex(), weaponUnit, multi));
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
        stationList = document.getElementById("station-list");
        stationEmpty = document.getElementById("station-empty");
        weaponList = document.getElementById("weapon-list");
        weaponEmpty = document.getElementById("weapon-empty");
        closeButton = document.getElementById("close-button");
        if (closeButton != null) {
            closeButton.addEventListener("click", event -> onClose());
        }
        buildStationList();
        refreshWeaponPanel();
    }

    private void buildStationList() {
        if (stationList == null) {
            return;
        }
        stationList.setTextContent("");
        for (int index = 0; index < stations.size(); index++) {
            StationEntry station = stations.get(index);
            Element entry = document.createElement("div");
            entry.setClassName("rvp-entry");
            entry.setAttribute("role", "button");
            entry.setAttribute("tabindex", "0");
            Element name = document.createElement("span");
            name.setClassName("rvp-entry-name");
            name.setTextContent(station.multi.getDisplayName().getString());
            Element count = document.createElement("span");
            count.setClassName("rvp-entry-count");
            int current = station.multi.getSubWeapons().indexOf(station.multi.getSelectedWeapon());
            count.setTextContent((current + 1) + "/" + station.multi.getSubWeapons().size());
            entry.appendChild(name);
            entry.appendChild(count);
            int selectionIndex = index;
            entry.addEventListener("click", event -> selectStation(selectionIndex));
            stationList.appendChild(entry);
        }
        if (stationEmpty != null) {
            stationEmpty.setAttribute("style", stations.isEmpty() ? "" : "display: none;");
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
        if (stationList == null) {
            return;
        }
        List<Element> entries = stationList.querySelectorAll(".rvp-entry");
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setClassName(i == selectedStationIndex ? "rvp-entry active" : "rvp-entry");
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
        List<net.minecraft.world.entity.Entity> noop = new ArrayList<>();
        for (int index = 0; index < station.multi.getSubWeapons().size(); index++) {
            var sub = station.multi.getSubWeapons().get(index);
            Element entry = document.createElement("div");
            entry.setClassName("rvp-entry-sub");
            entry.setAttribute("role", "button");
            entry.setAttribute("tabindex", "0");
            entry.setTextContent(sub.getDisplayName().getString());
            int subIndex = index;
            entry.addEventListener("click", event -> {
                // 本地乐观切换：立即记录选中并刷新高亮，不等服务器回包
                selectedSubIndex = subIndex;
                RVP_Network.CHANNEL.sendToServer(new C2SSelectModdingSubWeapon(
                        vehicle.getId(), station.partId(), station.weaponIndex(), subIndex));
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
        // 高亮选中：优先用本地乐观索引（服务器状态只在未选择时兜底）
        List<Element> subs = weaponList.querySelectorAll(".rvp-entry-sub");
        int current = selectedSubIndex >= 0
                ? selectedSubIndex
                : station.multi.getSubWeapons().indexOf(station.multi.getSelectedWeapon());
        for (int i = 0; i < subs.size(); i++) {
            subs.get(i).setClassName(i == current ? "rvp-entry-sub active" : "rvp-entry-sub");
        }
    }

    @Override
    public void onClose() {
        // 关闭后返回上一屏幕（本体改造工具）
        super.onClose();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}