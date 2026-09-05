package org.ywzj.rvp.firesupport.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RVP_Items;
import org.ywzj.rvp.firesupport.RVP_FireSupportProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 服务端权威的终端实例 UUID 分配、重复检查和生命周期匹配。 */
public final class RVP_FireSupportTerminalIdentity {
    /** 终端专用 NBT 根，避免与其他物品扩展字段冲突。 */ private static final String ROOT_TAG = "rvp_fire_support";
    /** 根标签内保存实例 UUID 的键。 */ private static final String INSTANCE_TAG = "terminal_instance";

    private RVP_FireSupportTerminalIdentity() {}

    /** 为实际终端物品读取或创建权威实例 UUID；非终端返回 null。 */
    @Nullable
    public static UUID getOrCreate(ServerPlayer player, ItemStack stack) {
        if (player == null || !isTerminal(stack)) return null;
        CompoundTag root = stack.getOrCreateTagElement(ROOT_TAG);
        if (!root.hasUUID(INSTANCE_TAG)) root.putUUID(INSTANCE_TAG, UUID.randomUUID());
        return root.getUUID(INSTANCE_TAG);
    }

    /** 只读解析实例 UUID；缺失、损坏或非终端物品均返回 null。 */
    @Nullable
    public static UUID read(ItemStack stack) {
        if (!isTerminal(stack) || !stack.hasTag()) return null;
        CompoundTag root = stack.getTagElement(ROOT_TAG);
        return root != null && root.hasUUID(INSTANCE_TAG) ? root.getUUID(INSTANCE_TAG) : null;
    }

    /** 检查指定手是否满足 profile 的物品与 allowed_hands 规则。 */
    public static boolean isAllowedHand(ServerPlayer player, InteractionHand hand,
                                        RVP_FireSupportProfile.HolderPolicy policy) {
        if (player == null || hand == null || policy == null) return false;
        String handId = hand == InteractionHand.MAIN_HAND ? "main" : "off";
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(player.getItemInHand(hand).getItem());
        return policy.allowedHands().contains(handId) && policy.requiredItem().equals(itemId);
    }

    /** 呼叫阶段扫描玩家完整物品栏；必须恰好存在一个匹配实例，复制 UUID 会被拒绝。 */
    public static boolean ownsUniqueTerminal(ServerPlayer player, UUID instanceId) {
        if (player == null || instanceId == null) return false;
        int matches = 0;
        for (ItemStack stack : inventoryStacks(player)) {
            if (instanceId.equals(read(stack)) && ++matches > 1) return false;
        }
        return matches == 1;
    }

    /** 停火时只扫描 profile 允许的主手/副手，并精确匹配任务绑定实例 UUID。 */
    public static boolean matchesBoundTerminalInAllowedHand(ServerPlayer player, UUID instanceId,
                                                             RVP_FireSupportProfile.HolderPolicy policy) {
        if (player == null || instanceId == null || policy == null) return false;
        for (InteractionHand hand : InteractionHand.values()) {
            if (isAllowedHand(player, hand, policy) && instanceId.equals(read(player.getItemInHand(hand)))) return true;
        }
        return false;
    }

    /** @return 当前静态终端注册项是否与物品堆匹配。 */
    public static boolean isTerminal(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(RVP_Items.FIRE_SUPPORT_TERMINAL.get());
    }

    private static List<ItemStack> inventoryStacks(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>(player.getInventory().items);
        stacks.addAll(player.getInventory().offhand);
        return stacks;
    }
}
