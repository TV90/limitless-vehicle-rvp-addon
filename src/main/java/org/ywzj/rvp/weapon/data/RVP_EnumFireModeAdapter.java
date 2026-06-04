package org.ywzj.rvp.weapon.data;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/** Gson 读写 {@link RVP_EnumFireMode}（仅枚举名，大小写不敏感）。 */
public final class RVP_EnumFireModeAdapter extends TypeAdapter<RVP_EnumFireMode> {

    @Override
    public void write(JsonWriter out, RVP_EnumFireMode value) throws IOException {
        if (value == null) {
            out.nullValue();
        } else {
            out.value(value.name());
        }
    }

    @Override
    public RVP_EnumFireMode read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return RVP_EnumFireMode.FULL_AUTO;
        }
        return RVP_EnumFireMode.fromString(in.nextString());
    }
}
