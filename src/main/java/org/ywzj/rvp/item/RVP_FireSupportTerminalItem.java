package org.ywzj.rvp.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportTerminalIdentity;

/** 固定注册的炮火支援终端物品；任务权限与实例身份始终由服务端复核。 */
public final class RVP_FireSupportTerminalItem extends Item {
    public RVP_FireSupportTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            // 调用本项目终端身份服务：首次使用只在服务端为这一个物品实例分配 UUID。
            RVP_FireSupportTerminalIdentity.getOrCreate(serverPlayer, stack);
        } else if (level.isClientSide) {
            // 调用本项目双端安全客户端桥：阶段 D 将在此打开安装了炮火工具的战术地图。
            RVP_ClientActionsAccess.openFireSupportTerminal();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.ywzj_rvp.fire_support_terminal.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.ywzj_rvp.fire_support_terminal.authority").withStyle(ChatFormatting.DARK_GRAY));
    }
}
