# RVP 会话交接：发射音效改回 display 渠道（2026-11-03）

本文件为最新会话入口；前一入口 `RVP会话交接_动作条分辨率适配与辅助载具身份_20261103.md`。操作习惯仍先读手册、根 agents.md 与调试规范。当前 HEAD `a6c490dc34c9a5d51883c3fbe6641ce996d6baf7`（Use weapon display for launch sound configuration）；Gitee/GitHub master 均已推送本提交。

## 当前版本与已部署内容

mod、源包、构建包和 A1 包版本统一 `0.1.0w_beta_0.6.2`，协议41。完整版 `D:\ywzj\ywzj\ywzj_rvp\build\libs\ywzj_rvp-1.20.1-0.1.0w_beta_0.6.2-full.jar`，SHA256 `c6d321c9a1026e02202e6de62f886f4c0ef23a045c4289e248f02ce96f831ca3`；A1 `D:\ywzj rvp测试用客户端A1.0\ywzj rvp测试用客户端A1.0\.minecraft\versions\永无止境rvp测试服\mods\ywzj_rvp-1.20.1-0.1.0w_beta_0.6.2-full.jar` 与最终门禁构建逐字节一致。1151个发布包文件在源、build/rvp-pack、full.jar、A1 全部校验；179个变更包文件已同步并备份。旧v jar按删除规范移入回收站并登记；A1 options/lighting等受保护设置未变。

前一提交 e013cc30 的 v 版实现了音效迁包、RAWR/PG7V参数，但误把发射音效参数新增到武器数据 `fire_sound_data`。用户明确要求撤回该入口，改回 display；本轮已完成纠正，禁止从旧备份恢复错误设计。

## 现有通道调查与最终实现

逐读本体 AbstractVehicleWeapon、BaseDisplay/BaseDisplayPojo、VehiclePackLoader、VehicleSound，以及 RVP 现有相机相对、观瞄、衰减处理器后实施：

- 音效事件仍通过原生 display `sounds.fire`；远音轨为同一原生字典的 `sounds.fire_far`。`sounds` 的值全部是资源ID字符串，不能把数值塞进该字典。
- 必要距离/音量/音调参数仅在武器 display 顶层 `fire_sound`；字段为 `max_distance`（格）、`far_distance`（格，配远音轨时生效）、`volume`、`pitch`（后二者缺省1）。不声明则走现有原生处理；不按载具类别/口径自动填默认音。
- 在已有 RVP_BaseDisplayPojo/BaseDisplay 扩展解析；共用双端安全的 RVP_DisplayFireSound 进行校验。原生 fire/sight_fire 观瞄双音轨优先保留，不被默认导弹规则绕过。
- 移除了 RVP_FireSoundData、RVP_WeaponData 对应字段，以及独立事件订阅器 RVP_ClientWeaponFireSound。89把武器的 fire_sound_data 全部撤回；89份对应 display 已接管配置。没有新增 Mixin 或改本体源码。
- 本地发射接入已有 RVP_ClientCameraRelativeFireSound 事件入口，复用其单次抑制和冷发射延迟抑制队列；客户端辅助类仅供该入口和远距通知共用。原500格衰减对 display 显式范围让出，避免范围再被硬截断。
- 超实体跟踪距离仍复用既有 S2CVehicleSound 传输，载荷只含源载具身份/位置与武器ID，客户端按ID取自己的已加载 display，不再传武器数据声音对象。服务端沿用本体 VehiclePackLoader 的包列表、目录/zip与资源优先级读取 display JSON，只发同维度、听距内、跟踪范围外玩家；不会每次开火扫磁盘。首次发射缓存，常规 reload/ywzj_vehicle reload/rvp reload 命令及停服使缓存失效。显示/音效的客户端刷新仍用原生全量资源重载。

示例（武器 display 内）：

```json
{
  "sounds": {"fire": "rvp:launch_cold", "fire_far": "rvp:launch_cold_far"},
  "fire_sound": {"max_distance": 2048, "far_distance": 512, "volume": 1, "pitch": 1}
}
```

## 保留的上一轮授权内容

13个发射OGG已迁到源包 assets/rvp/sounds/weapon/launch，原src内样本已回收并提交剔除，禁止恢复硬编码样本组。包 sounds.json 声音组：air59武器/150格，surface20/2048格，cold7/近远512格分界、最远2048格，large_rocket3/最远64格、volume4。共89；3把有自定义声音的武器未改，无匹配载具的武器未擅自套用。

RAWR及rawr_deployed俯仰仍为 -20°至+10°（负数是仰角）；drone_pg7v直击700，projectile_data.gravity=-0.15（原-0.03的5倍）。真实公式每tick向Y速度加gravity；根gravity_scale不参与该实现，因此未改。上述内容包含于当前w版发布包。

## 验证和界限

- 显式JAVA_HOME=JDK21.0.11，offline build jarFull 成功；1349项测试、0失败/错误/跳过。减少1项来自移除错误武器数据配置测试，并改为display解析/非法配置/原生fire缺失/远距门限/消息仅传武器标识等回归。
- 临时真实客户端经原生载具包加载器加载1151文件；89个已实例化display与服务端安全解析的89份元数据逐项一致，5组事件有效，13个OGG经原生解码通过。临时probe未进入src或jar。此次检查了资源解析与实例参数，未声称多人实际距离试听已完成。
- 新鲜服务端日志 `[0311月2026 06:55:39.618] [Server thread/INFO] [net.minecraft.server.dedicated.DedicatedServer/]: Done (2.201s)! For help, type "help"`；与v版冒烟基线8个既有模型ERROR一致，新增0。打印并核验完整命令行后仅终止本次RVP服务端23036，wrapper11916随后自动退出，25565释放；没有用户客户端误杀。最初一次taskkill因PATH中无该命令未执行，随后用System32绝对路径完成。
- 门禁以构建 && 源/包/jar指纹校验 && git add src/ && 任务专属武器索引 && 暂存检查 && commit阻断；show --stat核对106个文件，新增display公共类已提交，两个错误入口类已提交剔除。src修改与untracked为0。
- 6个重叠武器WIP通过基于HEAD去字段的索引候选保留；89份工作区武器数据与迁包前备份语义完全一致。Meshy、鲨鱼网、既有文档与7个武器WIP均保留；没有碰废止authoritative或本体。

完整状态 `_tmp_display_sound_20261103.json`；备份 `D:\ywzj\ywzj\ywzj_rvp\_backups\display_sound_20261103_064927`，包备份 `limitless_vehicle/rvp/_bak/display_sound_20261103_064927`；旧音效迁移清单 `_tmp_sound_migration_inventory_20261103.json`、旧状态 `_tmp_pack_fire_sound_20261103.json` 可查历史，不能当最终版本。

用户新增工作铁律已写入本地skip-worktree agents.md：所有功能实现前先查本体/RVP是否已有通道；音效资源、音轨、远距播放及新增参数均归display，禁止重建武器数据平行音效入口。后续由本人接手，不再派Luna修改。
