package org.ywzj.rvp.client.handler;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.init.Node;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.screen.RVP_AuiAuxiliaryVehicleScreen;
import org.ywzj.rvp.client.screen.RVP_AuiVariantScreen;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.mixin.accessor.VehicleModdingToolScreenAccessor;
import org.ywzj.vehicle.client.screen.VehicleModdingToolScreen;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 通过 AUI Document API 扩展本体改装屏，不修改本体 HTML。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ModdingToolOverlay {
    private RVP_ModdingToolOverlay() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof VehicleModdingToolScreen screen)) return;
        AbstractVehicle vehicle = ((VehicleModdingToolScreenAccessor) screen).rvp$getVehicle();
        if (vehicle == null) return;
        Document document = screen.getLinkedDocument();
        if (document == null) return;

        // 辅助载具入口独立于 RVP 武器变体逻辑，母车配置存在时始终可浏览。
        insertAuxiliaryVehicleEntry(document, vehicle, screen);

        boolean hasRvpMulti = !RVP_VehicleExtendedConfigManager.INSTANCE.getModdingOnlyEntries(vehicle).isEmpty();
        boolean hasBaseCustom = false;
        for (var part : vehicle.getPartUnits()) {
            if (part instanceof org.ywzj.vehicle.vehicle.part.WeaponUnit weaponUnit
                    && weaponUnit.isInteractive() && weaponUnit.weapons.size() > 1) {
                hasBaseCustom = true;
                break;
            }
        }
        Element weaponButton = document.getElementById("weapon-button");
        if (weaponButton == null) return;
        Node parent = weaponButton.getParentNode();
        if (parent == null || document.getElementById("rvp-variants-button") != null) return;

        if (hasRvpMulti) {
            // 有 RVP 自定义弹种时沿用原行为：统一入口接管武器切换，本体烟雾控制不受影响。
            weaponButton.setAttribute("style", "display: none;");
            Element rvpButton = document.createElement("button");
            rvpButton.setAttribute("id", "rvp-variants-button");
            rvpButton.setClassName("button button-secondary");
            rvpButton.setAttribute("type", "button");
            rvpButton.setTextContent(net.minecraft.client.resources.language.I18n.get("gui.ywzj_rvp.modding.unified_button"));
            rvpButton.addEventListener("click", ev -> {
                if (!RVP_VehicleExtendedConfigManager.INSTANCE.canModVehicle(vehicle)) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) mc.player.displayClientMessage(
                            Component.translatable("message.ywzj_rvp.modding_blocked"), true);
                    return;
                }
                Minecraft.getInstance().setScreen(new RVP_AuiVariantScreen(vehicle, screen));
            });
            parent.insertBefore(rvpButton, weaponButton);
        } else if (hasBaseCustom) {
            // 只有本体改装时继续使用本体原生入口。
        }
    }

    private static void insertAuxiliaryVehicleEntry(Document document, AbstractVehicle vehicle,
                                                     VehicleModdingToolScreen screen) {
        if (document.getElementById("rvp-auxiliary-entry-region") != null) return;
        var options = RVP_DeployableUavConfigCache.getOptions(vehicle.getVehicleId());
        if (options == null || options.isEmpty()) return;
        Element profileCopy = document.querySelector(".profile-copy");
        Element profileActions = document.querySelector(".profile-actions");
        if (profileCopy == null || profileActions == null) return;
        Node profileBody = profileActions.getParentNode();
        if (profileBody == null || profileCopy.getParentNode() != profileBody) return;

        Element region = document.createElement("div");
        region.setAttribute("id", "rvp-auxiliary-entry-region");
        region.setAttribute("style", "width: 28%; min-width: 76px; flex: 0 1 28%; display: flex; align-self: flex-start; align-items: flex-start; justify-content: center;");
        Element button = document.createElement("button");
        button.setAttribute("id", "rvp-auxiliary-entry");
        button.setAttribute("type", "button");
        button.setClassName("button button-secondary");
        button.setAttribute("style", "width: 100%; min-height: 34px; white-space: normal;");
        button.setTextContent(net.minecraft.client.resources.language.I18n.get("gui.ywzj_rvp.auxiliary.entry"));
        button.addEventListener("click", ev -> Minecraft.getInstance().setScreen(
                new RVP_AuiAuxiliaryVehicleScreen(vehicle, screen)));
        region.appendChild(button);
        // profile-body 是横向 flex：新入口成为 profile-copy 与 actions 之间的同排第三列。
        profileBody.insertBefore(region, profileActions);
    }
}
