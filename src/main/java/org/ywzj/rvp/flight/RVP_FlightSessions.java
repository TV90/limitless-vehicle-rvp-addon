package org.ywzj.rvp.flight;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 不依赖游戏对象的排他控制会话表；每个服务器独立持有，不将凭据持久化。 */
public final class RVP_FlightSessions {
    /** 维度与载具组合，避免跨维度或跨世界串控制。 */
    private record Key(/** 维度。 */ String dimension, /** 载具UUID。 */ UUID vehicleId) {}
    /** 按插入顺序调度，避免HashMap顺序成为业务优先级。 */
    private final Map<Key, Session> sessions = new LinkedHashMap<>();
    /** 同机其他业务的等待队列；请求照常保存，按申请顺序自动交接，不要求再次申请。 */
    private final Map<Key, LinkedHashMap<UUID, Session>> waiting = new LinkedHashMap<>();
    /** 单服务器单调递增接管代次。 */
    private long nextGeneration;

    /** 服务端业务已验证的控制申请；不允许调用者用优先级抢占有效持有者。 */
    public Session acquire(UUID vehicleId, String dimension, UUID owner, BooleanSupplier authorization) {
        Objects.requireNonNull(vehicleId); Objects.requireNonNull(dimension);
        Objects.requireNonNull(owner); Objects.requireNonNull(authorization);
        // 调用授权保护，身份来源异常时不能冒用控制权，也不能打断其他飞机调度。
        if (!authorized(authorization)) return null;
        Key key = new Key(dimension, vehicleId);
        Session current = sessions.get(key);
        // 调用凭据关闭清掉失效占用，并将已有等待者顺序提升，后来申请不能插队。
        if (current != null && !current.valid()) { current.close(); current = sessions.get(key); }
        if (current != null && current.owner.equals(owner)) return current;
        LinkedHashMap<UUID, Session> queue = waiting.get(key);
        Session pending = queue == null ? null : queue.get(owner);
        if (pending != null && pending.valid()) return pending;
        if (pending != null) pending.close();
        Session created = new Session(key, owner, ++nextGeneration, authorization);
        if (current == null) sessions.put(key, created);
        else waiting.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(owner, created);
        return created;
    }

    /** 调度快照，清理会话不会破坏当前迭代。 */
    public List<Session> snapshot() {
        List<Session> result = new ArrayList<>(sessions.values());
        for (var queue : waiting.values()) result.addAll(queue.values());
        return List.copyOf(result);
    }

    /** 当前是否由任何仍有资格的会话接管。 */
    public boolean controlled(UUID vehicleId, String dimension) {
        Session s = sessions.get(new Key(dimension, vehicleId));
        return s != null && s.valid();
    }

    /** 当前持有者退出时提升下一位仍有资格的业务；未提交目标也先按缺省参数稳飞。 */
    private void promote(Key key) {
        LinkedHashMap<UUID, Session> queue = waiting.get(key);
        if (queue == null) return;
        var iterator = queue.values().iterator();
        while (iterator.hasNext()) {
            Session next = iterator.next(); iterator.remove();
            if (!next.closed && authorized(next.authorization)) { sessions.put(key, next); break; }
            next.closed = true;
        }
        if (queue.isEmpty()) waiting.remove(key);
    }

    /** 无法确认授权属于禁止写入的身份边界；隔离回调异常，防止冲垮服务器刻。 */
    private static boolean authorized(BooleanSupplier authorization) {
        try { return authorization.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    /** 单持有者凭据，不可自行构造；请求新鲜度与会话寿命分别管理。 */
    public final class Session {
        /** 会话目标。 */ private final Key key;
        /** 业务持有者UUID。 */ private final UUID owner;
        /** 接管代次。 */ private final long generation;
        /** 每次执行前重验的资格来源。 */ private final BooleanSupplier authorization;
        /** 最近已发布意图。 */ private RVP_FlightRequest request;
        /** 最近控制参数。 */ private RVP_FlightTuning tuning;
        /** 本意图最后新鲜刻；过期后继续目标，只在反馈中提示陈旧。 */ private long expires = Long.MIN_VALUE;
        /** 防止旧提交覆盖新目标。 */ private long lastSubmitted = Long.MIN_VALUE;
        /** 显式关闭标志。 */ private boolean closed;

        private Session(Key key, UUID owner, long generation, BooleanSupplier authorization) {
            this.key = key; this.owner = owner; this.generation = generation; this.authorization = authorization;
        }
        /** 接受并保存意图；新鲜窗口自动修正为1～20刻，旧提交幂等忽略，不要求业务重试。 */
        public boolean submit(RVP_FlightRequest value, RVP_FlightTuning parameters, int validTicks, long tick) {
            if (!valid()) return false;
            if (tick < lastSubmitted || request != null && value != null && request.goalId().equals(value.goalId())
                    && value.revision() < request.revision()) return true;
            // 调用公共缺省请求补齐首次空提交；之后空提交作为继续当前目标的心跳。
            request = value != null ? value : request != null ? request : new RVP_FlightRequest("stabilize", 0, null, null, null, null);
            tuning = parameters != null ? parameters : tuning != null ? tuning : RVP_FlightTuning.smoothCruise();
            lastSubmitted = tick;
            int window = Math.max(1, Math.min(20, validTicks));
            expires = tick > Long.MAX_VALUE - window ? Long.MAX_VALUE : tick + window;
            return true;
        }
        /** 是否仍是此槽位的持有者，不在这里重复判断业务资格。 */
        public boolean current() { return !closed && sessions.get(key) == this; }
        /** 是否仍有控制资格。 */
        public boolean valid() {
            Map<UUID, Session> queue = waiting.get(key);
            // 调用授权保护，等待资格与实际写入资格都不能绕过身份校验。
            return !closed && (current() || queue != null && queue.get(owner) == this) && authorized(authorization);
        }
        /** 已保存意图持续有效，直到业务替换/关闭或资格失效；陈旧并不撤销业务目标。 */
        public RVP_FlightRequest request(long tick) { return valid() && tick >= lastSubmitted ? request : null; }
        /** 仅提供陈旧提示，不用于拒绝执行。 */
        public boolean fresh(long tick) { return request != null && tick >= lastSubmitted && tick <= expires; }
        /** 最近参数，只读。 */ public RVP_FlightTuning tuning() { return tuning; }
        /** 持久载具身份。 */ public UUID vehicleId() { return key.vehicleId; }
        /** 维度身份。 */ public String dimension() { return key.dimension; }
        /** 不可伪造的本会话代次。 */ public long generation() { return generation; }
        /** 业务身份。 */ public UUID owner() { return owner; }
        /** 显式关闭只清本代次，不得删除后来的新持有者。 */
        public void close() {
            boolean active = current(); closed = true;
            Map<UUID, Session> queue = waiting.get(key);
            if (queue != null) { queue.remove(owner, this); if (queue.isEmpty()) waiting.remove(key); }
            if (active) { sessions.remove(key); promote(key); }
        }
    }
}
