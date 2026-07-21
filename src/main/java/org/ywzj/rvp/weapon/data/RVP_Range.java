package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable normalized union of closed intervals.
 * A null lower or upper bound represents negative or positive infinity.
 */
@JsonAdapter(RVP_Range.AdapterFactory.class)
public final class RVP_Range<T extends Comparable<T>> {

    private final List<Interval<T>> intervals;

    public RVP_Range(List<Interval<T>> intervals) {
        this.intervals = normalize(intervals);
    }

    @SafeVarargs
    public static <T extends Comparable<T>> RVP_Range<T> of(Interval<T>... intervals) {
        if (intervals == null) {
            throw new JsonParseException("Range intervals must not be null");
        }
        return new RVP_Range<>(Arrays.asList(intervals));
    }

    public static <T extends Comparable<T>> RVP_Range<T> closed(T lower, T upper) {
        return of(new Interval<>(lower, upper));
    }

    public static <T extends Comparable<T>> Interval<T> interval(T lower, T upper) {
        return new Interval<>(lower, upper);
    }

    public List<Interval<T>> intervals() {
        return intervals;
    }

    public boolean contains(T value) {
        if (value == null || isIllegalNumber(value)) {
            return false;
        }
        for (Interval<T> interval : intervals) {
            if (interval.lower != null && value.compareTo(interval.lower) < 0) {
                return false;
            }
            if (interval.upper == null || value.compareTo(interval.upper) <= 0) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Comparable<T>> List<Interval<T>> normalize(List<Interval<T>> source) {
        if (source == null || source.isEmpty()) {
            throw new JsonParseException("Range must contain at least one interval");
        }

        List<Interval<T>> sorted = new ArrayList<>(source.size());
        for (Interval<T> interval : source) {
            if (interval == null) {
                throw new JsonParseException("Range interval must not be null");
            }
            validateBound(interval.lower, "lower");
            validateBound(interval.upper, "upper");
            if (interval.lower != null && interval.upper != null
                    && interval.lower.compareTo(interval.upper) > 0) {
                throw new JsonParseException("Range lower bound exceeds upper bound: " + interval);
            }
            sorted.add(interval);
        }

        sorted.sort(Comparator
                .comparing((Interval<T> interval) -> interval.lower,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(interval -> interval.upper,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        List<Interval<T>> merged = new ArrayList<>(sorted.size());
        for (Interval<T> next : sorted) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }

            Interval<T> current = merged.get(merged.size() - 1);
            if (!overlaps(current, next)) {
                merged.add(next);
                continue;
            }

            T upper = maxUpper(current.upper, next.upper);
            merged.set(merged.size() - 1, new Interval<>(current.lower, upper));
        }
        return Collections.unmodifiableList(merged);
    }

    private static <T extends Comparable<T>> boolean overlaps(Interval<T> left, Interval<T> right) {
        return left.upper == null || right.lower == null || left.upper.compareTo(right.lower) >= 0;
    }

    private static <T extends Comparable<T>> T maxUpper(T left, T right) {
        if (left == null || right == null) {
            return null;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static void validateBound(Object value, String name) {
        if (isIllegalNumber(value)) {
            throw new JsonParseException("Range " + name + " bound must be finite: " + value);
        }
    }

    private static boolean isIllegalNumber(Object value) {
        if (value instanceof Float number) {
            return !Float.isFinite(number);
        }
        if (value instanceof Double number) {
            return !Double.isFinite(number);
        }
        return false;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof RVP_Range<?> range)) {
            return false;
        }
        return intervals.equals(range.intervals);
    }

    @Override
    public int hashCode() {
        return intervals.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < intervals.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            Interval<T> interval = intervals.get(index);
            builder.append('[')
                    .append(formatBound(interval.lower))
                    .append(',')
                    .append(formatBound(interval.upper))
                    .append(']');
        }
        return builder.append(']').toString();
    }

    private static String formatBound(Object bound) {
        return bound == null ? "inf" : bound.toString();
    }

    public static final class Interval<T extends Comparable<T>> {
        private final T lower;
        private final T upper;

        public Interval(T lower, T upper) {
            this.lower = lower;
            this.upper = upper;
        }

        public T lower() {
            return lower;
        }

        public T upper() {
            return upper;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Interval<?> interval)) {
                return false;
            }
            return Objects.equals(lower, interval.lower) && Objects.equals(upper, interval.upper);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lower, upper);
        }

        @Override
        public String toString() {
            return "[" + formatBound(lower) + "," + formatBound(upper) + "]";
        }
    }

    public static final class AdapterFactory implements TypeAdapterFactory {

        @Override
        public <R> TypeAdapter<R> create(Gson gson, TypeToken<R> typeToken) {
            if (typeToken.getRawType() != RVP_Range.class) {
                return null;
            }
            Type type = typeToken.getType();
            if (!(type instanceof ParameterizedType parameterizedType)) {
                throw new JsonParseException("RVP_Range requires an Integer or Float type argument");
            }
            BoundCodec<?> codec = codecFor(parameterizedType.getActualTypeArguments()[0]);
            @SuppressWarnings({"rawtypes", "unchecked"})
            TypeAdapter<R> adapter = (TypeAdapter<R>) new RangeAdapter(codec);
            return adapter;
        }

        private static BoundCodec<?> codecFor(Type type) {
            if (type == Integer.class) {
                return IntegerCodec.INSTANCE;
            }
            if (type == Float.class) {
                return FloatCodec.INSTANCE;
            }
            throw new JsonParseException("Unsupported RVP_Range bound type: " + type.getTypeName());
        }
    }

    private static final class RangeAdapter<T extends Comparable<T>> extends TypeAdapter<RVP_Range<T>> {
        private final BoundCodec<T> codec;

        private RangeAdapter(BoundCodec<T> codec) {
            this.codec = codec;
        }

        @Override
        public void write(JsonWriter out, RVP_Range<T> value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.value(value.toString());
        }

        @Override
        public RVP_Range<T> read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            if (token == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            if (token == JsonToken.STRING) {
                return parseEncoded(in.nextString());
            }
            if (token == JsonToken.BEGIN_ARRAY) {
                return readArray(in);
            }
            throw new JsonParseException("RVP_Range must be a string or an array, got " + token);
        }

        private RVP_Range<T> parseEncoded(String encoded) {
            try {
                JsonReader reader = new JsonReader(new StringReader(encoded));
                reader.setLenient(true);
                RVP_Range<T> range = readArray(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new JsonParseException("Unexpected content after RVP_Range: " + encoded);
                }
                return range;
            } catch (IOException | IllegalStateException exception) {
                throw new JsonParseException("Invalid RVP_Range: " + encoded, exception);
            }
        }

        private RVP_Range<T> readArray(JsonReader in) throws IOException {
            List<Interval<T>> intervals = new ArrayList<>();
            in.beginArray();
            while (in.hasNext()) {
                if (in.peek() != JsonToken.BEGIN_ARRAY) {
                    throw new JsonParseException("Each RVP_Range interval must be an array");
                }
                in.beginArray();
                if (!in.hasNext()) {
                    throw new JsonParseException("RVP_Range interval is empty");
                }
                T lower = readBound(in);
                if (!in.hasNext()) {
                    throw new JsonParseException("RVP_Range interval is missing its upper bound");
                }
                T upper = readBound(in);
                if (in.hasNext()) {
                    throw new JsonParseException("RVP_Range interval must contain exactly two bounds");
                }
                in.endArray();
                intervals.add(new Interval<>(lower, upper));
            }
            in.endArray();
            return new RVP_Range<>(intervals);
        }

        private T readBound(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            if (token == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            if (token != JsonToken.NUMBER && token != JsonToken.STRING) {
                throw new JsonParseException("RVP_Range bound must be a number, inf, or null");
            }
            String raw = in.nextString().trim();
            if (isInfinityToken(raw)) {
                return null;
            }
            try {
                return codec.parse(raw);
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new JsonParseException("Invalid RVP_Range bound: " + raw, exception);
            }
        }
    }

    private static boolean isInfinityToken(String raw) {
        return "inf".equalsIgnoreCase(raw)
                || "+inf".equalsIgnoreCase(raw)
                || "-inf".equalsIgnoreCase(raw)
                || "infinity".equalsIgnoreCase(raw)
                || "+infinity".equalsIgnoreCase(raw)
                || "-infinity".equalsIgnoreCase(raw);
    }

    private interface BoundCodec<T extends Comparable<T>> {
        T parse(String raw);
    }

    private enum IntegerCodec implements BoundCodec<Integer> {
        INSTANCE;

        @Override
        public Integer parse(String raw) {
            return new BigDecimal(raw).intValueExact();
        }
    }

    private enum FloatCodec implements BoundCodec<Float> {
        INSTANCE;

        @Override
        public Float parse(String raw) {
            float value = Float.parseFloat(raw);
            if (!Float.isFinite(value)) {
                throw new NumberFormatException("Float bound must be finite");
            }
            return value;
        }
    }
}
