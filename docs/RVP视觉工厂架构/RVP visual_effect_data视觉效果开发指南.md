# RVP `visual_effect_data` 视觉效果开发指南

> 面向 `limitless-vehicle-rvp-addon` 后续视觉效果开发者。  
> 本文以 2026-08-13 仓库中的实际实现为准，若文档与代码不一致，以当前代码和测试为准。

## 1. 文档目标

本文回答以下问题：

- 如何通过 `detonate_data.visual_effect_data[]` 新增一种爆炸视觉；
- 通用服务端、网络层和客户端视觉工厂分别负责什么；
- 哪些新增效果只需添加客户端代码，哪些情况才需要扩展公共协议；
- 如何设计类型化预设、资源重载、实例生命周期、渲染、声音、LOD 和测试；
- 如何保持专用服务器安全，并避免武器 ID 硬编码、客户端类型泄漏和不必要的 Mixin。

本文不是温压弹参数手册。温压弹全部现有字段仍以项目中的 `RVP包新增参数字段说明.md`、`RVP_ThermobaricPreset` 和 `RVP_ThermobaricPresetPatch` 为准。本文借温压弹实现说明通用扩展方法。

## 2. 一句话设计原则

`visual_effect_data` 描述“爆炸发生后应发布什么视觉事件”，服务端只发送一次小型、不可变、可确定性复演的事件；客户端根据 `effect_type` 找到类型化工厂，创建本地实例，并自行完成 Tick、渲染、声音和反馈。

不要为每个粒子同步实体或逐 Tick 网络状态，也不要用武器 ID 推断视觉类型。

## 3. 当前真实调用链

```text
武器 JSON
  detonate_data.visual_effect_data[]
          │
          ▼
RVP_VisualEffectData
          │
          ▼
RVP_BaseBullet 爆炸结算
  └─ 构造 RVP_DetonationVisualContext
          │
          ▼
RVP_VisualEffects.publishDetonation(...)
  └─ RVP_DetonationVisualResolver
       └─ 默认：RVP_DefaultDetonationVisualResolver
          │
          ▼
RVP_VisualEffectEvent（公共、不可变、无客户端类型）
          │
          ▼
RVP_NetworkVisualEventPublisher
  └─ S2CVisualEffectEvent
     └─ PacketDistributor.NEAR（同维度、broadcast_range 内）
          │
          ▼
RVP_VisualEffectEndpoint
          │
          ▼
RVP_ClientVisualEffectDispatcher
  └─ 按 effect_type 查找 RVP_ClientVisualEffectFactory
          │
          ▼
RVP_ClientVisualEffect 实例
  ├─ ClientTick END：tick()
  ├─ AFTER_WEATHER：render(...)
  ├─ isFinished()：结束判定
  └─ close()：释放资源
```

这条链路已经存在。新增普通视觉类型时，不应再复制一套网络包、端点、全局 Tick 事件或爆炸入口。

## 4. 各层职责与禁止事项

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| 武器 JSON / `RVP_VisualEffectData` | 通用开关、工厂类型、预设 ID、尺寸、密度、寿命、广播范围、反馈许可、类型化覆盖 | 伤害、方块破坏、武器 ID 分派 |
| 服务端上下文与解析器 | 使用最终爆心、最终爆炸半径、维度、种子、开始时间生成领域事件 | 加载客户端资源、生成粒子、引用 `Minecraft` |
| 网络层 | 编解码一次性领域事件并按范围广播 | 直接调用客户端渲染器、保存活动视觉状态 |
| 客户端工厂 | 校验并合并本类型预设，补偿网络延迟，决定是否创建实例 | 修改爆炸物理、信任未经校验的任意 JSON |
| 客户端实例 | 保存一次效果的确定性状态，推进 Tick，报告结束并清理资源 | 在 render 中推进模拟、修改公共注册表 |
| 渲染器 / 反馈控制器 | 提交世界几何、声音、GUI 闪光、镜头震动 | 决定服务端伤害或广播对象 |

硬性约束：

1. 不得在实体或渲染类中使用 `weaponId.getPath().equals(...)`、`contains(...)` 等方式区分效果。
2. 不得在公共、服务端或网络包中导入 `net.minecraft.client.*` 或 `org.ywzj.rvp.client.*`。
3. 默认不得新增 Mixin。当前视觉框架已经提供 JSON、工厂注册表、Forge 事件和网络端口，新增视觉通常没有使用 Mixin 的理由。
4. 不得修改 `ywzj_vehicle` 本体源码。
5. 不得加入旧 schema 别名、迁移器或 `legacy*` 分支。
6. 客户端表现不得反向改变 `explosion_data` 的伤害、半径或方块破坏。

## 5. `visual_effect_data[]` 通用 schema

单次爆炸可以配置多项视觉。每项独立解析、发布和创建。

```json
{
  "detonate_data": {
    "visual_effect_data": [
      {
        "enabled": true,
        "effect_type": "rvp:example_blast",
        "preset": "rvp:example_blast_standard",
        "scale": 1.0,
        "density": 1.0,
        "duration_ticks": -1,
        "broadcast_range": 1536.0,
        "sound": true,
        "flash": true,
        "shake": true,
        "suppress_native_explosion_effect": true,
        "experimental": {},
        "preset_data": {
          "core_color": "#FFAA44"
        }
      }
    ]
  }
}
```

| 字段 | 当前行为 |
| --- | --- |
| `enabled` | 默认 `false`。只有为 `true` 且 `effect_type` 合法时，默认解析器才接受该项。 |
| `effect_type` | 客户端工厂类型资源 ID，例如 `rvp:thermobaric`。它是视觉类型，不是武器 ID。 |
| `preset` | 客户端预设资源 ID，默认 `rvp:default`。缺失资源如何回退由具体工厂决定。 |
| `scale` | 非负有限视觉尺寸倍率，默认 `1.0`。当前通用层不设业务上限。 |
| `density` | 非负有限视觉密度上限，默认 `1.0`。具体如何影响粒子数由工厂实现。 |
| `duration_ticks` | `-1` 表示使用预设寿命；非负值直接覆盖，`0` 通常应使工厂不创建可见实例。 |
| `broadcast_range` | 同维度广播距离，默认 `1536.0` 格；只影响谁收到事件。 |
| `sound` | 是否允许本效果播放声音，默认 `true`。客户端配置可进一步关闭。 |
| `flash` | 是否允许近距离闪光，默认 `true`。 |
| `shake` | 是否允许镜头震动，默认 `true`。 |
| `suppress_native_explosion_effect` | 该项成功发布后是否屏蔽本体普通爆炸视觉，默认 `true`；不屏蔽物理爆炸。 |
| `experimental` | 当前通用数据中的实验能力对象。现有字段只有 `dynamic_particle_budget`，且当前仅温压工厂消费。新效果应忽略不认识的实验字段。 |
| `preset_data` | 与该效果预设相同 schema 的稀疏覆盖对象，由具体客户端工厂类型化解析。 |

重要边界：

- `scale`、`density`、`duration_ticks` 是公共事件字段；类型预设中不要再创造同义字段。
- `preset_data` 规范化后的 UTF-8 大小不能超过 8 KiB。超限时整项不会发布，并保留本体普通爆炸视觉。
- 对象键会在服务端递归排序，数组顺序保持不变，随后作为不可变 JSON 字符串进入事件。
- `visual_effect_data` 缺失、为空、项为 `null`、未启用或类型 ID 非法时，保持旧行为。
- 一项失败不会阻止同一数组中其他合法项发布。

## 6. 新增效果前先选择扩展级别

### 6.1 推荐路径：只新增客户端视觉类型

满足以下条件时，只需新增客户端实现并注册工厂：

- 已有事件字段足以描述爆心、基础半径、尺寸、密度、寿命和反馈许可；
- 新效果的专属参数可以全部放入 `preset` / `preset_data`；
- 不需要额外的服务端权威信息；
- 不需要不同的广播策略或物理逻辑。

这是绝大多数新爆炸视觉应采用的路径。默认解析器接受任意合法的 `effect_type`，因此不需要为每种客户端效果新增 `RVP_DetonationVisualResolver`。

### 6.2 只有确有服务端语义时才新增解析器

当效果必须根据 `RVP_DetonationVisualContext` 中的权威信息改变事件，且无法由现有公共字段或 `preset_data` 表达时，才考虑实现 `RVP_DetonationVisualResolver` 并调用：

```java
RVP_VisualEffects.registerResolver(new RVP_ExampleDetonationVisualResolver());
```

注册的扩展解析器会被插入列表首部，优先于默认解析器。实现时必须注意：

- `supports(data)` 只能选择自己真正处理的配置；
- `resolve(context, data)` 返回 `Optional.empty()` 表示该项不发布；
- 解析器是公共/服务端代码，禁止引用客户端类型；
- 不要把客户端预设的每个字段搬进解析器；
- 不要根据武器 ID 分支，应根据明确的数据字段或效果类型工作；
- 注册位置必须是公共初始化路径，并保证只注册一次。

如果新增需求需要给所有客户端工厂增加新的公共事件字段，则它不是“仅新增一种效果”，而是一次协议变更。必须同步修改领域事件、网络 DTO、编码、解码、数值校验、schema 版本策略和测试，详见第 14 节。

## 7. 推荐文件结构

以新效果 `example_blast` 为例：

```text
src/main/java/org/ywzj/rvp/client/visual/example/
├─ RVP_ExampleBlastEffectFactory.java
├─ RVP_ExampleBlastEffectInstance.java
├─ RVP_ExampleBlastRenderer.java
├─ RVP_ExampleBlastPreset.java
├─ RVP_ExampleBlastPresetPatch.java
├─ RVP_ExampleBlastPresetManager.java       # 需要资源预设时
├─ RVP_ExampleBlastSoundController.java      # 需要复杂声音时
└─ RVP_ExampleBlastScreenFeedback.java       # 需要专属 GUI/镜头反馈时

src/main/resources/assets/rvp/
├─ visual_effects/example_blast_standard.json
├─ textures/particle/example_blast.png
├─ sounds/example_blast_near.ogg
└─ sounds.json

src/test/java/org/ywzj/rvp/client/visual/example/
├─ RVP_ExampleBlastPresetPatchTest.java
├─ RVP_ExampleBlastCurveTest.java
├─ RVP_ExampleBlastResourceTest.java
└─ RVP_ExampleBlastBudgetTest.java
```

命名要求：类名和武器名使用正规缩写写法，例如 `AntiRadiation`、`TVMissile`、`GPS`，不要写成 `Antiradiation`、`TvMissile`、`Gps`。

## 8. 实现客户端工厂

### 8.1 工厂最小模板

```java
public final class RVP_ExampleBlastEffectFactory
        implements RVP_ClientVisualEffectFactory {

    /** 通用视觉协议中的示例爆炸效果类型。 */
    public static final ResourceLocation EFFECT_TYPE =
            ResourceLocation.fromNamespaceAndPath("rvp", "example_blast");

    @Override
    public Optional<RVP_ClientVisualEffect> create(
            ClientLevel level,
            RVP_VisualEffectEvent event) {
        // 调用本效果预设管理器，选择资源预设并应用武器 preset_data 稀疏覆盖。
        RVP_ExampleBlastPreset preset = RVP_ExampleBlastPresetManager.resolve(
                event.preset(), event.canonicalPresetDataJson());

        int duration = event.durationTicks() >= 0
                ? event.durationTicks()
                : preset.effectEndTick();
        long elapsed = Math.max(0L, level.getGameTime() - event.startGameTime());
        if (elapsed >= duration) {
            return Optional.empty();
        }

        return Optional.of(new RVP_ExampleBlastEffectInstance(
                level, event, preset, duration, (int) elapsed));
    }
}
```

工厂应完成四件事：

1. 解析本类型预设；
2. 应用顶层寿命覆盖；
3. 用 `startGameTime` 计算初始年龄，补偿网络延迟和客户端卡顿；
4. 已过期或无法安全创建时返回 `Optional.empty()`。

客户端分发器收到未知 `effect_type` 时只警告一次并安全忽略。工厂抛出未捕获异常会进入客户端主线程，因此工厂边界必须尽量把错误转为安全回退或空结果。

### 8.2 注册工厂

在 `RVP_ClientBootstrap.onClientSetup()` 的 `event.enqueueWork(...)` 中注册：

```java
RVP_ClientVisualEffectDispatcher.register(
        RVP_ExampleBlastEffectFactory.EFFECT_TYPE,
        new RVP_ExampleBlastEffectFactory());
```

注册表不允许重复键，重复注册会抛出 `IllegalStateException`。注册代码只能放在客户端初始化路径，不能移到 `RVP_MOD` 公共构造器。

## 9. 设计类型化预设

### 9.1 为什么不能直接把 JSON 当动态配置使用

`canonicalPresetDataJson` 只保证它是大小受限、键顺序稳定的 JSON，不保证字段类型、范围或业务关系合法。每个工厂必须拥有自己的类型化 schema 和校验逻辑。

推荐结构：

- `RVP_ExampleBlastPreset`：完整、不可变、运行时可直接读取的值对象；
- `RVP_ExampleBlastPreset.DEFAULT`：所有字段都有安全默认值；
- `RVP_ExampleBlastPresetPatch`：能区分“字段缺失”和“显式填写”的稀疏覆盖；
- `RVP_ExampleBlastPresetManager`：加载客户端资源预设并发布不可变快照。

不要直接用 Gson 把稀疏 JSON 反序列化成带默认值的完整对象后覆盖基础预设，否则无法可靠地区分缺失字段、非法字段和显式零值。

### 9.2 合并优先级

新效果应遵循以下优先级：

```text
内建安全默认值
  < assets/<namespace>/visual_effects/<path>.json 资源预设
  < 武器 visual_effect_data[].preset_data
  < 顶层 scale / density / duration_ticks / sound / flash / shake
  < 客户端质量、性能与无障碍上限
```

关键语义：

- 资源预设是作者可复用的基础值；
- `preset_data` 只覆盖显式出现且校验成功的字段；
- 单个非法字段只回退该字段，不应拖垮整个合法对象；
- 未知字段应忽略并使用去重警告，不能静默变成新语义；
- `preset_data` 不得提高客户端主动设置的性能或无障碍上限；
- 资源重载后只影响新实例，活动实例继续使用创建时的预设快照。

### 9.3 资源预设路径

`SimpleJsonResourceReloadListener` 使用目录 `visual_effects` 时：

```text
assets/rvp/visual_effects/example_blast_standard.json
```

对应预设 ID：

```text
rvp:example_blast_standard
```

如果使用独立预设管理器，应在 `RVP_ClientReloadListeners.onRegisterReloadListeners()` 中注册：

```java
event.registerReloadListener(RVP_ExampleBlastPresetManager.INSTANCE);
```

该事件属于客户端 MOD 总线。不要使用服务端数据包重载事件读取 `assets/...`。

### 9.4 校验规则建议

每个字段至少明确：类型、单位、默认值、合法范围、零值语义、生效阶段以及与其他字段的关系。

推荐做法：

- 颜色只接受明确格式，例如 `#RRGGBB`；
- 资源 ID 用 `ResourceLocation.tryParse`；
- 浮点值先检查有限性，再检查范围；
- 计数接受非负整数，是否设业务硬上限应按内存和顶点预算决定；
- 时间字段统一以事件开始后的绝对 tick 或明确的持续 tick 表达，不混用；
- `full_tick < start_tick` 时钳制、禁用阶段或回退，必须在 schema 中固定一种语义；
- 嵌套 LOD 要校验距离单调性和保留比例单调性；
- 警告集合应去重，避免连续爆炸刷日志。

## 10. 实现客户端实例生命周期

### 10.1 接口契约

`RVP_ClientVisualEffect` 只有四个方法：

```java
void tick();
void render(RenderLevelStageEvent event);
boolean isFinished();
void close();
```

推荐实例职责：

- 构造时冻结服务端事件和类型化预设派生出的状态；
- 使用 `event.seed()` 预生成或确定性计算随机布局；
- 保存 `center`、`visualRadius`、`duration`、`age` 和必要的粒子参数；
- `tick()` 只推进逻辑时钟、声音调度和离散状态；
- `render()` 只读取当前状态并提交顶点，不推进年龄；
- `isFinished()` 同时检查寿命与当前客户端世界；
- `close()` 必须可安全调用一次，最好也能容忍重复调用。

### 10.2 延迟补帧

事件携带服务端 `startGameTime`。实例初始年龄应为：

```java
long elapsed = Math.max(0L, level.getGameTime() - event.startGameTime());
```

新客户端收到旧事件时应从正确阶段开始，而不是把已经发生的爆炸从第 0 tick 重播。若 `elapsed >= duration`，工厂直接返回空。

若效果包含一次性触发点，如闪光或第一声爆音，创建时需要依据 `initialAge` 判断它是否仍应播放，避免补帧造成重复或严重错位。

### 10.3 世界切换和全局上限

当前分发器：

- 同时最多保留 24 个活动实例；
- 超限时关闭并移除最旧实例；
- 客户端世界对象变化、维度卸载或退出世界时清空全部实例；
- Tick 使用客户端 Tick 的 END 阶段；
- 渲染统一发生在 `AFTER_WEATHER`。

因此，新效果不要再建立不受管理的全局活动实例列表。若额外注册静态声音去重表或 GUI 脉冲表，也必须在世界变化和卸载时清理；若能把状态归入实例并由 `close()` 管理，优先这样做。

## 11. 渲染设计

### 11.1 通用要求

- 使用相机相对坐标，避免大坐标精度问题；
- 明确深度测试、深度写入、混合模式、剔除和纹理状态；
- 成对恢复 RenderSystem 状态，不能污染后续世界渲染；
- 半透明元素需要稳定排序时，按相机距离由远到近排序；
- 通过 `event.getPartialTick()` 做视觉插值，不改变逻辑年龄；
- 对零半径、零尺寸、零细分、不可见透明度和越界阶段尽早返回；
- 不要每帧创建大量临时集合、随机数生成器或资源对象；
- 随机布局必须由服务端 seed 派生，不能依赖每帧随机；
- 渲染器只读取实例状态，几何模拟应在构造或 Tick 中准备。

### 11.2 尺寸与物理半径

温压实现使用：

```text
visualRadius = baseExplosionRadius × scale
```

新效果应优先沿用这一语义。`baseExplosionRadius` 已是服务端根据普通、空爆或近炸规则解析后的最终爆炸半径。不要从武器 JSON 重新读取原始半径，也不要让 `scale` 改变伤害范围。

如果某子阶段需要更大或更小的范围，应在类型预设中提供相对 `visualRadius` 的因子。

### 11.3 阶段时间线

推荐把复杂效果拆成若干有明确时间窗口的子阶段，例如：

```text
0 ── 点火核心 ───────── 淡出
     └── 压力波/凝结云 ── 派生结束
         └── 贴地尘环 ─── 淡出
0 ───────── 后燃烟云运动 ───────── 淡出
```

每个阶段应明确：

- start tick；
- full tick 或峰值 tick；
- fade duration 或 end tick；
- 位置、尺度、颜色、透明度的曲线；
- `duration_ticks` 提前截断时的行为。

曲线计算尽量抽成无 Minecraft 客户端依赖的纯函数，便于单元测试边界和单调性。

温压实现对贴地尘环还有一层代码级空爆门控：

- `RVP_ThermobaricEffectInstance.MAX_DUST_RING_AIRBORNE_HEIGHT` 是尘环允许生成的最大爆心离地高度，当前为 `20.0F` 格；
- 客户端以爆心中心 X/Z 下方的 `MOTION_BLOCKING` 高度图候选碰撞表面作为地面，并使用碰撞形状顶面进行最终高度判定；
- `explosionY - groundY <= 20.0` 时照常采样地表并渲染尘环，严格超过 20 格时跳过地表采样、尘环环段数据和渲染；恰好 20 格仍生成；
- 该阈值是 Java 代码配置字段，不新增或覆盖 `preset_data` JSON 字段。地面暂不可用时不作超过阈值的推断，以避免客户端区块不同步导致尘环误消失。

## 12. 密度、LOD 与性能预算

`event.density()` 是服务端作者允许的视觉密度，不是必须完整使用的粒子数量。推荐最终数量按以下顺序收敛：

```text
预设 max_count
  → 顶层 density 限制
  → 客户端质量档限制
  → 距离 LOD
  → 全局或本效果运行时预算
```

原则：

- 客户端限制只能下调作者预算，不能上调；
- `density = 0` 必须有清晰语义，通常表示相应粒子组为零；
- 正比例保留时是否至少保留一个粒子，应由效果 schema 明确；
- LOD 应在姿态计算、透明排序和顶点提交前生效；
- 对高计数字段进行内存、构造耗时和每帧顶点数评估；
- 距离之外可完全不创建实例，或创建只有声音的轻量实例，但行为要写入测试；
- 活动实例已有全局 24 个上限，本效果仍需限制单实例成本。

温压实现的 `experimental.dynamic_particle_budget` 是该类型的实验算法，不是所有新效果必须复用的通用策略。新效果若需要类似能力，应先判断它是否能作为本类型 `preset_data` 字段实现；只有确实需要跨类型公共开关时，才扩展 `experimental` 和网络事件。

## 13. 声音、闪光和镜头震动

### 13.1 顶层开关是许可，不是强制播放

- `event.sound()` 为 `false` 时，本效果不得播放专属声音；
- `event.flash()` 为 `false` 时，不得触发专属闪光；
- `event.shake()` 为 `false` 时，不得触发专属震动；
- 客户端音量、闪光强度、震动强度配置仍可把许可进一步压到零。

### 13.2 复杂声音控制器

当效果有声速延迟、近远音、尾音或去重需求时，建议像温压实现一样使用独立控制器。控制器应：

- 以事件 seed 产生确定性延迟和变体；
- 以初始年龄补偿迟到消息；
- 避免同一事件重复播放；
- 在实例 `close()`、切换世界和退出世界时取消未播放任务；
- 从 `sounds.json` 中引用可被资源包覆盖的声音事件，不硬编码文件路径。

### 13.3 专属 GUI 与相机事件

通用分发器只管理世界视觉实例。温压闪光与震动目前由 `RVP_ClientVisualEvents` 的 `RenderGuiEvent.Post` 和 `ViewportEvent.ComputeCameraAngles` 转发给温压反馈服务。

新增效果如果需要同类反馈，优先考虑把反馈能力抽象为新的通用客户端管理器，再由现有 Forge 事件统一转发；不要为每种效果无限增加重复的全局事件处理器。若只是实例渲染的一部分，则保留在实例/渲染器中。

## 14. 何时必须修改公共协议

仅当现有 `RVP_VisualEffectEvent` 无法携带一种必须由服务端权威提供、且不能安全放入 `preset_data` 的信息时，才扩展公共协议。例如服务端计算出的命中法线、目标实体引用或额外事件分类。

一次完整协议修改至少涉及：

1. `RVP_VisualEffectData` 或新的服务端数据来源；
2. `RVP_DetonationVisualContext`，如果爆炸入口需要提供新权威信息；
3. `RVP_DetonationVisualResolver` / `RVP_DefaultDetonationVisualResolver`；
4. `RVP_VisualEffectEvent` record 与构造校验；
5. `S2CVisualEffectEvent` record；
6. `encode()`、`decode()`、flag 位或字段序列；
7. `toDomainEvent()`；
8. `SCHEMA_VERSION` 和兼容策略；
9. 编解码往返、非法值和不支持版本测试；
10. 字段说明文档。

不要只改 record 而漏改编码顺序。网络解码必须在创建领域事件前验证：有限坐标、非负半径、非负尺寸、非负密度、合法寿命和广播范围。

如果只是某效果独有的颜色、曲线、段数、贴图、声音 ID、LOD 或布尔开关，应放在本效果的 `preset_data` schema 中，不应增加网络固定字段。

## 15. 本体普通爆炸视觉抑制

`suppress_native_explosion_effect` 只在该项已经成功发布后才参与抑制。当前逻辑保证：

- 无配置、解析失败、8 KiB 超限或发布异常时，不会仅因配置意图而屏蔽本体视觉；
- 任一成功发布的项要求抑制时，本次爆炸会屏蔽本体普通爆炸视觉；
- 抑制不改变 `VehicleExplosion` 的伤害和方块破坏；
- 不抑制时，RVP 既有爆炸粒子路径仍可执行；
- 多视觉叠加时，应明确是否至少一项负责替换本体视觉。

框架已经通过现有 `RVP_ExplosionVisualSuppression` 和既有最小注入实现抑制。新增视觉不得再添加新的 Mixin 或复制抑制通道。

## 16. 服务端与物理侧安全

以下包应持续保持无客户端导入：

```text
org.ywzj.rvp.server.visual
org.ywzj.rvp.network.visual
org.ywzj.rvp.weapon.visual.api
```

网络消息处理器必须继续通过：

```text
S2CVisualEffectEvent
  → RVP_VisualEffectEndpoint.accept(...)
  → 客户端启动时安装的 RVP_ClientVisualEffectDispatcher::accept
```

不要在 `S2CVisualEffectEvent.handle()` 中直接调用客户端工厂或 `Minecraft.getInstance()`。这种写法即使在客户端运行正常，也可能导致专用服务器类加载崩溃。

专用服务器验证至少包括：

- `RVP_VisualArchitectureTest` 通过；
- 构建成功；
- 服务端启动时不加载 `net.minecraft.client`；
- 无客户端时发布端仍安全；
- 未安装客户端工厂不会反向影响服务端爆炸。

## 17. 测试策略

### 17.1 数据与预设测试

至少覆盖：

- 缺失配置保持禁用和安全默认值；
- 显式零值不会被错误抬高；
- NaN、Infinity、负数、非法资源 ID 的回退；
- 稀疏覆盖只改变显式字段；
- 单个非法字段不影响其他合法字段；
- 未知字段被忽略；
- 阶段时间关系的规范化；
- 资源预设缺失时回退内建默认值；
- F3+T 重载后新实例使用新快照，旧实例不突变。

### 17.2 工厂与生命周期测试

至少覆盖：

- `duration_ticks = -1` 使用预设结束时间；
- `duration_ticks = 0` 不创建实例；
- 延迟消息从正确 `initialAge` 开始；
- 已过期消息返回空；
- `isFinished()` 的寿命边界；
- 切换世界后结束；
- `close()` 释放延迟声音、缓存和反馈；
- 相同 seed 生成相同布局。

### 17.3 曲线与渲染辅助逻辑测试

把可测试逻辑从 OpenGL/RenderSystem 调用中分离，覆盖：

- start、full、fade、end 前后一个 tick；
- 插值的单调性和范围；
- 半径、透明度、颜色的边界；
- 零半径、零粒子、零细分；
- LOD 档位边界；
- 高密度和多实例预算；
- 透明排序键的稳定性；
- 地面采样或遮挡算法的异常区块情况。

### 17.4 网络与架构测试

如果没有修改公共协议，现有通用网络测试仍应通过。如果修改协议，则必须更新并扩充：

- encode/decode 完整往返；
- 超过 8 KiB 拒绝；
- 不支持 schema 版本拒绝；
- 非有限值和非法负值拒绝；
- flag 位互不干扰；
- 网络和公共包无客户端 import；
- 新功能没有新增或扩展 Mixin；
- 实体和渲染代码没有武器 ID 分派。

### 17.5 游戏内验证矩阵

| 维度 | 建议场景 |
| --- | --- |
| 距离 | 爆心附近、LOD 各边界、广播范围内边缘、广播范围外 |
| 爆炸类型 | 直击、空爆、近炸、零半径或极小半径 |
| 地形 | 平地、斜坡、室内、洞穴、水面、高空、区块边界 |
| 性能 | 单发、连续发射、24 个实例上限附近、多个效果叠加 |
| 客户端设置 | 各质量档、声音为零、闪光为零、震动为零 |
| 生命周期 | 高延迟、暂停后恢复、F3+T、切维度、退出世界 |
| 服务器 | 单人、局域网、专用服务器、不同维度玩家 |
| 本体视觉 | 抑制开启、抑制关闭、非法配置失败回退 |

## 18. 构建与检查命令

在 项目根目录执行：

```powershell
./gradlew build
```

提交前还应检查：

```powershell
rg -n "net\.minecraft\.client|org\.ywzj\.rvp\.client" src/main/java/org/ywzj/rvp/server/visual src/main/java/org/ywzj/rvp/network/visual src/main/java/org/ywzj/rvp/weapon/visual/api
rg -n "weaponId.*(equals|contains)" src/main/java/org/ywzj/rvp/entity src/main/java/org/ywzj/rvp/client/render
```

## 19. 开发步骤清单

### 19.1 新增普通客户端视觉类型

- [ ] 选择唯一、稳定的 `effect_type`，例如 `rvp:example_blast`。
- [ ] 确认现有领域事件字段足够，不修改公共协议。
- [ ] 设计完整预设和稀疏 patch，写清单位、默认值、范围和零值语义。
- [ ] 实现内建安全默认值和字段级回退。
- [ ] 如需资源预设，实现 manager 并注册客户端 reload listener。
- [ ] 实现 factory，处理预设合并、寿命覆盖和延迟补帧。
- [ ] 实现 instance，遵守 Tick、render、finished、close 契约。
- [ ] 实现 renderer，控制状态恢复、透明排序和每帧分配。
- [ ] 在 `RVP_ClientBootstrap` 注册工厂。
- [ ] 添加贴图、声音和 `sounds.json` 条目。
- [ ] 添加数据、预设、曲线、预算和资源测试。
- [ ] 添加官方建议 JSON 示例及字段文档。
- [ ] 运行完整构建和游戏内矩阵。

### 19.2 扩展公共协议时的额外清单

- [ ] 证明该值必须由服务端权威产生，不能放入类型预设。
- [ ] 同步修改领域事件和网络 DTO。
- [ ] 明确 schema 版本和旧客户端/服务端行为。
- [ ] 更新编码、解码、校验和 round-trip 测试。
- [ ] 保持网络层无客户端 import。
- [ ] 检查所有构造调用点和测试数据。
- [ ] 更新 `RVP_VisualEffectData` JavaDoc 与参数文档。

## 20. 常见错误

### 错误 1：为新效果复制一套 S2C 消息

普通新效果已经可以由 `effect_type + preset + preset_data` 分派。复制网络消息会扩大协议、注册和专服安全维护面。

### 错误 2：用武器文件名或武器 ID 选择渲染器

这会让效果与具体武器耦合，破坏数据驱动复用。正确做法是由武器 JSON 显式填写 `effect_type`。

### 错误 3：把所有专属参数加进 `RVP_VisualEffectEvent`

颜色、阶段时间、细分数、曲线、声音 ID、贴图和 LOD 通常都属于类型预设，应留在 `preset_data`。

### 错误 4：直接信任 `preset_data`

服务端只做 JSON 规范化和大小限制，不知道具体类型规则。客户端工厂仍必须进行类型化字段校验。

### 错误 5：在 render 中 `age++`

帧率不同会造成寿命和动画速度不同。年龄只能在客户端 Tick END 推进，render 使用 partial tick 插值。

### 错误 6：忽略 `startGameTime`

高延迟时会从头播放已经结束的爆炸，并导致声音和闪光错位。工厂必须计算 `initialAge`。

### 错误 7：视觉发布失败仍抑制本体效果

必须沿用 `RVP_VisualPublishResult` 的结果语义。只有真正发布成功的项才有资格请求抑制。

### 错误 8：在网络消息里引用客户端类

这会破坏专用服务器安全。继续使用公共 `RVP_VisualEffectEndpoint` 隔离物理侧。

### 错误 9：为视觉生命周期新增 Mixin

现有 Forge Tick、世界渲染、GUI、相机和卸载事件已经覆盖所需生命周期。应复用事件总线和分发器。

### 错误 10：只限制粒子数，不限制每帧成本

透明排序、地表采样、姿态计算和顶点提交都可能成为瓶颈。预算必须覆盖构造期内存和运行期 CPU/GPU 成本。

## 21. 当前实现参考索引

公共数据与服务端：

- `src/main/java/org/ywzj/rvp/weapon/data/RVP_VisualEffectData.java`
- `src/main/java/org/ywzj/rvp/weapon/data/RVP_VisualEffectExperimentalData.java`
- `src/main/java/org/ywzj/rvp/weapon/visual/RVP_VisualEffects.java`
- `src/main/java/org/ywzj/rvp/weapon/visual/RVP_DefaultDetonationVisualResolver.java`
- `src/main/java/org/ywzj/rvp/weapon/visual/RVP_VisualPresetDataCodec.java`
- `src/main/java/org/ywzj/rvp/weapon/visual/api/RVP_VisualEffectEvent.java`
- `src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java`

网络与端隔离：

- `src/main/java/org/ywzj/rvp/server/visual/RVP_NetworkVisualEventPublisher.java`
- `src/main/java/org/ywzj/rvp/network/visual/S2CVisualEffectEvent.java`
- `src/main/java/org/ywzj/rvp/weapon/visual/api/RVP_VisualEffectEndpoint.java`

客户端通用生命周期：

- `src/main/java/org/ywzj/rvp/client/visual/RVP_ClientVisualEffectFactory.java`
- `src/main/java/org/ywzj/rvp/client/visual/RVP_ClientVisualEffect.java`
- `src/main/java/org/ywzj/rvp/client/visual/RVP_ClientVisualEffectDispatcher.java`
- `src/main/java/org/ywzj/rvp/client/visual/RVP_ClientVisualEvents.java`
- `src/main/java/org/ywzj/rvp/client/RVP_ClientBootstrap.java`
- `src/main/java/org/ywzj/rvp/client/RVP_ClientReloadListeners.java`

温压类型化参考实现：

- `src/main/java/org/ywzj/rvp/client/visual/thermobaric/RVP_ThermobaricEffectFactory.java`
- `src/main/java/org/ywzj/rvp/client/visual/thermobaric/RVP_ThermobaricEffectInstance.java`
- `src/main/java/org/ywzj/rvp/client/visual/thermobaric/RVP_ThermobaricRenderer.java`
- `src/main/java/org/ywzj/rvp/client/visual/thermobaric/RVP_ThermobaricPreset.java`
- `src/main/java/org/ywzj/rvp/client/visual/thermobaric/RVP_ThermobaricPresetPatch.java`
- `src/main/java/org/ywzj/rvp/client/visual/thermobaric/RVP_ThermobaricPresetManager.java`
- `src/main/resources/assets/rvp/visual_effects/thermobaric_standard.json`

关键测试：

- `src/test/java/org/ywzj/rvp/weapon/data/RVP_VisualEffectDataTest.java`
- `src/test/java/org/ywzj/rvp/weapon/visual/RVP_VisualPresetDataCodecTest.java`
- `src/test/java/org/ywzj/rvp/network/visual/S2CVisualEffectEventTest.java`
- `src/test/java/org/ywzj/rvp/architecture/RVP_VisualArchitectureTest.java`
- `src/test/java/org/ywzj/rvp/client/visual/thermobaric/` 下的类型化视觉测试

## 22. 最终判断标准

一个合格的新视觉效果应满足：

1. 武器作者只需选择 `effect_type`、`preset` 和少量 `preset_data` 即可复用；
2. 服务端只发布一次小型事件，不同步逐帧粒子状态；
3. 相同事件和 seed 在客户端产生稳定结果；
4. 缺失资源、非法字段、超限覆盖、未知工厂或发布失败都能安全降级；
5. 客户端设置能够独立限制声音、闪光、震动和性能；
6. 世界切换、实例超限和寿命结束时资源被正确释放；
7. 不硬编码武器 ID，不修改本体，不新增不必要的 Mixin；
8. 公共与网络代码在专用服务器上不加载客户端类型；
9. 自定义视觉失败时保留本体普通爆炸视觉；
10. 完整构建、自动测试和游戏内矩阵通过。

达到以上条件后，新视觉才真正融入现有 `visual_effect_data` 工厂体系，而不是与温压弹并列维护的一套孤立特例。
