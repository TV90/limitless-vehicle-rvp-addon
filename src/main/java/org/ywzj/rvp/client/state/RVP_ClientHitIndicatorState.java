package org.ywzj.rvp.client.state;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.ywzj.rvp.network.S2CRvpHitIndicator;

import java.util.ArrayList;
import java.util.List;

/**
 * RVP 命中提示客户端状态：保存短时间内的多次载具命中事件，供右上角展板 UI 渲染。
 * <p>与本体的 events 列表语义一致：同一载具的多次命中累积展示（画多条红线，体现命中过程），
 * 换载具或超过保留时间（3 秒）后清空。</p>
 */
public final class RVP_ClientHitIndicatorState {

    /** 命中过程保留时间（毫秒），与本体 KEEP_TIME 一致 */
    private static final long KEEP_MS = 3000;
    /** 最多保留的事件数，防止内存无限增长（与本体上限一致） */
    private static final int MAX_EVENTS = 128;
    /**
     * 视角重置角度阈值（度）：与当前视角基准（第一条事件）的来袭方向夹角 ≥ 该值时重置视角基准。
     * 视角基准决定展板模型绕 Y 的观察角度 —— 用第一发命中的方向固定，防止频繁射击导致
     * 射角多次跳变；只有转攻大角度（侧击 → 正面等）时才切换视角，切换后与新基准继续对比。
     */
    private static final double VIEW_RESET_ANGLE_DEG = 75.0;
    /** 命中动画：飞行物从命中点沿来袭方向往外几米处飞来（飞来到命中约 1 秒） */
    public static final long FLY_MS = 1000;
    /** 命中动画：爆点小圆球时长 */
    public static final long BURST_MS = 800;
    /** 命中动画：爆炸破片飞溅时长（红圈结束后破片飞向车体） */
    public static final long FRAG_MS = 800;
    /** 命中动画：来袭起点距命中点的距离（米/方块） */
    public static final double START_DIST = 4.0;
    /**
     * 命中圈动画总时长（毫秒）：飞行段 + 爆点段 + 破片段。爆反被摧毁动画必须等
     * 命中圈动画播完才开始（用户在时序上的明确要求）。
     */
    public static final long HIT_ANIM_END_MS = FLY_MS + BURST_MS + FRAG_MS;
    /** 爆反被摧毁动画时长（毫秒）：红色 → 深黑红 → 透明 */
    public static final long ERA_ANIM_MS = 1500;

    /** 单次命中事件 */
    public static final class HitEvent {
        public final int entityId;
        public final Vec3 hitPosition;
        public final Vec3 hitVector;
        public final float damage;
        public final String boneDisplayName;
        public final String ammoNameKey;
        /** 武器 id（可空）：客户端据此查询武器 display 判断“有无模型” */
        public final String weaponId;
        /** 来袭方向（世界坐标单位向量）：与弹道相反，从远处飞向命中点 */
        public final Vec3 incomingDir;
        /**
         * 命中时刻的被命中实体位置（世界坐标）：超视距实体由广播克隆体渲染（位置可能滞后），
         * 渲染前用它临时对齐模型位置，避免命中特效相对模型偏移出展示框。
         */
        public final Vec3 entityPosAtHit;
        /** 被命中载具的 displayId（可空）：超视距克隆实体 displayId 缺失时用它初始化渲染 */
        public final String vehicleDisplayId;
        /** 爆炸半径（方块）：>0 时客户端渲染"随爆炸范围扩大的扩散圈"；0 = 无爆炸信息 */
        public final float explosionRadius;
        /** 命中事件产生时间戳（毫秒）：驱动飞行/爆点/红线三段动画 */
        public final long hitTime;

        public HitEvent(S2CRvpHitIndicator msg) {
            this.entityId = msg.entityId;
            this.hitPosition = msg.hitPosition;
            this.hitVector = msg.hitVector;
            this.damage = msg.damage;
            this.boneDisplayName = msg.boneDisplayName;
            this.ammoNameKey = msg.ammoNameKey;
            this.weaponId = msg.weaponId;
            Vec3 hv = msg.hitVector;
            this.incomingDir = (hv != null && hv.lengthSqr() > 1.0E-6)
                    ? hv.normalize().scale(-1) : new Vec3(0, 0, -1);
            this.entityPosAtHit = msg.entityPosAtHit != null ? msg.entityPosAtHit : msg.hitPosition;
            this.vehicleDisplayId = msg.vehicleDisplayId == null ? "" : msg.vehicleDisplayId;
            this.explosionRadius = msg.explosionRadius;
            this.hitTime = System.currentTimeMillis();
        }
    }

    private static final List<HitEvent> events = new ArrayList<>();
    private static long lastHitTime = System.currentTimeMillis();
    /**
     * 累计命中伤害（独立于动画事件列表累加）：
     * 事件列表有 MAX_EVENTS 上限（滑动窗口丢旧事件），若伤害从事件求和，高速武器
     * （如机炮）会因事件被丢弃而卡在上限发数的总伤害（≈3 秒）上不去 —— 独立累加器
     * 只要持续命中就持续增长，停止命中超过 3 秒才清零，不间断扫射 10 秒即显示 10 秒累计。
     */
    private static float accumulatedDamage;
    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_ClientHitIndicatorState() {}

    public static void push(S2CRvpHitIndicator msg) {
        LOGGER.info("[RVP-HitUI] push: entityId={} bone={} dmg={} radius={}",
                msg.entityId, msg.boneDisplayName, msg.damage, msg.explosionRadius);
        lastHitTime = System.currentTimeMillis();
        // 换载具清空；同一载具的多次命中累积展示“命中过程”
        if (!events.isEmpty() && events.get(0).entityId != msg.entityId) {
            events.clear();
            accumulatedDamage = 0;
        } else if (!events.isEmpty()) {
            // 视角稳定性：新命中与当前视角基准（第一条事件）的来袭方向夹角 ≥ 阈值时才重置视角基准。
            // 保留第一发确定的观察角度（防频繁射击导致射角多次跳变），转攻大角度时才切换视角；
            // 重置只清事件（第一条成为新基准），伤害累计 accumulatedDamage 不重置，持续累计。
            HitEvent base = events.get(0);
            Vec3 baseDir = base.hitVector;
            Vec3 newDir = msg.hitVector;
            if (baseDir != null && newDir != null
                    && baseDir.lengthSqr() > 1.0E-6 && newDir.lengthSqr() > 1.0E-6) {
                double cos = baseDir.normalize().dot(newDir.normalize());
                cos = Math.max(-1.0, Math.min(1.0, cos));
                if (Math.toDegrees(Math.acos(cos)) >= VIEW_RESET_ANGLE_DEG) {
                    events.clear();
                }
            }
        }
        events.add(new HitEvent(msg));
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        accumulatedDamage += msg.damage;
        // 与本体的命中提示二选一：收到 RVP 命中事件即清空本体事件源，避免两套 UI 同时显示
        org.ywzj.vehicle.client.gui.VehicleHitIndicatorOverlay.events.clear();
    }

    public static void clientTick() {
        // 展示期改用时间戳控制（保留 3 秒），与本体一致，无需每 tick 递减
    }

    public static boolean isActive() {
        // 3 秒静默超时：最后一次命中后 3 秒内持续展示并累计伤害；期间每来一次新命中都会
        // 刷新超时起点（push 更新 lastHitTime），因此连续射击时伤害一直累加 ——
        // 如不间断扫射 10 秒，显示的就是这 10 秒的累计伤害，而不是 3 秒滑动窗口。
        // 停止命中超过 3 秒才整体清空（与本体的 events 语义一致）。
        // 载具有被摧毁的爆反（ERA）时，展示窗口延长到"命中圈动画 + 爆反动画"（共 4.1 秒），
        // 保证爆反燃烧动画（在命中圈动画之后播放）不会因 3 秒超时被中途清空。
        long keepMs = hasPendingEraBurn() ? HIT_ANIM_END_MS + ERA_ANIM_MS : KEEP_MS;
        if (System.currentTimeMillis() - lastHitTime > keepMs) {
            events.clear();
            accumulatedDamage = 0;
        }
        return !events.isEmpty();
    }

    /** 当前展示载具是否还有"被摧毁的爆反"待播动画（爆反动画需要延长展示窗口）。 */
    private static boolean hasPendingEraBurn() {
        if (events.isEmpty()) {
            return false;
        }
        return !RVP_ClientBoneModuleState.getInactiveEraBones(events.get(0).entityId).isEmpty();
    }

    /** 全部命中事件 */
    public static List<HitEvent> getEvents() {
        return events;
    }

    // ---- 兼容 getters（基于第一条事件） ----

    public static int getEntityId() {
        return events.isEmpty() ? -1 : events.get(0).entityId;
    }

    public static Vec3 getHitPosition() {
        return events.isEmpty() ? null : events.get(0).hitPosition;
    }

    public static Vec3 getHitVector() {
        return events.isEmpty() ? null : events.get(0).hitVector;
    }

    /** 命中总伤害：独立累加器，持续命中持续累加（不受事件列表上限影响），静默 3 秒才清零 */
    public static float getDamage() {
        return accumulatedDamage;
    }

    /**
     * 最近一次命中部位（取最后一条事件）：实时反映当前命中的部位，而不是固定显示首发命中的部位
     * （第一发打炮塔后后续命中车体仍显示炮塔 —— 旧 bug）。
     */
    public static String getBoneDisplayName() {
        return events.isEmpty() ? "" : events.get(events.size() - 1).boneDisplayName;
    }

    public static String getAmmoNameKey() {
        return events.isEmpty() ? "" : events.get(0).ammoNameKey;
    }
}
