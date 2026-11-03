package org.ywzj.rvp.replay;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.vehicle.BoneModuleType;

import java.util.*;

/** 命中回放的不可变数值快照；不持有世界、实体、模型或完整 NBT。 */
public final class RVP_HitReplay {
    /** 一秒历史，包含起止端点。 */
    public static final int HISTORY_TICKS = 20;
    /** 包中集合硬上限，防止异常配置扩大网络分配。 */
    public static final int MAX_PARTS = 256;
    private RVP_HitReplay() {}

    /** 部件索引、水平/俯仰角（度）；开关 -1 表示旋转部件，0/1 表示开关。 */
    public record Part(int index, float pitch, float yaw, int toggle) {}
    /** 世界 tick、位置（格）、速度（格/tick）、车体欧拉角（度）和当时的损伤状态。 */
    public record Frame(long tick, Vec3 position, Vec3 velocity, float pitch, float yaw, float roll,
                        float health, boolean destroyed, List<Part> parts, Map<Integer, Float> damagedParts, Set<Integer> detached,
                        Map<String, Set<BoneModuleType>> inactive) {
        public Frame {
            parts = List.copyOf(parts);
            damagedParts = Map.copyOf(damagedParts);
            detached = Set.copyOf(detached);
            Map<String, Set<BoneModuleType>> copy = new HashMap<>();
            inactive.forEach((bone, types) -> copy.put(bone, Set.copyOf(types)));
            inactive = Map.copyOf(copy);
        }
    }
    /** 弹体轨迹的 tick 与世界位置；最多保留一秒，不推算出生前或缺失区间。 */
    public record Point(long tick, Vec3 position) {}
    /** 命中点绑定到实际结构块；part=-2 为车体局部兜底，-1 为车体碰撞块。 */
    public record Anchor(int part, int cube, Vec3 point, Vec3 incoming) {}
    /** 命中包增量：UUID 防实体 ID 复用；reset 表示需要替换此前目标历史。 */
    public record Clip(UUID target, ResourceLocation entityType, ResourceLocation vehicleId,
                       ResourceLocation displayId, long impactTick, boolean reset,
                       List<Frame> frames, List<Point> projectile, Anchor anchor) {
        public Clip {
            frames = List.copyOf(frames);
            projectile = List.copyOf(projectile);
        }
    }

    /** 固定长度时间窗；同 tick 覆盖端点，旧样本不倒灌。 */
    public static final class Window<T> {
        /** 只保存最近 21 个 tick，命中发包时才复制列表。 */
        private final NavigableMap<Long, T> samples = new TreeMap<>();
        public void put(long tick, T value) {
            if (!samples.isEmpty() && tick < samples.lastKey() - HISTORY_TICKS) return;
            samples.put(tick, value);
            samples.headMap(samples.lastKey() - HISTORY_TICKS, false).clear();
        }
        public List<T> after(long tick) { return List.copyOf(samples.tailMap(tick, false).values()); }
        public List<T> all() { return List.copyOf(samples.values()); }
    }

    /** 最短弧角度插值，跨 ±180° 时不绕远路。 */
    public static float angle(float a, float b, double t) {
        return a + Mth.wrapDegrees(b - a) * (float) t;
    }

    /** 损伤/开关是离散状态，仅到达新帧时生效，不能提前显示命中后的损坏。 */
    public static Frame sample(List<Frame> frames, double tick) {
        if (frames.isEmpty()) return null;
        Frame a = frames.get(0);
        if (tick <= a.tick()) return a;
        for (int i = 1; i < frames.size(); i++) {
            Frame b = frames.get(i);
            if (tick < b.tick()) {
                double t = (tick - a.tick()) / (b.tick() - a.tick());
                List<Part> parts = new ArrayList<>(a.parts().size());
                for (int p = 0; p < a.parts().size(); p++) {
                    Part old = a.parts().get(p);
                    Part next = p < b.parts().size() && b.parts().get(p).index() == old.index()
                            ? b.parts().get(p) : old;
                    parts.add(new Part(old.index(), angle(old.pitch(), next.pitch(), t),
                            angle(old.yaw(), next.yaw(), t), old.toggle()));
                }
                return new Frame(a.tick(), a.position().lerp(b.position(), t),
                        a.velocity().lerp(b.velocity(), t), angle(a.pitch(), b.pitch(), t),
                        angle(a.yaw(), b.yaw(), t), angle(a.roll(), b.roll(), t),
                        a.health(), a.destroyed(), parts, a.damagedParts(), a.detached(), a.inactive());
            }
            a = b;
        }
        return a;
    }

    /** 缺失超过一刻的轨迹不连成凭空穿越地图的长线。 */
    public static Vec3 projectileAt(List<Point> points, double tick) {
        if (points.isEmpty() || tick < points.get(0).tick()) return null;
        Point a = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            Point b = points.get(i);
            if (tick <= b.tick()) {
                if (b.tick() - a.tick() > 1) return tick == b.tick() ? b.position() : null;
                return a.position().lerp(b.position(), (tick - a.tick()) / Math.max(1, b.tick() - a.tick()));
            }
            a = b;
        }
        return a.position();
    }

    public static void write(FriendlyByteBuf buf, Clip clip) {
        buf.writeBoolean(clip != null);
        if (clip == null) return;
        buf.writeUUID(clip.target());
        buf.writeResourceLocation(clip.entityType());
        buf.writeResourceLocation(clip.vehicleId());
        buf.writeResourceLocation(clip.displayId());
        buf.writeLong(clip.impactTick());
        buf.writeBoolean(clip.reset());
        buf.writeVarInt(clip.frames().size());
        Map<String, Set<BoneModuleType>> previous = null;
        for (Frame f : clip.frames()) {
            buf.writeLong(f.tick());
            writeVec(buf, f.position());
            buf.writeVector3f(f.velocity().toVector3f());
            buf.writeFloat(f.pitch()); buf.writeFloat(f.yaw()); buf.writeFloat(f.roll());
            buf.writeFloat(f.health()); buf.writeBoolean(f.destroyed());
            buf.writeVarInt(f.parts().size());
            for (Part p : f.parts()) {
                buf.writeVarInt(p.index()); buf.writeFloat(p.pitch()); buf.writeFloat(p.yaw()); buf.writeByte(p.toggle());
            }
            buf.writeVarInt(f.damagedParts().size());
            f.damagedParts().forEach((index, health) -> { buf.writeVarInt(index); buf.writeFloat(health); });
            buf.writeVarInt(f.detached().size());
            f.detached().forEach(buf::writeVarInt);
            boolean changed = !f.inactive().equals(previous);
            buf.writeBoolean(changed);
            if (changed) {
                buf.writeVarInt(f.inactive().size());
                f.inactive().forEach((bone, types) -> {
                    buf.writeUtf(bone, 128);
                    int mask = 0;
                    for (BoneModuleType type : types) mask |= 1 << type.ordinal();
                    buf.writeVarInt(mask);
                });
                previous = f.inactive();
            }
        }
        buf.writeVarInt(clip.projectile().size());
        for (Point point : clip.projectile()) { buf.writeLong(point.tick()); writeVec(buf, point.position()); }
        Anchor anchor = clip.anchor();
        buf.writeInt(anchor.part()); buf.writeInt(anchor.cube());
        buf.writeVector3f(anchor.point().toVector3f()); buf.writeVector3f(anchor.incoming().toVector3f());
    }

    public static Clip read(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) return null;
        UUID target = buf.readUUID();
        ResourceLocation type = buf.readResourceLocation(), vehicle = buf.readResourceLocation(), display = buf.readResourceLocation();
        long impact = buf.readLong();
        boolean reset = buf.readBoolean();
        int count = count(buf, HISTORY_TICKS + 1);
        List<Frame> frames = new ArrayList<>(count);
        Map<String, Set<BoneModuleType>> inactive = Map.of();
        for (int i = 0; i < count; i++) {
            long tick = buf.readLong();
            Vec3 pos = readVec(buf), velocity = new Vec3(buf.readVector3f());
            float pitch = buf.readFloat(), yaw = buf.readFloat(), roll = buf.readFloat(), health = buf.readFloat();
            boolean destroyed = buf.readBoolean();
            int n = count(buf, MAX_PARTS);
            List<Part> parts = new ArrayList<>(n);
            for (int p = 0; p < n; p++) parts.add(new Part(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readByte()));
            n = count(buf, MAX_PARTS);
            Map<Integer, Float> damaged = new HashMap<>();
            for (int p = 0; p < n; p++) damaged.put(buf.readVarInt(), buf.readFloat());
            n = count(buf, MAX_PARTS);
            Set<Integer> detached = new HashSet<>();
            for (int p = 0; p < n; p++) detached.add(buf.readVarInt());
            if (buf.readBoolean()) {
                inactive = new HashMap<>();
                n = count(buf, 1024);
                for (int p = 0; p < n; p++) {
                    String bone = buf.readUtf(128); int mask = buf.readVarInt();
                    Set<BoneModuleType> types = EnumSet.noneOf(BoneModuleType.class);
                    for (BoneModuleType t : BoneModuleType.values()) if ((mask & (1 << t.ordinal())) != 0) types.add(t);
                    inactive.put(bone, types);
                }
            }
            frames.add(new Frame(tick, pos, velocity, pitch, yaw, roll, health, destroyed, parts, damaged, detached, inactive));
        }
        int n = count(buf, HISTORY_TICKS + 1);
        List<Point> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) points.add(new Point(buf.readLong(), readVec(buf)));
        Anchor anchor = new Anchor(buf.readInt(), buf.readInt(), new Vec3(buf.readVector3f()), new Vec3(buf.readVector3f()));
        return new Clip(target, type, vehicle, display, impact, reset, frames, points, anchor);
    }

    private static int count(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("回放条目数量越界: " + n);
        return n;
    }
    private static void writeVec(FriendlyByteBuf buf, Vec3 vec) {
        buf.writeDouble(vec.x); buf.writeDouble(vec.y); buf.writeDouble(vec.z);
    }
    private static Vec3 readVec(FriendlyByteBuf buf) { return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()); }
}
