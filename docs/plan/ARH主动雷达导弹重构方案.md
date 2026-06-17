# ARH 主动雷达导弹重构方案

## 改动说明

现行 ARH 改为走本体的主动雷达逻辑——在 `RVP_MissileEntity` 中直接嵌入本体 `MissileEntity` 的那套代码（实时目标追踪、载机雷达续标、距离触发主动雷达开机、60 tick 丢锁自毁）。不再走 RVP 的 stage 系统。

IR、SARH、ARM 等其他导弹继续走原有 stage 系统，不受影响。

## 使用流程

```
切换到 ARH 导弹（AIM-120 / PL-12 / PL-12B）
按 R → 锁定目标，同时自动开启导引头
    或 按 ~ 开导引头 → 等约 1 秒自动锁定
发射

发射后：
1. tick 0-19 加速，不追踪
2. tick ≥ 20 后：
   - targetEntity 存在 → 每 tick 从实体更新目标位置（目标怎么机动导弹就怎么转）
   - targetEntity 丢失 → 从载机雷达续标
     → 雷达还照着目标 → 恢复 targetEntity，继续追踪
     → 雷达丢锁 → clearTarget
3. 距目标 ≤ activeRadarActivationRange（JSON 配置，默认 512 格）
   → 弹载雷达开机
   → 之后自动截获，fire-and-forget，不需要载机雷达
4. 主动雷达丢锁 → 60 tick（3 秒）倒计时
   → 3 秒内目标回到弹载雷达范围 → 复锁
   → 3 秒后自毁
5. 全程无目标 → 走 IOG coast 回退（朝 lastKnownPos 滑行）
```

## 实现修改

### 文件修改清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | `RVP_BaseBullet.java` | 新增 `activeRadarOn`、`activeRadarCatch`、`activeRadarLostTargetTick`、`activeRadarActivationRange` 字段 |
| 2 | `RVP_GuidanceSourceParamsData.java` | 新增 `active_radar_activation_range` JSON 参数 |
| 3 | `RVP_MissileEntity.java` | `tickGuidance()` 对 ARH 导弹走嵌入式本体逻辑（`tickArhGuidance()`），非 ARH 走原 stage 系统 |
| 4 | `aim_120.json`、`pl_12.json`、`pl_12_2.json` | 改为单 stage + ARH source（带 `active_radar_activation_range`）+ IOG fallback |

### JSON 新格式

```json
{
  "guidance_data": {
    "stages": [{
      "name": "arh",
      "activation": { "start_tick": 0 },
      "seeker": { "fov": 60, "range": 900, "scan_interval_tick": 2 },
      "sources": [
        { "type": "ARH", "priority": 100,
          "params": { "active_radar_activation_range": 512 } },
        { "type": "IOG", "priority": 10,
          "params": { "use_last_guidance": true } }
      ]
    }]
  }
}
```

### RVP_MissileEntity.tickGuidance() 流程

```
tickGuidance()
  ├→ isArhMissile() == true → tickArhGuidance()
  │     ├ activeRadarOn ?
  │     │   ├ true → 弹载雷达扫描 + 截获目标
  │     │   └ false → 载机雷达续标（check detectedEntities）
  │     ├ tickCount ≥ 20 且 targetEntity 存活
  │     │   ├ targetPos = entity.position()  ← 实时追踪
  │     │   └ 距离 ≤ activationRange → activeRadarOn = true
  │     ├ activeRadarOn 且 targetEntity == null
  │     │   ├ lostTargetTick++ → ≥ 60 → life = 0 → 自毁
  │     │   └ return
  │     ├ 无目标也无 targetPos → super.tickGuidance() → IOG coast
  │     └ return
  └→ 非 ARH → super.tickGuidance() → 原 stage 系统
```

### 与 IR/SARH/ARM 的关系

| 制导方式 | 走哪条路 | 说明 |
|---------|---------|------|
| ARH（AIM-120/PL-12） | `tickArhGuidance()` | 嵌入本体逻辑 |
| IR（PL-10/PL-8） | `super.tickGuidance()` → stage 系统 | 无变化 |
| SARH | `super.tickGuidance()` → stage 系统 | 无变化 |
| ARM（KH-58/YJ-91） | `super.tickGuidance()` → stage 系统 | 无变化 |

