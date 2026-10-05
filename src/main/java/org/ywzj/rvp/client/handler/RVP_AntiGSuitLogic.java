package org.ywzj.rvp.client.handler;

/**
 * 抗荷附魔的补偿数学（纯函数，无任何 Minecraft 类依赖，便于单元测试）。
 * <p>本体黑视机制的体力模型（ywzj_vehicle {@code LocalVehiclePlayer.tickOverload}）：
 * 每 tick {@code stamina -= (currentG - 1) * 0.8}，再钳制到 {@code [100-100M, 100+60M]}
 * （M = 本体全局过载耐受倍率），触到任一端即黑视 3 秒。
 * 本类按胸甲上的抗荷附魔等级把部分消耗"返还"回去——净消耗变为
 * {@code (G-1) * 0.8 * (1 - 返还率)}，等效于抗荷服替飞行员扛掉一部分过载（正负 G 对称生效）。</p>
 */
public final class RVP_AntiGSuitLogic {

    /** 本体 tickOverload 的每 G 消耗系数（LocalVehiclePlayer.java: {@code endureG * 0.8f}），必须与本体保持一致。 */
    public static final float DRAIN_PER_G = 0.8f;

    /**
     * 各等级返还率（下标 = 附魔等级 - 1）：I 级 20%、II 级 35%、III 级 50%。
     * <p>等效稳态不黑视上限（净消耗 = 每 tick 恢复 1 的平衡点 {@code G = 1 + 1/(0.8*(1-率))}）：
     * 裸装 2.25G → I 级 2.56G → II 级 2.92G → III 级 3.5G。</p>
     */
    private static final float[] RATES = {0.20F, 0.35F, 0.50F};

    private RVP_AntiGSuitLogic() {
    }

    /**
     * 按附魔等级取返还率，超界等级取最高档。
     *
     * @param level 胸甲上的抗荷附魔等级（0 = 未附魔）
     * @return 返还率（0~1），未附魔返回 0
     */
    public static float rateForLevel(int level) {
        if (level <= 0) {
            return 0F;
        }
        return RATES[Math.min(level, RATES.length) - 1];
    }

    /**
     * 计算补偿后的体力值。
     *
     * @param stamina             本体本 tick 扣减并钳制后的体力值
     * @param currentG            本体本 tick 的过载 G 值（LocalVehiclePlayer.currentG，平飞 ≈ 1）
     * @param level               胸甲上的抗荷附魔等级
     * @param capacityMultiplier  本体全局过载耐受倍率（AllConfigs.common.overloadCapacityMultiplier，
     *                            默认 1.0；只用于复刻本体钳制边界，不硬编码）
     * @return 返还部分消耗并按本体同款边界钳制后的体力值
     */
    public static float compensate(float stamina, float currentG, int level, float capacityMultiplier) {
        float rate = rateForLevel(level);
        if (rate <= 0F) {
            return stamina;
        }
        // 返还量 = 本 tick 本体消耗量 × 返还率；currentG < 1 时消耗为负（体力上飘），返还同样为负 → 负 G 红视对称减缓
        float refund = (currentG - 1F) * DRAIN_PER_G * rate;
        // 复刻本体的体力边界（LocalVehiclePlayer.tickOverload 的 clamp 上下限），保证补偿不把 stamina 推出合法区间
        float positiveLimit = 100F - 100F * capacityMultiplier;
        float negativeLimit = 100F + 60F * capacityMultiplier;
        return Math.max(positiveLimit, Math.min(negativeLimit, stamina + refund));
    }
}
