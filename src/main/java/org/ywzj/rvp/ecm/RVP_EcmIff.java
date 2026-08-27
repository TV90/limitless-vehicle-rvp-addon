package org.ywzj.rvp.ecm;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 被动电子战敌我识别（IFF）工具。
 *
 * <p>判定语义对齐 {@code GunnerTargeting}（CIWS 敌我判定同源），核心是
 * 载具与载具之间的友好关系原语 {@link #areVehiclesFriendly}：</p>
 * <ul>
 *   <li>同一载具 → 友方；</li>
 *   <li>双方均 gunner 驾驶且同 faction（含 ENEMY-ENEMY，用户批示互不触发）→ 友方；</li>
 *   <li>gunner 为 FRIENDLY 阵营 → 视为友方；</li>
 *   <li>gunner 放置者链 / 乘客关系 / Team 联盟 → 友方；</li>
 *   <li>其余 → 敌对。</li>
 * </ul>
 *
 * <p><b>中立判定</b>：雷达载具无驾驶者、或驾驶者既非玩家也非 gunner → 中立，
 * 不触发被动电子战（用户硬性要求）。</p>
 */
public final class RVP_EcmIff {

    private RVP_EcmIff() {
    }

    /** 雷达载具是否"无主中立"：无驾驶者或驾驶者既非玩家也非 gunner。 */
    public static boolean isNeutralRadarVehicle(@Nullable AbstractVehicle radarVehicle) {
        if (radarVehicle == null) {
            return true;
        }
        Entity driver = radarVehicle.getDriver();
        return !(driver instanceof Player) && !(driver instanceof GunnerEntity);
    }

    /**
     * 两载具是否互为友方（ECM 触发/可见性共用同一套关系原语）。
     * 判定按序短路，任一命中即友方。
     */
    public static boolean areVehiclesFriendly(@Nullable AbstractVehicle a, @Nullable AbstractVehicle b) {
        if (a == null || b == null) {
            return false;
        }
        if (a == b || a.getUUID().equals(b.getUUID())) {
            return true;
        }
        Entity da = a.getDriver();
        Entity db = b.getDriver();

        // 双方均 gunner 驾驶：同 faction 即友方（含 FRIENDLY-FRIENDLY 与 ENEMY-ENEMY 互不触发）
        if (da instanceof GunnerEntity ga && db instanceof GunnerEntity gb
                && ga.getProfileFaction() == gb.getProfileFaction()) {
            return true;
        }
        // FRIENDLY 阵营 gunner 对任何载具视为友方（不触发其 ECM）
        if (da instanceof GunnerEntity ga2
                && ga2.getProfileFaction() == RVP_EnumGunnerFaction.FRIENDLY) {
            return true;
        }
        if (db instanceof GunnerEntity gb2
                && gb2.getProfileFaction() == RVP_EnumGunnerFaction.FRIENDLY) {
            return true;
        }
        // gunner 放置者链：对方玩家是该 gunner 的放置者。
        // 仅 FRIENDLY/中立阵营 gunner 才与放置者互为友方；ENEMY 阵营 gunner 不与放置者结盟，
        // 放置者对其而言是敌对目标（否则敌方 gunner 的 ECM/雷达不会对放置者生效）。
        if (da instanceof GunnerEntity ga3 && ga3.getProfileFaction() != RVP_EnumGunnerFaction.ENEMY
                && db instanceof Player ownerPlayer && ga3.isOwnedBy(ownerPlayer)) {
            return true;
        }
        if (db instanceof GunnerEntity gb3 && gb3.getProfileFaction() != RVP_EnumGunnerFaction.ENEMY
                && da instanceof Player ownerPlayer2 && gb3.isOwnedBy(ownerPlayer2)) {
            return true;
        }
        // 乘客关系：一方驾驶者是另一方的乘客
        if (da != null && b.getPassengers().contains(da)) {
            return true;
        }
        if (db != null && a.getPassengers().contains(db)) {
            return true;
        }
        // Team 联盟（任一方向）
        Team teamA = a.getTeam();
        Team teamB = b.getTeam();
        if (teamA != null && teamB != null
                && (teamB.isAlliedTo(teamA) || teamA.isAlliedTo(teamB))) {
            return true;
        }
        return false;
    }

    /** 照射判定：雷达载具非中立且与被照载体敌对时才触发被动电子战。 */
    public static boolean isHostileIllumination(@Nullable AbstractVehicle radarVehicle,
                                                @Nullable AbstractVehicle ewVehicle) {
        if (isNeutralRadarVehicle(radarVehicle)) {
            return false;
        }
        return !areVehiclesFriendly(radarVehicle, ewVehicle);
    }

    /**
     * 假目标对该观察方（雷达/gunner 所属载具）是否为"可攻击的敌方幻影"。
     * 归属方与其友方不可见/不可锁/不攻击（§7.3 过滤 + §7.4 看门狗共用）。
     */
    public static boolean isDecoyHostileTo(@Nullable RVP_EcmDecoyEntity decoy,
                                           @Nullable AbstractVehicle observerVehicle) {
        if (decoy == null || observerVehicle == null || observerVehicle.level().isClientSide()) {
            return false;
        }
        Entity owner = observerVehicle.level().getEntity(decoy.getOwnerVehicleId());
        if (!(owner instanceof AbstractVehicle ownerVehicle)) {
            // 归属载具已不存在：假目标无害，视为友方（不攻击、随寿命自然消亡）
            return false;
        }
        return !areVehiclesFriendly(observerVehicle, ownerVehicle);
    }
}
