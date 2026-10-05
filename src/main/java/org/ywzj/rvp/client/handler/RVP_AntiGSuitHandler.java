package org.ywzj.rvp.client.handler;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Enchantments;
import org.ywzj.vehicle.all.AllConfigs;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.List;
import java.util.Map;

/**
 * 抗荷附魔生效器（客户端）：胸甲上的"抗荷"附魔降低本体过载黑视机制的体力消耗。
 * <p><b>时序依据</b>：本体 {@code LocalVehiclePlayerEvent.onPlayerTick} 订阅
 * {@code PlayerTickEvent}（客户端 Phase.END，默认 NORMAL 优先级，receiveCanceled=true）驱动
 * {@code LocalVehiclePlayer.tick()} → {@code tickOverload()}（本 tick 的体力扣减与黑视判定）。
 * 本 handler 订阅<b>同一事件同一阶段</b>但优先级 <b>LOWEST</b>（事件总线保证 NORMAL 之后才派发），
 * 因此每 tick 必然在本体扣完之后执行，把抗荷服承担的部分消耗返还回去——等效降低有效过载，
 * 不改写本体任何逻辑字段语义。零 Mixin：stamina/currentG/lostControl 均为本体 public 字段
 * （RVP 已有直接读写先例：RVP_ClientHitlState 写 instance.viewType）。</p>
 * <p><b>边界</b>：纯客户端模拟（本体 G 系统本就只在本地玩家客户端运行），无服务端同步需求；
 * 黑视 3 秒失控与恢复节奏完全由本体控制，抗荷服只延后触底；HUD 的 G 读数与过载预警
 * 读的是载具真实 G，不受补偿影响。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_AntiGSuitHandler {

    /**
     * 客户端玩家 tick 末尾执行抗荷补偿。
     * <p>priority = LOWEST：排在本体 LocalVehiclePlayerEvent（NORMAL）之后，见类注释的时序依据；
     * receiveCanceled = true：与本体 handler 同语义，即使事件被其它 mod 取消也照常补偿，
     * 避免本体照常扣体力而补偿缺失造成的"隐式变脆"。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onPlayerTickEnd(TickEvent.PlayerTickEvent event) {
        if (event.side != LogicalSide.CLIENT || event.phase != TickEvent.Phase.END) {
            return;
        }
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        // 只补偿本体过载机制跟踪的那名玩家（与本体 handler 的判定完全一致）
        Player player = event.player;
        if (player == null || player != lvp.getPlayer()) {
            return;
        }
        // 本体过载机制总开关关闭时，本体每 tick 把 stamina 复位为 100——此时必须跳过补偿，
        // 否则返还量会单调推高 stamina（着色器直接读 stamina，会出现"没开过载却渐渐红视"的视觉 bug）
        if (!AllConfigs.common.overload.get()) {
            return;
        }
        // 已黑视：本体 3 秒昏迷期间不补偿，保持本体恢复节奏不变
        if (lvp.lostControl) {
            return;
        }
        // 只认胸甲槽位上的抗荷等级——生存模式铁砧本就拒绝不兼容的"书+物品"组合（原版 canEnchant 检查），
        // 但创造模式铁砧会绕过该检查把附魔书安到任意物品上；效果端只读 CHEST 槽位即可保证
        // "仅胸甲可用"的承诺与提示文本（RVP_AntiGSuitHandler.onItemTooltip）口径一致
        int level = EnchantmentHelper.getItemEnchantmentLevel(RVP_Enchantments.ANTI_G.get(),
                player.getItemBySlot(EquipmentSlot.CHEST));
        if (level <= 0) {
            return;
        }
        // 耐受倍率现场读本体全局配置（与本体 clamp 边界同源，不硬编码；默认 1.0）
        float capacityMultiplier = AllConfigs.common.overloadCapacityMultiplier.get().floatValue();
        lvp.stamina = RVP_AntiGSuitLogic.compensate(lvp.stamina, lvp.currentG, level, capacityMultiplier);
    }

    /**
     * 悬停提示：携带"抗荷"附魔、但当前物品不是合法胸甲目标时，追加灰色"仅胸甲可用"行。
     * <p>覆盖两处场景：①抗荷附魔书（铁砧/附魔台的施加来源，最需要提示的入口）；
     * ②被创造模式铁砧误附到其它装备上的物品（生存模式原版 canEnchant 检查会拒绝，正常到不了这里）。
     * 已附在合法胸甲上时不提示（"仅胸甲可用"挂在胸甲上是废话）。
     * {@code EnchantmentHelper.getEnchantments} 对附魔书读 StoredEnchantments、对装备读 Enchantments，
     * 两种来源全覆盖。</p>
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        List<Component> tooltip = event.getToolTip();
        if (tooltip == null) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
        if (!enchantments.containsKey(RVP_Enchantments.ANTI_G.get())) {
            return;
        }
        if (EnchantmentCategory.ARMOR_CHEST.canEnchant(stack.getItem())) {
            return;
        }
        tooltip.add(Component.translatable("tips.ywzj_rvp.anti_g.chest_only").withStyle(ChatFormatting.GRAY));
    }
}
