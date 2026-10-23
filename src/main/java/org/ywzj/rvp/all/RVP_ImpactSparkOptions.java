package org.ywzj.rvp.all;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

/**
 * 命中火星的粒子数据（{@code rvp:impact_spark}）——弹药命中载具/方块后在命中点溅出的扩散火星。
 *
 * <p>方向语义：{@code dirX/Y/Z} 为<b>扩散基准方向</b>，即「命中方向的反方向」（弹体来袭方向的相反向，
 * 视觉上等于命中点朝弹源侧/反弹侧溅出）。速度 {@code speed} 由调用方按命中弹速打折后给出
 * （刻意不是原速）；每颗粒子在此基础上做锥形随机扰动，扰动幅度为 {@code spread}。</p>
 *
 * <p>模式照抄 {@link RVP_TracerGhostOptions}（Codec + Deserializer 双通道）。</p>
 */
public record RVP_ImpactSparkOptions(
        float r,
        float g,
        float b,
        float size,
        double dirX,
        double dirY,
        double dirZ,
        double speed,
        float spread,
        int lifetime
) implements ParticleOptions {

    public static final Codec<RVP_ImpactSparkOptions> CODEC = RecordCodecBuilder.create(builder ->
            builder.group(
                    Codec.FLOAT.fieldOf("r").forGetter(RVP_ImpactSparkOptions::r),
                    Codec.FLOAT.fieldOf("g").forGetter(RVP_ImpactSparkOptions::g),
                    Codec.FLOAT.fieldOf("b").forGetter(RVP_ImpactSparkOptions::b),
                    Codec.FLOAT.fieldOf("size").forGetter(RVP_ImpactSparkOptions::size),
                    Codec.DOUBLE.fieldOf("dir_x").forGetter(RVP_ImpactSparkOptions::dirX),
                    Codec.DOUBLE.fieldOf("dir_y").forGetter(RVP_ImpactSparkOptions::dirY),
                    Codec.DOUBLE.fieldOf("dir_z").forGetter(RVP_ImpactSparkOptions::dirZ),
                    Codec.DOUBLE.fieldOf("speed").forGetter(RVP_ImpactSparkOptions::speed),
                    Codec.FLOAT.fieldOf("spread").forGetter(RVP_ImpactSparkOptions::spread),
                    Codec.INT.fieldOf("lifetime").forGetter(RVP_ImpactSparkOptions::lifetime)
            ).apply(builder, RVP_ImpactSparkOptions::new));

    @SuppressWarnings("deprecation")
    public static final Deserializer<RVP_ImpactSparkOptions> DESERIALIZER = new Deserializer<>() {
        @Override
        public RVP_ImpactSparkOptions fromCommand(ParticleType<RVP_ImpactSparkOptions> type, StringReader reader)
                throws CommandSyntaxException {
            reader.expect(' ');
            float r = reader.readFloat();
            reader.expect(' ');
            float g = reader.readFloat();
            reader.expect(' ');
            float b = reader.readFloat();
            reader.expect(' ');
            float size = reader.readFloat();
            reader.expect(' ');
            double dirX = reader.readDouble();
            reader.expect(' ');
            double dirY = reader.readDouble();
            reader.expect(' ');
            double dirZ = reader.readDouble();
            reader.expect(' ');
            double speed = reader.readDouble();
            reader.expect(' ');
            float spread = reader.readFloat();
            reader.expect(' ');
            int lifetime = reader.readInt();
            return new RVP_ImpactSparkOptions(r, g, b, size, dirX, dirY, dirZ, speed, spread, lifetime);
        }

        @Override
        public RVP_ImpactSparkOptions fromNetwork(ParticleType<RVP_ImpactSparkOptions> type, FriendlyByteBuf buffer) {
            return new RVP_ImpactSparkOptions(buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readFloat(), buffer.readInt());
        }
    };

    /** 归一化基准方向；零向量按竖直向上兜底。尺寸/散布/寿命同时做安全钳制。 */
    public RVP_ImpactSparkOptions {
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
        size = Math.max(0.02F, Math.min(size, 0.6F));
        speed = Math.max(0.0D, Math.min(speed, 4.0D));
        spread = Math.max(0.0F, Math.min(spread, 1.5F));
        lifetime = Math.max(2, Math.min(lifetime, 60));
    }

    @Override
    public ParticleType<?> getType() {
        return RVP_Particles.IMPACT_SPARK.get();
    }

    @Override
    public void writeToNetwork(FriendlyByteBuf buffer) {
        buffer.writeFloat(r);
        buffer.writeFloat(g);
        buffer.writeFloat(b);
        buffer.writeFloat(size);
        buffer.writeDouble(dirX);
        buffer.writeDouble(dirY);
        buffer.writeDouble(dirZ);
        buffer.writeDouble(speed);
        buffer.writeFloat(spread);
        buffer.writeInt(lifetime);
    }

    @Override
    public String writeToString() {
        return String.format(Locale.ROOT, "%s %f %f %f %f %f %f %f %f %f %d",
                ForgeRegistries.PARTICLE_TYPES.getKey(this.getType()),
                r, g, b, size, dirX, dirY, dirZ, speed, spread, lifetime);
    }
}
