# -*- coding: utf-8 -*-
"""生成 data/rvp/vehicles/m1agds.json —— 以 m1a2sep.json 为蓝本改（用户批注 #14）。
新文件，不涉及覆写已跟踪文件。"""
import json, collections, os

BASE = 'd:/ywzj/ywzj/ywzj_rvp/limitless_vehicle/rvp/data/rvp/vehicles/m1a2sep.json'
OUT  = 'd:/ywzj/ywzj/ywzj_rvp/limitless_vehicle/rvp/data/rvp/vehicles/m1agds.json'

src = json.load(open(BASE, encoding='utf-8'), object_pairs_hook=collections.OrderedDict)
byid = {p['id']: p for p in src['parts']}

def generic(pid):
    """直接沿用蓝本里的 generic 骨部件（骨名一致）"""
    return json.loads(json.dumps(byid[pid], ensure_ascii=False), object_pairs_hook=collections.OrderedDict)

# ---------------- parts ----------------
turret = collections.OrderedDict([
    ("id", "turret"), ("name", "turret"), ("type", "ywzj_vehicle:weapon"),
    ("structure_bone", "turret"),
    ("seat_offset", [-0.5, 1.5, 0.375]),
    ("rot_info", collections.OrderedDict([("x_rot_speed", 4), ("y_rot_speed", 4), ("x_rot_max", 10), ("x_rot_min", -50)])),
    ("operator_view_offset", [0, 2.5, 0]),
    ("optical_sight_offset", [0.6918625, 1.40706875, 0.86111875]),
    ("with_stabilizer", True),
    ("with_thermal_imager", True),
    ("fire_control_sensor_type", "rf"),
    ("rvp_fire_control_mode", "rvp_rf"),
    ("rvp_rf_off_axis_deg", 10),
    ("optical_sight_type", "crt_ui"),
    ("zoom_max", 8),
    ("crosshair_style", "circle"),
    ("sub_part_unit_ids", ["scan_radar", "lock_radar"]),
    ("weapons", [
        "rvp:m1agds_kda35",
        collections.OrderedDict([("id", "rvp:m1agds_mim146"), ("part_unit_id", "missile")]),
    ]),
])

missile = collections.OrderedDict([
    ("id", "missile"), ("name", "missile"), ("type", "ywzj_vehicle:weapon"),
    ("structure_bone", "missile"),
    ("fire_control_sensor_type", "rf"),
    ("ammo_capacity", 8),
    ("is_seat", False),
    ("base", "turret"),
    ("parent_weapon_unit_aim", True),
    ("crosshair_style", "none"),
    ("rot_info", collections.OrderedDict([("x_rot_speed", 3), ("y_rot_speed", 0), ("x_rot_max", 0), ("x_rot_min", -50)])),
])

def radar(pid, role, rot, period, hms=False):
    d = collections.OrderedDict([
        ("id", pid), ("name", pid), ("type", "ywzj_vehicle:radar"),
        ("structure_bone", pid),
        ("radar_role", role),
        ("nctr_mode", "MODERN"),
        ("is_seat", False),
        ("base", "turret"),
        ("rot_info", collections.OrderedDict(rot)),
        ("scan_sector_angle", 180),
        ("radar_type", "AGDS"),
        ("scan_animation_mode", "phase"),
        ("scan_period_tick", period),
        ("scan_min_height", 25),
        ("scan_max_height", 10000),
    ])
    if hms:
        d["enable_hms"] = False
    d["max_scan_distance"] = 1500
    return d

lock_radar = radar("lock_radar", "fire_control",
                   [("x_rot_speed", 0), ("y_rot_speed", 0), ("x_rot_max", 85), ("x_rot_min", 0),
                    ("y_rot_max", 45), ("y_rot_min", -45)], 20)
scan_radar = radar("scan_radar", "search",
                   [("x_rot_speed", 0), ("y_rot_speed", 0), ("x_rot_max", 90), ("x_rot_min", 0),
                    ("y_rot_max", 360), ("y_rot_min", 0)], 60, hms=True)

def smoke(pid):
    return collections.OrderedDict([
        ("id", pid), ("name", pid), ("type", "ywzj_vehicle:weapon"),
        ("structure_bone", pid + "_barrel"),
        ("is_seat", False),
        ("base", "turret"),
        ("rot_info", collections.OrderedDict([("x_rot_speed", 0), ("y_rot_speed", 0)])),
    ])

parts = [
    turret, missile, lock_radar, scan_radar,
    smoke("turret_smoke_grenade_l"), smoke("turret_smoke_grenade_r"),
    generic("bone"), generic("vehicle_body"), generic("track"), generic("Engine"),
    generic("Upper_front"), generic("Lower_front"), generic("Periscope"),
    generic("turret_r"), generic("turret_l"), generic("turret_back"), generic("turret_top"),
]

# ---------------- 命中表 ----------------
ROWS = [
    ("vehicle_body",                   1.0, "Hull Side",      "车体侧面"),
    ("track",                          0.5, "Track",          "履带"),
    ("Engine",                         1.3, "Engine Bay",     "发动机舱"),
    ("Upper_front",                    0.6, "Upper Glacis",   "首上"),
    ("Lower_front",                    0.5, "Lower Glacis",   "首下"),
    ("Periscope",                      0.8, "Periscope",      "潜望镜"),
    ("turret",                         0.4, "Turret",         "炮塔"),
    ("turret_r",                       1.0, "Turret Right",   "炮塔右侧"),
    ("turret_back",                    1.3, "Turret Bustle",  "炮塔尾舱"),
    ("turret_top",                     1.0, "Turret Top",     "炮塔顶部"),
    ("turret_l",                       1.0, "Turret Left",    "炮塔左侧"),
    ("turret_barrel",                  0.4, "Main Gun",       "主炮"),
    ("missile",                        1.0, "Missile Launcher", "导弹发射架"),
    ("missile_barrel",                 1.3, "Missile Tube",   "导弹发射管"),
    ("lock_radar",                     0.4, "Tracking Radar", "跟踪雷达"),
    ("scan_radar",                     0.4, "Search Radar",   "搜索雷达"),
    ("turret_smoke_grenade_l_barrel",  0.4, "Smoke Launcher", "烟幕弹发射器"),
    ("turret_smoke_grenade_r_barrel",  0.4, "Smoke Launcher", "烟幕弹发射器"),
]
factor = collections.OrderedDict((r[0], r[1]) for r in ROWS)
name_en = collections.OrderedDict((r[0], r[2]) for r in ROWS)
name_cn = collections.OrderedDict((r[0], r[3]) for r in ROWS)

# ---------------- 骨模块 ----------------
bone_modules = collections.OrderedDict([
    ("Engine", collections.OrderedDict([
        ("modules", ["engine"]),
        ("engine", collections.OrderedDict([("threshold_light", 550), ("threshold_heavy", 800)])),
    ])),
    ("lock_radar", collections.OrderedDict([("modules", ["radar"]), ("min_damage", 20.0)])),
    ("scan_radar", collections.OrderedDict([("modules", ["radar"]), ("min_damage", 20.0)])),
    ("turret_barrel", collections.OrderedDict([("modules", ["barrel"]), ("smoke", False)])),
    ("missile_barrel", collections.OrderedDict([("modules", ["barrel"])])),
])

# ---------------- 烟幕 ----------------
smoke_cm = collections.OrderedDict(src["countermeasure"]["smoke"])  # 沿用蓝本数值
smoke_cm["launcher_parts"] = ["turret_smoke_grenade_l", "turret_smoke_grenade_r"]
countermeasure = collections.OrderedDict([("smoke", smoke_cm)])

# ---------------- 顶层 ----------------
out = collections.OrderedDict([
    ("defense_stats", src["defense_stats"]),
    ("armor_min_damage", 15),
    ("type", "ywzj_vehicle:tracked_vehicle"),
    ("ui_preset", "ps1sm"),
    ("show_skeleton", False),
    ("attributes", src["attributes"]),
    ("max_health", 1200),
    ("view_info", src["view_info"]),
    ("energy_info", src["energy_info"]),
    ("physics_info", src["physics_info"]),
    ("hide_passenger", True),
    ("structure_model", "rvp:vehicle/m1agds"),
    ("core_distance_scale_multiplier", 0.0),
    ("hitbox_damage_factor_default", 1.0),
    ("hitbox_damage_factor", factor),
    ("hitbox_display_name", name_en),
    ("hitbox_display_name_CN", name_cn),
    ("bone_modules", bone_modules),
    ("countermeasure", countermeasure),
    ("parts", parts),
])

txt = json.dumps(out, ensure_ascii=False, indent=2) + "\n"
open(OUT, 'w', encoding='utf-8', newline='\n').write(txt)
print('已写入', OUT)
print('  parts=%d  命中表=%d 行  顶层键=%d' % (len(parts), len(ROWS), len(out)))
print('  size=%d bytes  crlf=%d' % (os.path.getsize(OUT), open(OUT, 'rb').read().count(b'\r\n')))
