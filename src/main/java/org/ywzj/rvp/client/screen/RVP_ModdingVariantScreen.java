package org.ywzj.rvp.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.network.C2SSelectModdingSubWeapon;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.List;

public class RVP_ModdingVariantScreen extends Screen {

    private static final int ITEM_HEIGHT = 20;
    private static final int LEFT_WIDTH = 220;
    private static final int RIGHT_WIDTH = 220;
    private static final int PADDING = 18;
    private static final int TOP_PADDING = 42;
    private static final int BOTTOM_PADDING = 28;

    private final AbstractVehicle vehicle;
    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();
    private int selectedEntryIndex = 0;
    private int leftScroll;
    private int rightScroll;

    public RVP_ModdingVariantScreen(AbstractVehicle vehicle, Screen parent) {
        super(Component.literal("RVP Weapon Variants"));
        this.vehicle = vehicle;
        this.parent = parent;
        rebuildEntries();
    }

    private void rebuildEntries() {
        entries.clear();
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
            entries.add(new Entry(configEntry.partId(), configEntry.weaponIndex(), weaponUnit, multi));
        }
        if (selectedEntryIndex >= entries.size()) {
            selectedEntryIndex = Math.max(0, entries.size() - 1);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        guiGraphics.drawCenteredString(font, Component.literal("拿着改装工具选择单主炮栏内部变体"), width / 2, 24, 0xA0A0A0);

        int leftX = PADDING;
        int topY = TOP_PADDING;
        int bottomY = height - BOTTOM_PADDING;
        int rightX = width - PADDING - RIGHT_WIDTH;

        guiGraphics.fill(leftX, topY, leftX + LEFT_WIDTH, bottomY, 0x66000000);
        guiGraphics.fill(rightX, topY, rightX + RIGHT_WIDTH, bottomY, 0x66000000);

        guiGraphics.drawString(font, Component.literal("改装槽位"), leftX + 6, topY - 12, 0xC0C0C0, false);
        guiGraphics.drawString(font, Component.literal("可选变体"), rightX + 6, topY - 12, 0xC0C0C0, false);

        drawEntryList(guiGraphics, mouseX, mouseY, leftX, topY, bottomY);
        drawVariantList(guiGraphics, mouseX, mouseY, rightX, topY, bottomY);
    }

    private void drawEntryList(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int topY, int bottomY) {
        int visible = visibleItems(topY, bottomY);
        int end = Math.min(entries.size(), leftScroll + visible);
        for (int i = leftScroll; i < end; i++) {
            int y = topY + (i - leftScroll) * ITEM_HEIGHT;
            boolean selected = i == selectedEntryIndex;
            boolean hovered = mouseX >= x + 2 && mouseX <= x + LEFT_WIDTH - 2 && mouseY >= y && mouseY <= y + ITEM_HEIGHT - 1;
            int bg = selected ? 0xAA2E5A88 : (hovered ? 0x66444444 : 0x44222222);
            guiGraphics.fill(x + 2, y, x + LEFT_WIDTH - 2, y + ITEM_HEIGHT - 1, bg);
            Entry entry = entries.get(i);
            String label = entry.weaponUnit.getName().getString() + " / " + variantLabel(entry);
            guiGraphics.drawString(font, trim(label, LEFT_WIDTH - 14), x + 6, y + 6, 0xFFFFFF, false);
        }
    }

    private void drawVariantList(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int topY, int bottomY) {
        Entry entry = selectedEntry();
        if (entry == null) {
            guiGraphics.drawString(font, Component.literal("无可用项"), x + 6, topY + 6, 0x909090, false);
            return;
        }
        List<AbstractVehicleWeapon<?>> variants = entry.multi.getSubWeapons();
        int visible = visibleItems(topY, bottomY);
        int end = Math.min(variants.size(), rightScroll + visible);
        for (int i = rightScroll; i < end; i++) {
            int y = topY + (i - rightScroll) * ITEM_HEIGHT;
            boolean selected = i == entry.multi.getSelectedIndex();
            boolean hovered = mouseX >= x + 2 && mouseX <= x + RIGHT_WIDTH - 2 && mouseY >= y && mouseY <= y + ITEM_HEIGHT - 1;
            int bg = selected ? 0xAA3A7A3A : (hovered ? 0x66444444 : 0x44222222);
            guiGraphics.fill(x + 2, y, x + RIGHT_WIDTH - 2, y + ITEM_HEIGHT - 1, bg);
            String name = variants.get(i).getDisplayName().getString();
            guiGraphics.drawString(font, trim(name, RIGHT_WIDTH - 14), x + 6, y + 6, 0xFFFFFF, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int leftX = PADDING;
        int topY = TOP_PADDING;
        int bottomY = height - BOTTOM_PADDING;
        int rightX = width - PADDING - RIGHT_WIDTH;
        if (mouseY >= topY && mouseY <= bottomY) {
            if (mouseX >= leftX && mouseX <= leftX + LEFT_WIDTH) {
                int index = leftScroll + (int) ((mouseY - topY) / ITEM_HEIGHT);
                if (index >= 0 && index < entries.size()) {
                    selectedEntryIndex = index;
                    rightScroll = 0;
                    click();
                    return true;
                }
            } else if (mouseX >= rightX && mouseX <= rightX + RIGHT_WIDTH) {
                Entry entry = selectedEntry();
                if (entry == null) {
                    return true;
                }
                int index = rightScroll + (int) ((mouseY - topY) / ITEM_HEIGHT);
                if (index >= 0 && index < entry.multi.getSubWeapons().size()) {
                    RVP_Network.CHANNEL.sendToServer(new C2SSelectModdingSubWeapon(
                            vehicle.getId(),
                            entry.partId,
                            entry.weaponIndex,
                            index
                    ));
                    click();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int topY = TOP_PADDING;
        int bottomY = height - BOTTOM_PADDING;
        if (mouseY < topY || mouseY > bottomY) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        int direction = (int) -Math.signum(delta);
        int visible = visibleItems(topY, bottomY);
        if (mouseX >= PADDING && mouseX <= PADDING + LEFT_WIDTH) {
            leftScroll = Mth.clamp(leftScroll + direction, 0, Math.max(0, entries.size() - visible));
            return true;
        }
        int rightX = width - PADDING - RIGHT_WIDTH;
        Entry entry = selectedEntry();
        if (entry != null && mouseX >= rightX && mouseX <= rightX + RIGHT_WIDTH) {
            rightScroll = Mth.clamp(rightScroll + direction, 0, Math.max(0, entry.multi.getSubWeapons().size() - visible));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Entry selectedEntry() {
        if (selectedEntryIndex < 0 || selectedEntryIndex >= entries.size()) {
            return null;
        }
        return entries.get(selectedEntryIndex);
    }

    private int visibleItems(int topY, int bottomY) {
        return Math.max(1, (bottomY - topY) / ITEM_HEIGHT);
    }

    private String trim(String text, int maxWidth) {
        return font.plainSubstrByWidth(text, Math.max(12, maxWidth));
    }

    private String variantLabel(Entry entry) {
        int selected = entry.multi.getSelectedIndex();
        if (selected >= 0 && selected < entry.multi.getSubWeapons().size()) {
            return entry.multi.getSubWeapons().get(selected).getDisplayName().getString();
        }
        return "Variant";
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private record Entry(String partId, int weaponIndex, WeaponUnit weaponUnit, VehicleMultiWeapons multi) {}
}
