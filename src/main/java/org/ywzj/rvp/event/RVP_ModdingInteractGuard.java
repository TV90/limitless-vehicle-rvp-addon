package org.ywzj.rvp.event;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * [RVP] 有人载具改装守卫：屏蔽"手持改装工具 + 潜行 + 主手右键子武器站"的本体改装行为。
 *
 * <p>背景：本体 {@code AllEvents.onEntityInteract} 在命中武器站且玩家潜行、手持改装工具时，
 * 服务端直接 {@code WeaponUnit.onInteract → switchWeapon} 循环换弹，**不校验载具是否有乘员**——
 * 对已被玩家 / gunner 驾驶的载具依然可从车外改装换弹。按用户要求在 RVP 侧屏蔽该场景。</p>
 *
 * <p>实现方式（零 Mixin，Forge 事件总线）：本监听以 {@link EventPriority#HIGHEST} 先于本体
 * {@code AllEvents}（默认 NORMAL、不接收已取消事件）执行，命中拦截条件即
 * {@code setCanceled(true)}——本体 handler 被跳过，{@code onInteract} 不执行；
 * 客户端取消还会阻止交互包上行，服务端监听对改包客户端兜底。回退方式：删除本监听即恢复本体原行为。</p>
 *
 * <p>判定与本体同源：部件命中复用本体公共 {@link VectorUtil#hitPartUnit}（同样的 4 格视线射线），
 * 只拦截"对准可改装子武器站"的场景，潜行右键门 / 装饰等非武器部件的原有交互不受影响；
 * "载具有人"复用 {@link RVP_VehicleExtendedConfigManager#hasPilotOrGunnerPassenger}，
 * 与改装界面 {@code canModVehicle} 的乘员判定保持同一语义。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ModdingInteractGuard {

    private RVP_ModdingInteractGuard() {}

    /**
     * 目的：有人载具的潜行右键改装在进入本体 {@code onInteract} 之前取消。
     * 双端各自执行：客户端取消阻止发包 + 本地提示，服务端取消阻止实际换弹（互斥不重复提示）。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        // 本体 onInteract 仅在潜行 + 主手时改装，其余组合（未潜行开屏/开门、副手）不拦，保持原行为
        if (!player.isShiftKeyDown() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!mainHand.is(AllItems.MODDING_TOOL.get())) {
            return;
        }
        if (!(event.getTarget() instanceof AbstractVehicle vehicle)) {
            return;
        }
        // 目的：仅屏蔽"已有玩家或 gunner 乘员"的载具，停放无人载具的潜行改装照常
        if (!RVP_VehicleExtendedConfigManager.INSTANCE.hasPilotOrGunnerPassenger(vehicle)) {
            return;
        }
        // 目的：与本体 AllEvents 同源同参数的部件射线（4 格视线），确认对准的是可改装子武器站；
        // 潜行右键门 / 装饰等其他部件时不拦，避免误伤原有交互
        Vec3 eyePosition = player.getEyePosition();
        PartUnit<?> partUnit = VectorUtil.hitPartUnit(vehicle, eyePosition,
                eyePosition.add(player.getLookAngle().scale(4)));
        if (!(partUnit instanceof WeaponUnit weaponUnit) || !weaponUnit.isInteractive()) {
            return;
        }
        event.setCanceled(true);
        // 目的：动作栏提示被拦截原因；单人游戏服务端事件因客户端已取消不会触发，不会重复显示
        player.displayClientMessage(Component.translatable("rvp.modding.vehicle_occupied"), true);
    }
}
