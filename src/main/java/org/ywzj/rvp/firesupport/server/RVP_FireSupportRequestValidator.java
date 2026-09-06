package org.ywzj.rvp.firesupport.server;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.ywzj.rvp.firesupport.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.RVP_FireSupportDeliveryFactory;
import org.ywzj.rvp.firesupport.RVP_FireSupportDeliveryTypes;
import org.ywzj.rvp.firesupport.RVP_FireSupportParameterValidator;
import org.ywzj.rvp.firesupport.RVP_FireSupportProfile;
import org.ywzj.rvp.firesupport.RVP_FireSupportRequest;
import org.ywzj.rvp.firesupport.RVP_FireSupportSchedulePlanner;
import org.ywzj.rvp.firesupport.RVP_FireSupportSnapshot;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;

import java.util.Map;
import java.util.UUID;

/** 把不可信客户端选择转换为完整、冻结且可调度的任务草案。 */
public final class RVP_FireSupportRequestValidator {
    /** 网络之外再次约束选择 ID 的最大 UTF-16 长度。 */ private static final int MAX_SELECTION_LENGTH = 64;

    private RVP_FireSupportRequestValidator() {}

    /** 按设计顺序执行所有权威校验；任何异常都转换为稳定拒绝原因。 */
    public static ValidationResult validate(ServerPlayer player, RVP_FireSupportRequest request,
                                            RVP_FireSupportSnapshot snapshot, int activeForPlayer,
                                            int activeGlobal, long cooldownUntilTick) {
        if (!basicRequestValid(player, request)) return ValidationResult.reject(RVP_FireSupportEndReason.INVALID_PACKET);
        if (request.revision() != snapshot.revision()) return ValidationResult.reject(RVP_FireSupportEndReason.STALE_REVISION);

        ItemStack held = player.getItemInHand(request.hand());
        ResourceLocation heldItemId = ForgeRegistries.ITEMS.getKey(held.getItem());
        ResourceLocation profileId = request.profileId();
        RVP_FireSupportProfile profile = snapshot.profiles().get(profileId);
        if (profile == null) return ValidationResult.reject(RVP_FireSupportEndReason.PROFILE_NOT_FOUND);
        if (!profile.holderPolicy().requiredItem().equals(heldItemId)
                || !RVP_FireSupportTerminalIdentity.isAllowedHand(player, request.hand(), profile.holderPolicy())) {
            return ValidationResult.reject(RVP_FireSupportEndReason.TERMINAL_NOT_HELD);
        }

        UUID terminalId = RVP_FireSupportTerminalIdentity.getOrCreate(player, held);
        if (terminalId == null) return ValidationResult.reject(RVP_FireSupportEndReason.TERMINAL_NOT_HELD);
        if (!RVP_FireSupportTerminalIdentity.ownsUniqueTerminal(player, terminalId)) {
            return ValidationResult.reject(RVP_FireSupportEndReason.TERMINAL_DUPLICATED);
        }

        RVP_FireSupportProfile.Munition munition = profile.munitions().get(request.munitionId());
        RVP_FireSupportProfile.FireMode mode = profile.fireModes().get(request.fireModeId());
        RVP_FireSupportProfile.PatternPreset pattern = profile.patterns().get(request.patternId());
        if (munition == null || mode == null || pattern == null) {
            return ValidationResult.reject(RVP_FireSupportEndReason.SELECTION_NOT_FOUND);
        }

        if (!targetValid(player, request, profile.limits())) {
            double distanceSquared = horizontalDistanceSquared(player, request.targetX(), request.targetZ());
            double minSquared = profile.limits().minTargetDistanceMeters() * profile.limits().minTargetDistanceMeters();
            double maxSquared = profile.limits().maxTargetDistanceMeters() * profile.limits().maxTargetDistanceMeters();
            return ValidationResult.reject(distanceSquared < minSquared || distanceSquared > maxSquared
                    ? RVP_FireSupportEndReason.TARGET_OUT_OF_RANGE : RVP_FireSupportEndReason.INVALID_TARGET);
        }

        Map<String, Double> parameters;
        RVP_FireSupportSchedulePlanner.Plan plan;
        try {
            // 调用阶段 A 参数校验器：键集合、有限值、范围与步长必须和权威预设完全一致。
            parameters = RVP_FireSupportParameterValidator.validate(
                    pattern, request.parameters(), profile.limits().maxParameterCount());
        } catch (RuntimeException exception) {
            return ValidationResult.reject(RVP_FireSupportEndReason.INVALID_PARAMETERS);
        }

        long seed = player.getRandom().nextLong();
        try {
            // 调用阶段 A 计划器：重新计算弹数、呼叫时长和每发 Tick，不采信客户端派生值。
            plan = RVP_FireSupportSchedulePlanner.plan(profile.callStage().baseDurationTicks(),
                    munition.roundsPerUnit(), munition.registrationPhaseEnabled(), mode, seed, profile.limits());
        } catch (RuntimeException exception) {
            return ValidationResult.reject(RVP_FireSupportEndReason.INVALID_SCHEDULE);
        }
        if (activeForPlayer >= profile.limits().maxActiveMissionsPerPlayer()) {
            return ValidationResult.reject(RVP_FireSupportEndReason.PLAYER_MISSION_LIMIT);
        }
        if (activeGlobal >= profile.limits().maxActiveMissionsGlobal()) {
            return ValidationResult.reject(RVP_FireSupportEndReason.GLOBAL_MISSION_LIMIT);
        }
        if (player.serverLevel().getGameTime() < cooldownUntilTick) {
            return ValidationResult.reject(RVP_FireSupportEndReason.REQUEST_COOLDOWN);
        }

        RVP_WeaponData weapon = CommonAssetsManager.vehicleWeaponManager().getIndex(munition.weaponId())
                .filter(index -> index.data() instanceof RVP_WeaponData)
                .map(index -> (RVP_WeaponData) index.data()).orElse(null);
        RVP_FireSupportDeliveryFactory factory = RVP_FireSupportDeliveryTypes.get(munition.deliveryType());
        if (weapon == null || factory == null) return ValidationResult.reject(RVP_FireSupportEndReason.WEAPON_UNAVAILABLE);
        RVP_FireSupportDelivery delivery;
        try {
            // 调用阶段 B 投送工厂：把 profile 冻结的类型化数据创建为运行时投送器。
            delivery = factory.create(munition.deliveryData());
        } catch (RuntimeException exception) {
            return ValidationResult.reject(RVP_FireSupportEndReason.WEAPON_UNAVAILABLE);
        }
        return ValidationResult.accept(new Accepted(profileId, profile, munition, mode, pattern, parameters,
                plan, delivery, weapon, terminalId, seed, Mth.wrapDegrees(request.headingDegrees())));
    }

    private static boolean basicRequestValid(ServerPlayer player, RVP_FireSupportRequest request) {
        return player != null && request != null && request.hand() != null && request.profileId() != null
                && request.nonce() != null
                && validSelection(request.munitionId()) && validSelection(request.fireModeId())
                && validSelection(request.patternId()) && request.parameters() != null
                && request.parameters().size() <= 32 && Double.isFinite(request.targetX())
                && Double.isFinite(request.targetZ()) && Double.isFinite(request.headingDegrees());
    }

    private static boolean validSelection(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_SELECTION_LENGTH
                && value.matches("[a-z0-9_.-]+");
    }

    private static boolean targetValid(ServerPlayer player, RVP_FireSupportRequest request,
                                       RVP_FireSupportProfile.Limits limits) {
        if (Math.abs(request.targetX()) > 30_000_000 || Math.abs(request.targetZ()) > 30_000_000) return false;
        BlockPos probe = new BlockPos(Mth.floor(request.targetX()), player.serverLevel().getMinBuildHeight(),
                Mth.floor(request.targetZ()));
        if (!player.serverLevel().getWorldBorder().isWithinBounds(probe)) return false;
        double distanceSquared = horizontalDistanceSquared(player, request.targetX(), request.targetZ());
        double minSquared = limits.minTargetDistanceMeters() * limits.minTargetDistanceMeters();
        double maxSquared = limits.maxTargetDistanceMeters() * limits.maxTargetDistanceMeters();
        return distanceSquared >= minSquared && distanceSquared <= maxSquared;
    }

    private static double horizontalDistanceSquared(ServerPlayer player, double x, double z) {
        double dx = player.getX() - x;
        double dz = player.getZ() - z;
        return dx * dx + dz * dz;
    }

    /** 一次校验结果；拒绝时 accepted 为 null。 */
    public record ValidationResult(
            /** 稳定拒绝原因；接受时为 NONE。 */ RVP_FireSupportEndReason reason,
            /** 完整冻结草案；拒绝时为空。 */ Accepted accepted) {
        static ValidationResult reject(RVP_FireSupportEndReason reason) { return new ValidationResult(reason, null); }
        static ValidationResult accept(Accepted accepted) { return new ValidationResult(RVP_FireSupportEndReason.NONE, accepted); }
        /** @return 是否通过全部校验。 */ public boolean isAccepted() { return accepted != null; }
    }

    /** 创建任务所需的全部冻结输入。 */
    public record Accepted(
            /** 唯一匹配实际手中终端的 profile ID。 */ ResourceLocation profileId,
            /** 冻结 profile。 */ RVP_FireSupportProfile profile,
            /** 冻结弹种。 */ RVP_FireSupportProfile.Munition munition,
            /** 冻结射击模式。 */ RVP_FireSupportProfile.FireMode mode,
            /** 冻结打击预设。 */ RVP_FireSupportProfile.PatternPreset pattern,
            /** 服务端规范化参数。 */ Map<String, Double> parameters,
            /** 服务端完整计划。 */ RVP_FireSupportSchedulePlanner.Plan plan,
            /** 阶段 B 运行时投送器。 */ RVP_FireSupportDelivery delivery,
            /** 本体索引解析的真实 RVP 武器数据。 */ RVP_WeaponData weaponData,
            /** 绑定终端实例 UUID。 */ UUID terminalId,
            /** 权威随机种子。 */ long seed,
            /** 规范化到 [-180,180) 的方向角。 */ double normalizedHeading) {
        public Accepted { parameters = Map.copyOf(parameters); }
    }
}
