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
import org.ywzj.rvp.client.screen.RVP_AuiVariantScreen;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.mixin.accessor.VehicleModdingToolScreenAccessor;
import org.ywzj.vehicle.client.screen.VehicleModdingToolScreen;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 改造工具屏幕扩展：在本体 AUI 改造工具屏幕的「武器切换」按钮左侧动态插入
 * 「更换弹种(RVP)」按钮，点击打开 {@link RVP_AuiVariantScreen}。
 *
 * <p>本体改造工具已适配 ApricityUI（HTML 渲染），原版 Button 注入不可见，
 * 因此通过 AUI Document API 把按钮元素插入本体 HTML 的 {@code profile-actions}
 * 容器内、{@code weapon-button} 之前。</p>
 *
 * <p>换弹限制：仅当载具速度低于 5 kph 且载具上无玩家或 gunner 时允许更换——
 * 点击时复检（不满足给动作栏提示），服务端 {@code C2SSelectModdingSubWeapon} 权威校验兜底。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ModdingToolOverlay {

    private RVP_ModdingToolOverlay() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof VehicleModdingToolScreen screen)) {
            return;
        }
        AbstractVehicle vehicle = ((VehicleModdingToolScreenAccessor) screen).rvp$getVehicle();
        if (vehicle == null
                || RVP_VehicleExtendedConfigManager.INSTANCE.getModdingOnlyEntries(vehicle).isEmpty()) {
            return;
        }
        Document document = screen.getLinkedDocument();
        if (document == null) {
            return;
        }
        Element weaponButton = document.getElementById("weapon-button");
        if (weaponButton == null) {
            return;
        }
        Node parent = weaponButton.getParentNode();
        if (parent == null) {
            return;
        }
        // 若已插入过则不重复插入（init 可能多次触发）
        if (document.getElementById("rvp-variants-button") != null) {
            return;
        }
        Element rvpButton = document.createElement("button");
        rvpButton.setAttribute("id", "rvp-variants-button");
        rvpButton.setClassName("button button-secondary");
        rvpButton.setAttribute("type", "button");
        rvpButton.setTextContent("更换弹种(RVP)");
        rvpButton.addEventListener("click", ev -> {
            // 点击时复检改装条件（速度 < 5 kph 且无玩家/gunner 乘员），不满足则提示并阻止打开
            if (!RVP_VehicleExtendedConfigManager.INSTANCE.canModVehicle(vehicle)) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.translatable("message.ywzj_rvp.modding_blocked"), true);
                }
                return;
            }
            Minecraft.getInstance().setScreen(new RVP_AuiVariantScreen(vehicle, screen));
        });
        // 插到「武器切换」按钮左侧（weapon-button 之前的兄弟位置，
        // 不插其后——下方与特技烟雾区布局冲突）
        parent.insertBefore(rvpButton, weaponButton);
    }
}