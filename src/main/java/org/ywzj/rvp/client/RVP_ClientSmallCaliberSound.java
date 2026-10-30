package org.ywzj.rvp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.SampledFloat;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.S2CSmallCaliberSound;

import java.util.Locale;

/** 客户端小口径弹体声音播放器；直接读取已有音频，不改写资源文件。 */
public final class RVP_ClientSmallCaliberSound {

    /** 声音文件的原始音量与音调倍率。 */
    private static final SampledFloat UNIT_SAMPLE = random -> 1.0F;
    /** 划空声播放音量；近身检测半径由服务端控制。 */
    private static final float INCOMING_VOLUME = 1.0F;
    /** 方块命中声音量；原版线性衰减下覆盖约 32 格。 */
    private static final float IMPACT_VOLUME = 2.0F;
    /** 已有的六个划空声音频和内存事件描述。 */
    private static final SoundClip[] INCOMING = createClips(S2CSmallCaliberSound.Kind.INCOMING);
    /** 已有的十一个方块命中音频和内存事件描述。 */
    private static final SoundClip[] BLOCK_IMPACT = createClips(S2CSmallCaliberSound.Kind.BLOCK_IMPACT);

    private RVP_ClientSmallCaliberSound() {
    }

    /** 消费服务端确认的声音；跨维度迟到或音频缺失时不播放。 */
    public static void accept(S2CSmallCaliberSound message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(message.dimension())) {
            return;
        }
        // 调用本类样本表，核对被选中的已有音频是否仍在客户端资源中。
        SoundClip clip = clip(message.kind(), message.variant());
        if (minecraft.getResourceManager().getResource(clip.sample().getPath()).isEmpty()) {
            return;
        }
        // 调用本类定位声音工厂，再交给原版声音引擎处理音量、空间定位和资源缓存。
        minecraft.getSoundManager().play(createSound(message.kind(), message.variant(), message.position()));
    }

    /** 构造指定用途及样本的定位声音，供客户端播放和资源测试复用。 */
    static SoundInstance createSound(S2CSmallCaliberSound.Kind kind, int variant, Vec3 position) {
        SoundClip clip = clip(kind, variant);
        float volume = kind == S2CSmallCaliberSound.Kind.INCOMING ? INCOMING_VOLUME : IMPACT_VOLUME;
        return new ProjectileSound(clip, volume, position);
    }

    /** 按声音用途取样本，消息构造器已经验证编号范围。 */
    private static SoundClip clip(S2CSmallCaliberSound.Kind kind, int variant) {
        return (kind == S2CSmallCaliberSound.Kind.INCOMING ? INCOMING : BLOCK_IMPACT)[variant];
    }

    /** 在内存中建立样本描述，名称与用户已有音频文件逐一对应。 */
    private static SoundClip[] createClips(S2CSmallCaliberSound.Kind kind) {
        SoundClip[] clips = new SoundClip[kind.variantCount()];
        boolean incoming = kind == S2CSmallCaliberSound.Kind.INCOMING;
        ResourceLocation eventId = ResourceLocation.fromNamespaceAndPath(RVP_MOD.MOD_ID,
                incoming ? "small_caliber_incoming" : "small_caliber_block_impact");
        for (int i = 0; i < clips.length; i++) {
            String samplePath = incoming
                    ? String.format(Locale.ROOT, "default/bullet/bullet_incoming/bullet_incoming-%03d", i + 1)
                    : String.format(Locale.ROOT, "default/bullet/bullet_dirt/bullet_dirt_%02d", i + 1);
            Sound sample = new Sound(RVP_MOD.MOD_ID + ":" + samplePath,
                    UNIT_SAMPLE, UNIT_SAMPLE, 1, Sound.Type.FILE, false, false, 16);
            WeighedSoundEvents event = new WeighedSoundEvents(eventId, null);
            event.addSound(sample);
            clips[i] = new SoundClip(eventId, sample, event);
        }
        return clips;
    }

    /** 单个声音样本及原版声音引擎使用的内存事件描述。 */
    private record SoundClip(
            /** 内部声音事件 ID。 */ ResourceLocation eventId,
            /** 指向用户已有 .ogg 的声音资源。 */ Sound sample,
            /** 仅包含该样本的内存事件。 */ WeighedSoundEvents event) {
    }

    /** 自带样本解析的普通定位声，无需修改任何 sounds.json。 */
    private static final class ProjectileSound extends SimpleSoundInstance {

        /** 本次播放选中的音频样本。 */
        private final SoundClip clip;

        private ProjectileSound(SoundClip clip, float volume, Vec3 position) {
            super(clip.eventId(), SoundSource.PLAYERS, volume, 1.0F,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR,
                    position.x, position.y, position.z, false);
            this.clip = clip;
            this.sound = clip.sample();
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            this.sound = clip.sample();
            return clip.event();
        }
    }
}
