package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
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
    /** 命中动画：飞行物从命中点沿来袭方向往外几米处飞来（飞来到命中约 1 秒） */
    public static final long FLY_MS = 1000;
    /** 命中动画：爆点小圆球时长 */
    public static final long BURST_MS = 800;
    /** 命中动画：来袭起点距命中点的距离（米/方块） */
    public static final double START_DIST = 4.0;

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
            this.hitTime = System.currentTimeMillis();
        }
    }

    private static final List<HitEvent> events = new ArrayList<>();
    private static long lastHitTime = System.currentTimeMillis();

    private RVP_ClientHitIndicatorState() {}

    public static void push(S2CRvpHitIndicator msg) {
        lastHitTime = System.currentTimeMillis();
        // 换载具清空；同一载具的多次命中累积展示“命中过程”
        if (!events.isEmpty() && events.get(0).entityId != msg.entityId) {
            events.clear();
        }
        events.add(new HitEvent(msg));
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        // 与本体的命中提示二选一：收到 RVP 命中事件即清空本体事件源，避免两套 UI 同时显示
        org.ywzj.vehicle.client.gui.VehicleHitIndicatorOverlay.events.clear();
    }

    public static void clientTick() {
        // 展示期改用时间戳控制（保留 3 秒），与本体一致，无需每 tick 递减
    }

    public static boolean isActive() {
        // 逐条清理超过保留时间的旧事件（各自独立计时）：否则多次命中会一直累积，红线永不消失
        if (!events.isEmpty()) {
            long now = System.currentTimeMillis();
            events.removeIf(e -> now - e.hitTime > KEEP_MS);
        }
        return !events.isEmpty() && System.currentTimeMillis() - lastHitTime <= KEEP_MS;
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

    /** 命中总伤害（多次命中求和，与本体一致） */
    public static float getDamage() {
        float sum = 0;
        for (HitEvent e : events) {
            sum += e.damage;
        }
        return sum;
    }

    public static String getBoneDisplayName() {
        return events.isEmpty() ? "" : events.get(0).boneDisplayName;
    }

    public static String getAmmoNameKey() {
        return events.isEmpty() ? "" : events.get(0).ammoNameKey;
    }
}
