package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.RVP_WeaponTestMode;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Draws current weapon JSON on screen when {@link RVP_WeaponTestMode} is active (F10).
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WeaponTestOverlay {

    private static final float TEXT_SCALE = 0.55f;
    private static final int LINE_HEIGHT = 7;
    private static final int COLUMN_GAP = 10;
    private static final int MARGIN_X = 6;
    private static final int START_Y = 24;
    private static final int BOTTOM_MARGIN = 8;
    private static final int COLOR_HEADER = 0xFFE8C040;
    private static final int COLOR_LABEL = 0xFF9A9A9A;
    private static final int COLOR_VALUE = 0xFFFFB0B0;
    private static final int COLOR_HINT = 0xFF707070;

    @Nullable
    private static Object cachedDataSource;
    @Nullable
    private static String cachedJsonText;

    private RVP_WeaponTestOverlay() {}

    /**
     * Draw once per HUD frame. Do not hook {@code RenderGuiOverlayEvent} with {@code .type()} equality —
     * it never matched crosshair on Forge 1.20.1, so the overlay was invisible.
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!RVP_WeaponTestMode.isEnabled()) {
            return;
        }
        renderWeaponTest(event.getGuiGraphics());
    }

    private static void renderWeaponTest(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            drawHint(guiGraphics, mc.font, Component.translatable("overlay.ywzj_rvp.weapon_test.no_vehicle"));
            return;
        }

        WeaponUnit weaponUnit = resolveWeaponUnit(player, vehicle);
        if (weaponUnit == null) {
            drawHint(guiGraphics, mc.font, Component.translatable("overlay.ywzj_rvp.weapon_test.no_unit"));
            return;
        }

        Optional<AbstractVehicleWeapon<?>> weaponOpt = weaponUnit.getCurrentWeapon();
        if (weaponOpt.isEmpty()) {
            drawHint(guiGraphics, mc.font, Component.translatable("overlay.ywzj_rvp.weapon_test.no_weapon"));
            return;
        }

        AbstractVehicleWeapon<?> weapon = weaponOpt.get();
        List<Component> lines = buildLines(vehicle, weaponUnit, weapon);
        renderLines(guiGraphics, mc.font, lines);
    }

    @Nullable
    private static WeaponUnit resolveWeaponUnit(LocalPlayer player, AbstractVehicle vehicle) {
        if (LocalVehiclePlayer.instance != null) {
            WeaponUnit unit = LocalVehiclePlayer.instance.getWeaponUnit();
            if (unit != null) {
                return unit;
            }
        }
        if (vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit weaponUnit) {
            return weaponUnit;
        }
        return null;
    }

    private static List<Component> buildLines(AbstractVehicle vehicle, WeaponUnit unit, AbstractVehicleWeapon<?> weapon) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("overlay.ywzj_rvp.weapon_test.header"));
        lines.add(Component.literal(""));

        ResourceLocation weaponId = resolveWeaponId(weapon);
        lines.add(literalPair("weapon_id", weaponId != null ? weaponId.toString() : "?"));
        lines.add(literalPair("unit", unit.getId()));
        lines.add(literalPair("index", String.valueOf(unit.getCurrentWeaponIndex())));
        lines.add(literalPair("class", weapon.getClass().getSimpleName()));

        if (weapon instanceof RVP_WeaponBase rvpWeapon) {
            RVP_WeaponData data = rvpWeapon.getData();
            if (data != null) {
                lines.add(literalPair("rvp_type", data.getWeaponKind() != null ? data.getWeaponKind().name() : "?"));
                lines.add(literalPair("name", data.getName() != null ? data.getName() : ""));
            }
        }

        lines.add(Component.literal(""));
        lines.add(Component.translatable("overlay.ywzj_rvp.weapon_test.json_header"));

        BaseVehicleWeaponData weaponData = weapon.getData();
        String json = formatWeaponData(weaponData);
        for (String line : json.split("\n", -1)) {
            lines.add(Component.literal(line));
        }
        return lines;
    }

    @Nullable
    private static ResourceLocation resolveWeaponId(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof RVP_WeaponBase rvp) {
            RVP_WeaponData data = rvp.getData();
            if (data != null && data.getWeaponId() != null) {
                return data.getWeaponId();
            }
        }
        BaseVehicleWeaponData data = weapon.getData();
        if (data != null && data.getWeaponId() != null) {
            return data.getWeaponId();
        }
        return null;
    }

    /** Reads {@link BaseVehicleWeaponData} fields from the weapon instance on the vehicle (not pack files). */
    private static String formatWeaponData(@Nullable BaseVehicleWeaponData data) {
        if (data != null && data == cachedDataSource && cachedJsonText != null) {
            return cachedJsonText;
        }
        String text = RVP_WeaponDataDumper.toPrettyJson(data);
        cachedDataSource = data;
        cachedJsonText = text;
        return text;
    }

    private static Component literalPair(String key, String value) {
        return Component.literal(key + ": " + value);
    }

    private static void drawHint(GuiGraphics gg, Font font, Component text) {
        renderLines(gg, font, List.of(
                Component.translatable("overlay.ywzj_rvp.weapon_test.header"),
                text,
                Component.translatable("overlay.ywzj_rvp.weapon_test.hint")));
    }

    private static void renderLines(GuiGraphics gg, Font font, List<Component> lines) {
        if (lines.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int scaledScreenW = (int) (mc.getWindow().getGuiScaledWidth() / TEXT_SCALE);
        int scaledScreenH = (int) (mc.getWindow().getGuiScaledHeight() / TEXT_SCALE);
        int linesPerColumn = Math.max(1, (scaledScreenH - START_Y - BOTTOM_MARGIN) / LINE_HEIGHT);

        List<List<Component>> columns = splitIntoColumns(lines, linesPerColumn);
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.scale(TEXT_SCALE, TEXT_SCALE, 1f);

        int rightEdge = scaledScreenW - MARGIN_X;
        for (int col = 0; col < columns.size(); col++) {
            List<Component> column = columns.get(col);
            int columnWidth = measureColumnWidth(font, column);
            int x;
            if (col == 0) {
                x = MARGIN_X;
            } else {
                x = rightEdge - columnWidth;
                rightEdge = x - COLUMN_GAP;
            }
            int y = START_Y;
            for (int row = 0; row < column.size(); row++) {
                Component line = column.get(row);
                int color = colorForLine(line.getString(), row == 0 && col == 0);
                gg.drawString(font, line, x, y, color, true);
                y += line.getString().isEmpty() ? 4 : LINE_HEIGHT;
            }
        }

        pose.popPose();
    }

    private static List<List<Component>> splitIntoColumns(List<Component> lines, int linesPerColumn) {
        List<List<Component>> columns = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += linesPerColumn) {
            int end = Math.min(i + linesPerColumn, lines.size());
            columns.add(new ArrayList<>(lines.subList(i, end)));
        }
        return columns;
    }

    private static int measureColumnWidth(Font font, List<Component> column) {
        int max = 0;
        for (Component line : column) {
            max = Math.max(max, font.width(line.getString()));
        }
        return max;
    }

    private static int colorForLine(String text, boolean headerLine) {
        if (headerLine) {
            return COLOR_HEADER;
        }
        if (text.endsWith(":") || text.contains(": ") && !text.startsWith(" ")) {
            return COLOR_LABEL;
        }
        if (text.startsWith("overlay.")) {
            return COLOR_HINT;
        }
        return COLOR_VALUE;
    }
}
