package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_RangeTest {

    private final Gson gson = new Gson();

    @Test
    void containsIncludesClosedBoundaries() {
        RVP_Range<Integer> range = RVP_Range.closed(10, 20);

        assertFalse(range.contains(9));
        assertTrue(range.contains(10));
        assertTrue(range.contains(20));
        assertFalse(range.contains(21));
    }

    @Test
    void normalizesUnsortedOverlappingIntervals() {
        RVP_Range<Integer> range = new RVP_Range<>(List.of(
                RVP_Range.interval(20, 30),
                RVP_Range.interval(0, 10),
                RVP_Range.interval(8, 25),
                RVP_Range.interval(40, 50)
        ));

        assertEquals("[[0,30],[40,50]]", range.toString());
        assertTrue(range.contains(22));
        assertFalse(range.contains(35));
    }

    @Test
    void parsesUnboundedIntegerRangeFromString() {
        Type type = new TypeToken<RVP_Range<Integer>>() {}.getType();
        RVP_Range<Integer> range = gson.fromJson("\"[[inf,10],[20,inf]]\"", type);

        assertTrue(range.contains(Integer.MIN_VALUE));
        assertFalse(range.contains(15));
        assertTrue(range.contains(Integer.MAX_VALUE));
        assertEquals("[[inf,10],[20,inf]]", range.toString());
    }

    @Test
    void parsesFloatRangeFromJsonArray() {
        Type type = new TypeToken<RVP_Range<Float>>() {}.getType();
        RVP_Range<Float> range = gson.fromJson("[[null,1.5],[2.5,null]]", type);

        assertTrue(range.contains(-100f));
        assertFalse(range.contains(2f));
        assertTrue(range.contains(100f));
    }

    @Test
    void rejectsEmptyReversedAndIllegalRanges() {
        Type floatType = new TypeToken<RVP_Range<Float>>() {}.getType();

        assertThrows(JsonParseException.class, () -> gson.fromJson("[]", floatType));
        assertThrows(JsonParseException.class, () -> gson.fromJson("[[2.0,1.0]]", floatType));
        assertThrows(JsonParseException.class, () -> gson.fromJson("\"[[0,NaN]]\"", floatType));
        assertThrows(JsonParseException.class, () -> new RVP_Range<>(List.of()));
    }

    @Test
    void deserializesRangeAsObjectMapKey() {
        Type type = new TypeToken<Map<RVP_Range<Float>, String>>() {}.getType();
        Map<RVP_Range<Float>, String> map = gson.fromJson(
                "{\"[[0.0,100.0],[200.0,inf]]\":\"track\"}",
                type
        );

        RVP_Range<Float> key = map.keySet().iterator().next();
        assertEquals("track", map.get(key));
        assertTrue(key.contains(50f));
        assertFalse(key.contains(150f));
        assertTrue(key.contains(250f));
    }

    @Test
    void gsonRoundTripIsStable() {
        Type type = new TypeToken<RVP_Range<Float>>() {}.getType();
        RVP_Range<Float> original = RVP_Range.of(
                RVP_Range.interval(null, 10f),
                RVP_Range.interval(20f, null)
        );

        String json = gson.toJson(original, type);
        RVP_Range<Float> decoded = gson.fromJson(json, type);

        assertEquals("\"[[inf,10.0],[20.0,inf]]\"", json);
        assertEquals(original, decoded);
        assertEquals(original.hashCode(), decoded.hashCode());
    }
}
