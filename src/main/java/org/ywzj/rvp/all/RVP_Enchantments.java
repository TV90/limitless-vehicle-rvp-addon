package org.ywzj.rvp.all;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.RVP_MOD;

/**
 * RVP 附魔注册。
 * <p>动机：本体（ywzj_vehicle）的过载黑视机制没有 per-player 耐受钩子——耐受倍率只有全局配置
 * {@code AllConfigs.common.overloadCapacityMultiplier} 一处消费，无法按玩家装备区分。
 * RVP 侧用"抗荷"附魔补位：附在胸甲上即视为穿着抗荷服，客户端每 tick 按附魔等级返还本体体力消耗，
 * 生效逻辑见 {@code RVP_AntiGSuitHandler}（零 Mixin，走公共字段 + 同事件低优先级订阅）。</p>
 */
public class RVP_Enchantments {

    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, RVP_MOD.MOD_ID);

    /**
     * 抗荷（Anti-G）：胸甲附魔，I/II/III 三级。
     * <p>部位语义 = "抗荷服穿在身上"：仅胸甲（{@link EnchantmentCategory#ARMOR_CHEST} + CHEST 槽位），
     * 不可附到头/腿/脚。注册后附魔书自动可用：附魔台给书/胸甲出附魔、铁砧"附魔书+胸甲"合成、
     * {@code /enchant} 命令直测，无需新增任何物品。</p>
     * <p>双端注册：服务端必须知道该附魔，铁砧合成与 /enchant 的服务端校验才能通过。</p>
     */
    public static final RegistryObject<Enchantment> ANTI_G = ENCHANTMENTS.register("anti_g",
            () -> new Enchantment(Enchantment.Rarity.UNCOMMON, EnchantmentCategory.ARMOR_CHEST,
                    new EquipmentSlot[]{EquipmentSlot.CHEST}) {
                @Override
                public int getMaxLevel() {
                    // I/II/III 三级，对应 RVP_AntiGSuitLogic.RATES 的三档返还率
                    return 3;
                }

                @Override
                public int getMinCost(int level) {
                    // 附魔台最低经验门槛：一级 12、二级 24、三级 36（略高于原版护甲附魔节奏，体现专业性）
                    return level * 12;
                }

                @Override
                public int getMaxCost(int level) {
                    // 附魔台最高经验门槛，沿用原版 minCost + 15 的常见节奏
                    return getMinCost(level) + 15;
                }
            });

    public static void register(IEventBus eventBus) {
        ENCHANTMENTS.register(eventBus);
    }
}
