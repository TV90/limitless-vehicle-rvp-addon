package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.visual.RVP_UnderwaterExplosionTuning;

/** 水下爆炸水幕的客户端运行时调参命令。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_UnderwaterExplosionDebugCommands {

    private RVP_UnderwaterExplosionDebugCommands() {
    }

    /** 构造挂载到 {@code /rvpdebug} 下的水幕调参命令树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("underwaterExplosion")
                .then(Commands.literal("status").executes(context -> {
                    send(context.getSource(), "[RVP] underwaterExplosion "
                            + RVP_UnderwaterExplosionTuning.describe());
                    return 1;
                }))
                .then(Commands.literal("reset").executes(context -> {
                    RVP_UnderwaterExplosionTuning.reset();
                    send(context.getSource(), "[RVP] underwaterExplosion 已恢复默认参数: "
                            + RVP_UnderwaterExplosionTuning.describe());
                    return 1;
                }))
                .then(Commands.literal("set")
                        .then(Commands.literal("lifetimeScale")
                                .then(Commands.argument("value",
                                                FloatArgumentType.floatArg(
                                                        RVP_UnderwaterExplosionTuning.MIN_LIFETIME_SCALE,
                                                        RVP_UnderwaterExplosionTuning.MAX_LIFETIME_SCALE))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setLifetimeScale(
                                                    FloatArgumentType.getFloat(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion lifetimeScale="
                                                    + RVP_UnderwaterExplosionTuning.getLifetimeScale());
                                            return 1;
                                        })))
                        .then(Commands.literal("returnTail")
                                .then(Commands.argument("ticks",
                                                IntegerArgumentType.integer(0,
                                                        RVP_UnderwaterExplosionTuning.MAX_RETURN_TAIL_TICKS))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setReturnTailTicks(
                                                    IntegerArgumentType.getInteger(context, "ticks"));
                                            send(context.getSource(), "[RVP] underwaterExplosion returnTail="
                                                    + RVP_UnderwaterExplosionTuning.getReturnTailTicks());
                                            return 1;
                                        })))
                        .then(Commands.literal("maxLifetime")
                                .then(Commands.argument("ticks",
                                                IntegerArgumentType.integer(
                                                        RVP_UnderwaterExplosionTuning.MIN_MAX_LIFETIME_TICKS,
                                                        RVP_UnderwaterExplosionTuning.MAX_MAX_LIFETIME_TICKS))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setMaxLifetimeTicks(
                                                    IntegerArgumentType.getInteger(context, "ticks"));
                                            send(context.getSource(), "[RVP] underwaterExplosion maxLifetime="
                                                    + RVP_UnderwaterExplosionTuning.getMaxLifetimeTicks());
                                            return 1;
                                        })))
                        .then(Commands.literal("downwardDamping")
                                .then(Commands.argument("value",
                                                FloatArgumentType.floatArg(
                                                        RVP_UnderwaterExplosionTuning.MIN_DOWNWARD_DAMPING,
                                                        RVP_UnderwaterExplosionTuning.MAX_DOWNWARD_DAMPING))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setDownwardDamping(
                                                    FloatArgumentType.getFloat(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion downwardDamping="
                                                    + RVP_UnderwaterExplosionTuning.getDownwardDamping());
                                            return 1;
                                        })))
                        .then(Commands.literal("outwardSpread")
                                .then(Commands.argument("value",
                                                FloatArgumentType.floatArg(
                                                        RVP_UnderwaterExplosionTuning.MIN_OUTWARD_SPREAD_SCALE,
                                                        RVP_UnderwaterExplosionTuning.MAX_OUTWARD_SPREAD_SCALE))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setOutwardSpreadScale(
                                                    FloatArgumentType.getFloat(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion outwardSpread="
                                                    + RVP_UnderwaterExplosionTuning.getOutwardSpreadScale());
                                            return 1;
                                        })))
                        .then(Commands.literal("sizeMin")
                                .then(Commands.argument("value",
                                                FloatArgumentType.floatArg(
                                                        RVP_UnderwaterExplosionTuning.MIN_SIZE_MULTIPLIER,
                                                        RVP_UnderwaterExplosionTuning.MAX_SIZE_MULTIPLIER))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setSizeMultiplierMin(
                                                    FloatArgumentType.getFloat(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion sizeMin="
                                                    + RVP_UnderwaterExplosionTuning.getSizeMultiplierMin());
                                            return 1;
                                        })))
                        .then(Commands.literal("sizeMax")
                                .then(Commands.argument("value",
                                                FloatArgumentType.floatArg(
                                                        RVP_UnderwaterExplosionTuning.MIN_SIZE_MULTIPLIER,
                                                        RVP_UnderwaterExplosionTuning.MAX_SIZE_MULTIPLIER))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setSizeMultiplierMax(
                                                    FloatArgumentType.getFloat(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion sizeMax="
                                                    + RVP_UnderwaterExplosionTuning.getSizeMultiplierMax());
                                            return 1;
                                        })))
                        .then(Commands.literal("outerSizeDamping")
                                .then(Commands.argument("value",
                                                FloatArgumentType.floatArg(
                                                        RVP_UnderwaterExplosionTuning.MIN_OUTER_SIZE_DAMPING,
                                                        RVP_UnderwaterExplosionTuning.MAX_OUTER_SIZE_DAMPING))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setOuterSizeDamping(
                                                    FloatArgumentType.getFloat(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion outerSizeDamping="
                                                    + RVP_UnderwaterExplosionTuning.getOuterSizeDamping());
                                            return 1;
                                        })))
                        .then(Commands.literal("matureTicks")
                                .then(Commands.argument("ticks",
                                                IntegerArgumentType.integer(
                                                        RVP_UnderwaterExplosionTuning.MIN_MATURE_TICKS,
                                                        RVP_UnderwaterExplosionTuning.MAX_MATURE_TICKS))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setMatureTicks(
                                                    IntegerArgumentType.getInteger(context, "ticks"));
                                            send(context.getSource(), "[RVP] underwaterExplosion matureTicks="
                                                    + RVP_UnderwaterExplosionTuning.getMatureTicks());
                                            return 1;
                                        })))
                        .then(Commands.literal("holdMatureFrame")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setHoldMatureFrame(
                                                    BoolArgumentType.getBool(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion holdMatureFrame="
                                                    + RVP_UnderwaterExplosionTuning.isHoldMatureFrame());
                                            return 1;
                                        })))
                        .then(Commands.literal("upwardSpeed")
                                .then(Commands.argument("value",
                                                FloatArgumentType.floatArg(
                                                        RVP_UnderwaterExplosionTuning.MIN_UPWARD_SPEED_SCALE,
                                                        RVP_UnderwaterExplosionTuning.MAX_UPWARD_SPEED_SCALE))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setUpwardSpeedScale(
                                                    FloatArgumentType.getFloat(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion upwardSpeed="
                                                    + RVP_UnderwaterExplosionTuning.getUpwardSpeedScale());
                                            return 1;
                                        })))
                        .then(Commands.literal("upwardHeight")
                                .then(Commands.argument("value",
                                                FloatArgumentType.floatArg(
                                                        RVP_UnderwaterExplosionTuning.MIN_UPWARD_HEIGHT_SCALE,
                                                        RVP_UnderwaterExplosionTuning.MAX_UPWARD_HEIGHT_SCALE))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setUpwardHeightScale(
                                                    FloatArgumentType.getFloat(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion upwardHeight="
                                                    + RVP_UnderwaterExplosionTuning.getUpwardHeightScale());
                                            return 1;
                                        })))
                        .then(Commands.literal("upwardRandom")
                                .then(Commands.argument("value",
                                                FloatArgumentType.floatArg(
                                                        RVP_UnderwaterExplosionTuning.MIN_UPWARD_RANDOM_SCALE,
                                                        RVP_UnderwaterExplosionTuning.MAX_UPWARD_RANDOM_SCALE))
                                        .executes(context -> {
                                            RVP_UnderwaterExplosionTuning.setUpwardRandomScale(
                                                    FloatArgumentType.getFloat(context, "value"));
                                            send(context.getSource(), "[RVP] underwaterExplosion upwardRandom="
                                                    + RVP_UnderwaterExplosionTuning.getUpwardRandomScale());
                                            return 1;
                                        }))));
    }

    /** 向执行命令的客户端反馈当前调参结果。 */
    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
