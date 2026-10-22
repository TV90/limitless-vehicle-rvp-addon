package org.ywzj.rvp.all;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.registries.ForgeRegistries;
import org.ywzj.vehicle.all.AllParticleTypes;

import java.util.Locale;

/**
 * 近距命中曳光残影的粒子数据（{@code rvp:tracer_ghost}）：
 * 颜色与宽度/长度完全复刻实体渲染器（{@code VehicleProjectileRenderLogic}）的曳光箱体参数，
 * 方向为弹速单位向量；残影从命中点沿来袭方向铺满 {@code length}。
 * 模式照抄本体 {@code BulletHoleOption}（Codec + Deserializer 双通道）。
 */
public record RVP_TracerGhostOptions(
        float r,
        float g,
        float b,
        float width,
        float length,
        double dirX,
        double dirY,
        double dirZ
) implements ParticleOptions {

    public static final Codec<RVP_TracerGhostOptions> CODEC = RecordCodecBuilder.create(builder ->
            builder.group(
                    Codec.FLOAT.fieldOf("r").forGetter(RVP_TracerGhostOptions::r),
                    Codec.FLOAT.fieldOf("g").forGetter(RVP_TracerGhostOptions::g),
                    Codec.FLOAT.fieldOf("b").forGetter(RVP_TracerGhostOptions::b),
                    Codec.FLOAT.fieldOf("width").forGetter(RVP_TracerGhostOptions::width),
                    Codec.FLOAT.fieldOf("length").forGetter(RVP_TracerGhostOptions::length),
                    Codec.DOUBLE.fieldOf("dir_x").forGetter(RVP_TracerGhostOptions::dirX),
                    Codec.DOUBLE.fieldOf("dir_y").forGetter(RVP_TracerGhostOptions::dirY),
                    Codec.DOUBLE.fieldOf("dir_z").forGetter(RVP_TracerGhostOptions::dirZ)
            ).apply(builder, RVP_TracerGhostOptions::new));

    @SuppressWarnings("deprecation")
    public static final Deserializer<RVP_TracerGhostOptions> DESERIALIZER = new Deserializer<>() {
        @Override
        public RVP_TracerGhostOptions fromCommand(ParticleType<RVP_TracerGhostOptions> type, StringReader reader)
                throws CommandSyntaxException {
            reader.expect(' ');
            float r = reader.readFloat();
            reader.expect(' ');
            float g = reader.readFloat();
            reader.expect(' ');
            float b = reader.readFloat();
            reader.expect(' ');
            float width = reader.readFloat();
            reader.expect(' ');
            float length = reader.readFloat();
            reader.expect(' ');
            double dirX = reader.readDouble();
            reader.expect(' ');
            double dirY = reader.readDouble();
            reader.expect(' ');
            double dirZ = reader.readDouble();
            return new RVP_TracerGhostOptions(r, g, b, width, length, dirX, dirY, dirZ);
        }

        @Override
        public RVP_TracerGhostOptions fromNetwork(ParticleType<RVP_TracerGhostOptions> type, FriendlyByteBuf buffer) {
            return new RVP_TracerGhostOptions(buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readFloat(), buffer.readFloat(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        }
    };

    /** 单位方向向量；零向量输入按竖直向上兜底，避免归一化除零。 */
    public RVP_TracerGhostOptions {
        double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (len < 1.0E-6D) {
            dirX = 0.0D;
            dirY = 1.0D;
            dirZ = 0.0D;
        } else {
            dirX /= len;
            dirY /= len;
            dirZ /= len;
        }
        width = Math.max(0.01F, Math.min(width, 0.5F));
        length = Math.max(0.5F, Math.min(length, 8.0F));
    }

    @Override
    public ParticleType<?> getType() {
        return RVP_Particles.TRACER_GHOST.get();
    }

    @Override
    public void writeToNetwork(FriendlyByteBuf buffer) {
        buffer.writeFloat(r);
        buffer.writeFloat(g);
        buffer.writeFloat(b);
        buffer.writeFloat(width);
        buffer.writeFloat(length);
        buffer.writeDouble(dirX);
        buffer.writeDouble(dirY);
        buffer.writeDouble(dirZ);
    }

    @Override
    public String writeToString() {
        return String.format(Locale.ROOT, "%s %f %f %f %f %f %f %f %f",
                ForgeRegistries.PARTICLE_TYPES.getKey(this.getType()),
                r, g, b, width, length, dirX, dirY, dirZ);
    }
}
