# 头盔瞄准具 / 格斗模式（HMD / ACM）代码调研与实现方案

## 一、起源参考：MCH-Reforged 的格斗模式

MCH-Reforged（`D:\MCHR\MCH-Reforged`）中已有的 ACM（Air Combat Maneuvering）模式即为格斗模式，代码位于：

- **核心逻辑**：[MCH_RenderRWR.java](file:///D:/MCHR/MCH-Reforged/src/main/java/mcheli/render/MCH_RenderRWR.java)
- **按键处理**：`handleRadarAcmToggleKey()`（#L2918）
- **ACM 扫描**：`refreshRadarContactsAcm()`（#L1926）
- **鼠标方向**：`computeAcmCenterAnglesFromMouse()`（#L2045）
- **屏幕渲染**：`drawAcmScanOverlay()`（#L1132）— 方形框 + 扫描线

### MCH-Reforged ACM 模式的工作流程

```
按键切换 → acmMode = true
    │
    ├── 扫描参数变化：
    │     ├── FOV = 5°（固定）
    │     ├── 最大距离 = radarMaxTargetRange × 0.5（最大雷达距离的一半）
    │     ├── 扫描周期 = normalScanTick / 4（4 倍频率）
    │     └── 中心方向 = 玩家鼠标/头部朝向（player.getLook()）
    │
    ├── 扫描过程：
    │     ├── 只扫描 acmFov（5°）锥体内的目标
    │     ├── 按角度 + 距离计算最优点 score
    │     ├── 一旦找到目标 → 自动锁定（setTrackingTarget）
    │     └── 锁定成功后 acmMode = false，退回普通 STT 模式
    │
    ├── 离轴限制：
    │     └── 中心方向不能超出雷达本身的 trackAzimuthDeg / trackElevationDeg
    │          超出则 ACM 自动退出
    │
    └── 渲染：
          ├── 雷达面板上：5° 扇形（金色半透明填充 + 轮廓 + 扫描线）
          └── 屏幕中央：方形框 + 横向扫描线（闪烁）
```

**关键代码片段**：

```java
// 从鼠标/头部方向计算 ACM 中心方位角
private static double[] computeAcmCenterAnglesFromMouse(MCH_EntityAircraft ac, EntityPlayer player) {
    Vec3 lookVec = player.getLook(partialTicks);  // 玩家头部朝向
    double rayDist = 4096.0D;
    double xPos = pX + lookVec.x * rayDist;
    double yPos = pY + lookVec.y * rayDist;
    double zPos = pZ + lookVec.z * rayDist;
    RadarProjection proj = projectPoint(ac, player, xPos, yPos, zPos);
    return new double[]{proj.bearingDeg, proj.elevationDeg};
}

// ACM 扫描函数
private static void refreshRadarContactsAcm(...) {
    // 检查是否已在本 tick slot 扫描过（去重）
    // 获取所有雷达目标
    for (MCH_EntityInfo info : getServerLoadedEntityStatic()) {
        // 过滤：trackable、有效、非友军、非诱饵
        RadarProjection proj = projectContact(ac, player, info);
        // 检查是否在 ACM 锥体（5°）内
        double relBearing = wrapDeg180(proj.bearingDeg - acmCenterBearing);
        double relElevation = proj.elevationDeg - acmCenterElevation;
        if (Math.abs(relBearing) > acmHalf || Math.abs(relElevation) > acmHalf) continue;
        // 计算 score = 角度偏移 + 距离*0.001
        // 取 score 最小的 = 离准心最近的目标
    }
    if (bestId > 0) {
        trackState.selectedTargetId = bestId;
        trackState.trackingTargetId = bestId;  // 自动锁定
        trackState.acmMode = false;  // 退出 ACM 模式，进入 STT 跟踪
    }
}
```

---

## 二、实现方案

### 2.1 整体设计

将头盔瞄准具设计为**新的雷达模式**，复用 `RadarUnit` 体系，不新增 Overlay：

```
按键切换 → hmdMode = true（切换回普通模式 = false）
    │
    ├── 扫描参数变化（在 WeaponUnit / RadarUnit 层面）：
    │     ├── FOV = 5°（固定，由鼠标/头部方向决定中心）
    │     ├── 最大扫描距离 = radarMaxDistance × 0.5
    │     ├── 扫描周期 = normalScanTick / 4（更快的刷新率）
    │     └── 扫描方向 = 玩家头部朝向（Camera.getLookVector()）
    │
    ├── 限制：
    │     ├── 头瞄离轴不能超出雷达本身 scanAngle 范围
    │     └── 超出范围自动退出 HMD 模式
    │
    ├── 目标捕获：
    │     ├── 扫描到目标后自动锁定（调用 WeaponUnit.lockTarget()）
    │     ├── 锁定后回退至正常雷达模式
    │     └── 已有预选目标时跳过头瞄直接退出
    │
    └── 屏幕渲染：
          ├── 屏幕中央显示 5° FOV 方形框（闪烁扫描线）
          └── （复用 RVP_ArmOverlay 的渲染方式）
```

### 2.2 需要修改的代码

| 组件 | 修改内容 | 参考来源 |
|:--|:--|:--|
| **WeaponUnit** 或新增 **HMD_RadarMode** | 新增 `hmdMode` 状态 + `acmCenterBearing` / `acmCenterElevation` | MCH_RadarTrackState |
| **RadarUnit** | `getScanAngle()` `getMaxScanDistance()` 根据 hmdMode 返回不同值 | MCH_RadarDisplayFrame |
| **WeaponUnit 的 switchTarget / lockTarget 流程** | 新增 `lockTargetInHmd()` 自动锁定 | MCH_refreshRadarContactsAcm |
| **RVP_ClientHmdState**（新增） | 客户端 HMD 状态：方向角、锁定量、方形框渲染 | MCH_RenderRWR.drawAcmScanOverlay |
| **Overlay 注册** | 新增 `RVP_HmdOverlay` 屏幕方形框渲染 | MCH_RenderRWR 的 ACM overlay |

### 2.3 与现有系统的关系

```
现有系统：
    RadarUnit (普通扫描模式)
        ├── scanAngle = 配置值（如 120°）
        ├── scanDistance = 配置值（如 2000m）
        └── scanTick = 配置值（如 20 tick）

新系统（按键切换后）：
    RadarUnit (HMD 模式)
        ├── scanAngle = 5°（固定锥体，跟随鼠标/头部）
        ├── scanDistance = maxDistance × 0.5
        ├── scanTick = normalTick / 4（更高刷新率）
        └── 自动锁定 → 退回普通模式
```

### 2.4 渲染方案

在屏幕上绘制一个**闪烁白色方框**，代表 5° FOV 范围（MCH-Reforged 风格）：

```java
private void renderHmdSquare(GuiGraphics guiGraphics, float partialTick) {
    int cx = screenWidth / 2;
    int cy = screenHeight / 2;
    // 计算 5° FOV 对应的屏幕像素大小
    double acmRadius = baseRadius * tan(radians(2.5°)) / tan(radians(cameraFov / 2));
    
    // 白色方框（闪烁：每 3 tick 交替透明度）
    float alpha = (ticks / 3) % 2 == 0 ? 1.0F : 0.45F;
    
    // 横向扫描线（从左到右）
    double sweepPhase = ((ticks % 6) + 0.5D) / 6.0D;
    double sx = cx - acmRadius + acmRadius * 2.0 * sweepPhase;
}
```

### 2.5 功能备忘

- [ ] 新增按键绑定 `key_hmd_toggle`（切换 HMD 模式）
- [ ] `RVP_ClientHmdState` — 客户端 HMD 状态管理（类似 RVP_ClientArmState）
- [ ] `RVP_HmdOverlay` — 屏幕方框渲染
- [ ] `WeaponUnit` HMD 模式扩展：扫描参数 + 自动锁定
- [ ] 离轴范围限制：不能超出雷达物理扫描范围
