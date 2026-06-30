package org.ywzj.rvp.client.state;

import org.jetbrains.annotations.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class RVP_ClientGPSState {

    public enum Mode {
        SINGLE,
        MULTI;

        public Mode toggled() {
            return this == SINGLE ? MULTI : SINGLE;
        }
    }

    public record Point(ResourceLocation dimension, Vec3 pos) {}

    private static Mode mode = Mode.SINGLE;
    private static final List<Point> POINTS = new ArrayList<>();
    private static int nextIndex;

    public static void replace(Mode newMode, List<Point> points, int newNextIndex) {
        mode = newMode == null ? Mode.SINGLE : newMode;
        POINTS.clear();
        if (points != null) {
            POINTS.addAll(points);
        }
        nextIndex = normalizeIndex(newNextIndex, POINTS.size());
    }

    public static void set(ResourceLocation dim, Vec3 p) {
        replace(Mode.SINGLE, List.of(new Point(dim, p)), 0);
    }

    public static void addPoint(ResourceLocation dim, Vec3 p) {
        if (mode != Mode.MULTI) {
            set(dim, p);
            return;
        }
        POINTS.add(new Point(dim, p));
        nextIndex = normalizeIndex(nextIndex, POINTS.size());
    }

    public static void setMode(Mode newMode) {
        if (newMode == null || newMode == mode) {
            return;
        }
        if (newMode == Mode.SINGLE) {
            if (POINTS.isEmpty()) {
                replace(Mode.SINGLE, List.of(), 0);
            } else {
                Point last = POINTS.get(POINTS.size() - 1);
                replace(Mode.SINGLE, List.of(last), 0);
            }
            return;
        }
        mode = Mode.MULTI;
        nextIndex = normalizeIndex(nextIndex, POINTS.size());
    }

    public static void clear() {
        POINTS.clear();
        nextIndex = 0;
    }

    public static boolean isActive() {
        return !POINTS.isEmpty();
    }

    public static Mode getMode() {
        return mode;
    }

    public static boolean isMultiMode() {
        return mode == Mode.MULTI;
    }

    @Nullable
    public static ResourceLocation getDimension() {
        Point point = getArmedPoint();
        return point == null ? null : point.dimension();
    }

    @Nullable
    public static Vec3 getPos() {
        Point point = getArmedPoint();
        return point == null ? null : point.pos();
    }

    public static List<Point> getPoints() {
        return List.copyOf(POINTS);
    }

    public static List<Point> getPointsForDimension(@Nullable ResourceLocation dimension) {
        if (dimension == null) {
            return List.of();
        }
        return POINTS.stream().filter(point -> dimension.equals(point.dimension())).toList();
    }

    @Nullable
    public static Point getArmedPoint() {
        if (POINTS.isEmpty()) {
            return null;
        }
        return POINTS.get(normalizeIndex(nextIndex, POINTS.size()));
    }

    public static int getArmedPointNumber() {
        return isActive() ? normalizeIndex(nextIndex, POINTS.size()) + 1 : 0;
    }

    public static int getPointCount() {
        return POINTS.size();
    }

    public static int getNextIndex() {
        return nextIndex;
    }

    private static int normalizeIndex(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        int wrapped = index % size;
        return wrapped < 0 ? wrapped + size : wrapped;
    }
}
