注：表格中的字段名称为java类中使用的驼峰命名法，在json中需使用全小写+下划线连接的格式

# RVP_TargetingPodData

目标指示吊舱数据模型，JSON键 `targeting_pod_data`，适用于 `type: "rvp:targeting_pod"` 武器

目标指示吊舱是一种不发射弹体的特殊武器，按下发射键时执行一次标记扫描。标记分为实体标记和方块标记两大类，被标记的实体和方块会在 RVP 战术地图和炮兵地图上点亮显示，方块标记同时写入 GPS 目标点供 GPS 制导武器使用。

| RVP_TargetingPodData字段 | 解释 | 类型 | 默认值 |
| ------------------------ | ---- | ---- | ------ |
| mode | 标记模式。`"entity"`=仅实体标记，`"block"`=仅方块标记，`"both"`=同时执行实体和方块标记 | String | "entity" |
| spotRange | 实体标记扫描距离（格），锥形扫描的最大作用距离 | float | 200 |
| spotAngle | 实体标记锥形半角（度）。瞄准方向为中心，水平角和垂直角均小于此值的实体才会被标记 | float | 15 |
| markDuration | 标记持续时间（tick），被标记的实体/方块在此时间后自动从地图上消失。20tick=1秒 | int | 600 |
| targetFilter | 实体标记的目标类型过滤，字符串列表。可选值：`"vehicle"`=载具（AbstractVehicle），`"player"`=玩家，`"living"`=生物（LivingEntity）。仅列表中指定类型的实体会被标记 | List\<String\> | ["vehicle"] |
| blockRange | 方块标记射线最大距离（格），射线检测到此距离内的方块即为标记点。为null时使用 `laser_data.range` | Float | null |
| writeGpsTarget | 方块标记是否同时写入 GPS 目标点，使 GPS 制导武器可瞄准该坐标。多次标记会覆盖上一个 GPS 目标点 | boolean | true |
| teamShare | 标记是否共享给同队玩家。true=同队玩家都能在地图上看到标记，false=仅标记者可见 | boolean | true |

## 标记模式说明

### 实体标记（mode含"entity"）

按下发射键时，以武器站瞄准方向为中心轴，执行锥形扫描：

1. 获取瞄准方向向量
2. 在 `spotRange` 范围内获取所有 `targetFilter` 指定类型的实体
3. 对每个实体执行 IFF 判断（友军跳过）
4. 视线检查（ClipContext，方块遮挡则跳过）
5. 锥形角度检查：实体相对瞄准方向的水平偏角和垂直偏角均需小于 `spotAngle`
6. 通过检查的实体加入标记列表，持续 `markDuration` tick
7. 标记列表通过 `S2CTacticalRevealSnapshot.markedRevealIds` 同步到客户端
8. 战术地图/炮兵地图自动点亮被标记的实体

### 方块标记（mode含"block"）

按下发射键时，沿瞄准方向执行射线检测：

1. 从武器站位置沿瞄准方向发射射线，最大距离 `blockRange`
2. 命中方块 → 记录坐标为方块标记点
3. 若 `writeGpsTarget=true`，同时通过 `GPSTargetManager` 写入 GPS 目标点
4. 方块标记通过 `S2CMarkedBlockSync` 同步到客户端
5. 战术地图/炮兵地图以菱形标记显示方块位置

### 同时标记（mode="both"）

实体标记和方块标记在同一次发射中独立执行，互不影响。

## 与其他系统的联动

| 联动系统 | 说明 |
| -------- | ---- |
| 战术地图 | 被标记的实体自动通过 `RVP_ClientTacticalRevealState.isVisible()` 在地图上点亮，无需修改地图代码 |
| 炮兵地图 | 同战术地图，被标记实体点亮显示 |
| GPS制导 | `writeGpsTarget=true` 时方块标记直接写入 `GPSTargetManager`，GPS 导弹/炸弹可瞄准该坐标 |
| IFF系统 | 实体标记使用 RVP 现有的 IFF 6步优先级判断，友军不会被标记 |
| 激光制导 | 方块标记写入的 GPS 目标点与 LH/SALH 激光照射点独立，不互通 |

## JSON配置示例

### 反载具实体标记吊舱

```json
{
  "type": "rvp:targeting_pod",
  "name": "Targeting Pod",
  "shoot_interval": 20,
  "max_capacity": -1,
  "reload": { "time": 0, "ammo": "ywzj_vehicle:ammo_creative" },
  "targeting_pod_data": {
    "mode": "entity",
    "spot_range": 250,
    "spot_angle": 12,
    "mark_duration": 600,
    "target_filter": ["vehicle", "player"]
  }
}
```

### 方块标记吊舱（GPS目标指示）

```json
{
  "type": "rvp:targeting_pod",
  "name": "Ground Target Pod",
  "shoot_interval": 20,
  "max_capacity": -1,
  "reload": { "time": 0, "ammo": "ywzj_vehicle:ammo_creative" },
  "targeting_pod_data": {
    "mode": "block",
    "block_range": 512,
    "write_gps_target": true,
    "mark_duration": 1200
  }
}
```

### 双模吊舱（实体+方块）

```json
{
  "type": "rvp:targeting_pod",
  "name": "Multi-Mode Pod",
  "shoot_interval": 20,
  "max_capacity": -1,
  "reload": { "time": 0, "ammo": "ywzj_vehicle:ammo_creative" },
  "targeting_pod_data": {
    "mode": "both",
    "spot_range": 300,
    "spot_angle": 15,
    "block_range": 512,
    "mark_duration": 600,
    "target_filter": ["vehicle", "player", "living"],
    "write_gps_target": true,
    "team_share": true
  }
}
```
