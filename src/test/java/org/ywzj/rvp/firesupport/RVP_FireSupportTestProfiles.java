package org.ywzj.rvp.firesupport;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.firesupport.config.RVP_FireSupportProfileParser;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportResolvedWeapon;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

final class RVP_FireSupportTestProfiles {
    static final ResourceLocation PROFILE_ID = ResourceLocation.fromNamespaceAndPath("rvp", "test");
    static final ResourceLocation WEAPON_ID = ResourceLocation.fromNamespaceAndPath("rvp", "test_round");
    static final ResourceLocation SECOND_WEAPON_ID = ResourceLocation.fromNamespaceAndPath("rvp", "test_rocket");

    private RVP_FireSupportTestProfiles() {}

    static JsonElement validJson() {
        return JsonParser.parseString("""
                {
                  "schema_version":3,
                  "display":{"translation_key":"fire_support_profile.rvp.test"},
                  "item":{"translation_key":"item.ywzj_rvp.fire_support_terminal.test"},
                  "holder_policy":{"allowed_hands":["main","off"]},
                  "call_stage":{"base_duration_ticks":800,"cancel_on_player_death":true,"cancel_on_terminal_lost":true,"cancel_on_disconnect":true,"consume_terminal_on_completion":false},
                  "strike_stage":{"cease_fire_delay_ticks":80},
                  "limits":{"min_target_distance_m":16,"max_target_distance_m":2048,"max_rounds_per_mission":96,
                    "max_active_missions_per_player":1,"max_active_missions_global":16,"request_cooldown_ticks":100,
                    "max_mission_duration_ticks":2400,"max_loaded_chunks_per_mission":8,"max_parameter_count":16},
                  "munitions":[{"id":"he","translation_key":"fire_support.munition.rvp.he",
                    "rounds_per_unit":6,"registration_phase_enabled":true,"weapons":[
                      {"weapon":"rvp:test_round","weight":2,"delivery":{"type":"rvp:vertical_projectile","data":{"spawn_height_above_impact_m":120,
                       "entry_speed_m_per_tick":4,"preload_ticks":10,"heading_jitter_deg":0}}},
                      {"weapon":"rvp:test_rocket","weight":1,"delivery":{"type":"rvp:vertical_projectile","data":{"spawn_height_above_impact_m":100,
                       "entry_speed_m_per_tick":3,"preload_ticks":10,"heading_jitter_deg":1}}}
                    ]}],
                  "fire_modes":[
                    {"id":"rapid","translation_key":"fire_support.fire_mode.rvp.rapid","call_duration_multiplier":1,
                     "dispersion_multiplier":1.5,"phases":[{"id":"main","translation_key":"phase.main","start_delay_ticks":0,
                     "rounds":{"base_multiplier":1.5,"rounding":"ceil"},"duration_ticks":60}]},
                    {"id":"effect","translation_key":"fire_support.fire_mode.rvp.effect","call_duration_multiplier":1.5,
                     "dispersion_multiplier":1,"phases":[{"id":"registration","translation_key":"phase.registration","registration_phase":true,"start_delay_ticks":0,
                     "rounds":{"random_min":2,"random_max":3},"interval_ticks":20},{"id":"main","translation_key":"phase.main",
                     "start_delay_ticks":80,"rounds":{"base_multiplier":2,"rounding":"ceil"},"duration_ticks":180}]},
                    {"id":"monitor","translation_key":"fire_support.fire_mode.rvp.monitor","call_duration_multiplier":1.5,
                     "dispersion_multiplier":1,"phases":[{"id":"registration","translation_key":"phase.registration","registration_phase":true,"start_delay_ticks":0,
                     "rounds":{"random_min":2,"random_max":3},"interval_ticks":20},{"id":"main","translation_key":"phase.main",
                     "start_delay_ticks":80,"rounds":{"base_multiplier":2,"rounding":"ceil"},"duration_ticks":600}]}
                  ],
                  "patterns":[
                    {"id":"point","translation_key":"pattern.point","type":"rvp:point","data":{"parameters":{"radius_m":
                      {"type":"double","default":18,"min":4,"max":64,"step":1,"unit":"m"}}}},
                    {"id":"line","translation_key":"pattern.line","type":"rvp:line","data":{"parameters":{"length_m":
                      {"type":"double","default":120,"min":20,"max":320,"step":5,"unit":"m"},"width_m":
                      {"type":"double","default":24,"min":4,"max":96,"step":1,"unit":"m"}}}},
                    {"id":"creeping","translation_key":"pattern.creeping","type":"rvp:creeping","data":{"parameters":{"length_m":
                      {"type":"double","default":160,"min":40,"max":480,"step":10,"unit":"m"},"width_m":
                      {"type":"double","default":32,"min":4,"max":128,"step":1,"unit":"m"},"step_m":
                      {"type":"double","default":20,"min":5,"max":80,"step":5,"unit":"m"}}}}
                  ]
                }
                """);
    }

    static RVP_FireSupportResolvedWeapon projectileWeapon() {
        return new RVP_FireSupportResolvedWeapon(RVP_EnumWeaponKind.MACHINEGUN, RVP_EnumGuidanceType.NONE,
                false, false, false, false, false, 1200, 0, false, 20.0F, 4.0F);
    }

    static RVP_FireSupportProfile parse() {
        return RVP_FireSupportProfileParser.parseAll(Map.of(PROFILE_ID, validJson()),
                id -> WEAPON_ID.equals(id) || SECOND_WEAPON_ID.equals(id) ? projectileWeapon() : null).get(PROFILE_ID);
    }
}
