import { DEFAULT_SCENARIO, anglesFromVelocity, insertTargetPoint, normalizeScenario, simulationCommand, targetAt, velocityFromAngles } from './scenario.js';
import { ScenarioView } from './view.js';

/** 页面节点和本机草稿键；草稿只保留当前格式的有效输入。 */
const $ = id => document.getElementById(id), DRAFT_KEY = 'rvp-projectile-scenario-v1';
/** 编辑输入与最后有效场景分别保存；坏输入不能替换画面或被导出。 */
let draft = structuredClone(DEFAULT_SCENARIO), scene = null, valid = false, catalog = { scenarios: [], weapons: [] };
/** 播放只影响目标标记，不改变场景本身；目录请求用版本号防止旧响应覆盖新响应。 */
let tick = 0, playing = false, view = null, catalogRevision = 0;
/** -1 表示第 0 刻起点，其余为 targetPath 下标；列表签名避免输入时重建选项丢失滚动位置。 */
let selectedPathIndex = -1, pathListSignature = '';
/** 表单节点表用于局部同步，避免每次拖动重建输入框而丢失焦点。 */
const fields = new Map();

/** 所有用户文字通过 textContent/value 输出，不解释成 HTML。 */
function message(text, error = false) { $('message').textContent = text; $('message').classList.toggle('error', error); }
/** 数值输入不能为空；空值保持为 NaN 并交由统一校验提示。 */
const numberValue = input => input.value.trim() === '' ? NaN : Number(input.value);
/** 面板显示保留六位小数，原始 JSON 和场景副本不做精度裁剪。 */
const pretty = value => Number(value.toFixed(6)).toString();

/** 建立一个带中文含义和单位的普通字段。 */
function scalar(hostId, key, label, { type = 'number', help = '', min, max } = {}) {
  const wrapper = document.createElement('label'), title = document.createElement('span');
  wrapper.className = 'field'; title.textContent = label;
  const input = document.createElement(type === 'textarea' ? 'textarea' : 'input');
  if (type !== 'textarea') input.type = type;
  input.id = `field-${key}`; input.dataset.field = key;
  if (type === 'number') { input.step = ['ticks', 'coldLaunchTicks', 'seed'].includes(key) ? '1' : 'any'; if (min !== undefined) input.min = min; if (max !== undefined) input.max = max; }
  input.addEventListener('input', () => {
    draft[key] = type === 'checkbox' ? input.checked : type === 'number' ? numberValue(input) : input.value;
    // 调用统一刷新入口，在保留当前焦点的同时校验和同步预览。
    updateDraft();
  });
  wrapper.append(title, input);
  if (help) { const text = document.createElement('small'); text.className = 'help'; text.textContent = help; wrapper.append(text); }
  $(hostId).append(wrapper); fields.set(key, { input, type });
}

/** 三元组字段包含 X/Y/Z 或三个姿态分量；可选字段关闭时精确导出 null。 */
function triple(hostId, key, label, { unit = '格/秒', optional = false, fallback = [0, 0, 0], axes = ['X', 'Y', 'Z'], help = '' } = {}) {
  const wrapper = document.createElement('div'), heading = document.createElement('div'), title = document.createElement('span');
  wrapper.className = 'vector-field'; heading.className = 'vector-heading'; title.textContent = `${label}（${unit}）`; heading.append(title);
  let toggle;
  if (optional) {
    const toggleLabel = document.createElement('label'); toggle = document.createElement('input'); toggle.type = 'checkbox'; toggle.id = `enable-${key}`;
    toggleLabel.append(toggle, document.createTextNode(' 启用')); heading.append(toggleLabel);
    toggle.addEventListener('change', () => {
      draft[key] = toggle.checked ? [...fallback] : null;
      if (key === 'target' && !toggle.checked) { draft.targetPath = []; selectedPathIndex = -1; }
      updateDraft();
    });
  }
  const group = document.createElement('div'); group.className = 'triple';
  const inputs = axes.map((axis, index) => {
    const axisLabel = document.createElement('label'), input = document.createElement('input');
    axisLabel.textContent = axis; input.type = 'number'; input.step = 'any'; input.id = `field-${key}-${index}`; input.setAttribute('aria-label', `${label} ${axis}`);
    input.addEventListener('input', () => { draft[key] = inputs.map(numberValue); updateDraft(); });
    axisLabel.append(input); group.append(axisLabel); return input;
  });
  wrapper.append(heading, group);
  if (help) { const text = document.createElement('p'); text.className = 'help'; text.textContent = help; wrapper.append(text); }
  $(hostId).append(wrapper); fields.set(key, { inputs, toggle, fallback });
}

/** 字段只描述当前 Java 输入；高级字段完整保留，不隐藏导入时已有的参数。 */
function buildFields() {
  scalar('identity-fields', 'id', '用例 ID / 保存文件名', { type: 'text', help: '小写字母、数字、下划线和连字符；保存仅创建新文件。' });
  scalar('identity-fields', 'name', '用例名称', { type: 'text' });
  scalar('identity-fields', 'description', '测试说明', { type: 'textarea' });
  triple('launch-fields', 'position', '发射位置', { unit: '格' });
  scalar('launch-fields', 'yaw', '机头航向（度；0 朝 +Z，90 朝 -X）');
  scalar('launch-fields', 'pitch', '机头俯仰（度；正数抬头）', { min: -90, max: 90 });
  triple('initial-velocity-field', 'initialVelocity', '指定初速输入', { optional: true, fallback: [0, 0, 40], help: '关闭时由权威武器配置决定出膛初速；不在网页推算生产初始化。' });
  triple('target-fields', 'target', '目标起点', { unit: '格', optional: true, fallback: [0, 164, 800] });
  triple('analytic-target-fields', 'targetVelocity', '目标初速度');
  triple('analytic-target-fields', 'targetAcceleration', '目标加速度', { unit: '格/秒²' });
  scalar('target-radius-field', 'targetRadius', '目标停止球半径（格）', { min: 0 });
  scalar('environment-fields', 'ticks', '运行时长（刻；20 刻 = 1 秒）', { min: 1, max: 20000 });
  scalar('environment-fields', 'seed', '固定随机种子');
  scalar('environment-fields', 'groundY', '地面 / 海底高度 Y（格）');
  const water = document.createElement('label'), checkbox = document.createElement('input'); water.className = 'field';
  checkbox.type = 'checkbox'; checkbox.id = 'enable-waterY'; water.append(document.createTextNode('启用水平水面 '), checkbox); $('environment-fields').append(water);
  checkbox.addEventListener('change', () => { draft.waterY = checkbox.checked ? draft.groundY + 16 : null; updateDraft(); });
  scalar('environment-fields', 'waterY', '水面高度 Y（格）');
  scalar('environment-fields', 'guidance', '启用真实制导执行器的目标输入', { type: 'checkbox', help: '预览仍只显示目标运动；制导结果须运行 Java 仿真。' });
  triple('environment-fields', 'vehicleVelocity', '平台世界速度', { help: '是否叠加取决于权威武器的 inherit_vehicle_velocity 配置。' });
  scalar('cold-fields', 'coldLaunchTicks', '挂架冷发射时长（刻）', { min: 0, max: 20000 });
  triple('cold-fields', 'coldLaunchVelocity', '挂架本地冷发射速度');
  triple('cold-fields', 'carrierRotation', '发射平台姿态', { unit: '度', axes: ['航向', '俯仰', '滚转'] });
  triple('advanced-fields', 'parentRotation', '母弹释放姿态', { optional: true, unit: '度', axes: ['航向', '俯仰', '滚转'] });
  triple('advanced-fields', 'parentVelocity', '母弹世界速度');
  triple('advanced-fields', 'deploymentVelocity', '布撒初始分量', { optional: true });
  triple('advanced-fields', 'smartFuseTarget', '已接管的智能引信目标', { optional: true, unit: '格' });
}

/** 不覆盖正在键入的控件；滑条、箭头和 JSON 则从同一输入副本刷新。 */
function syncFields() {
  for (const [key, entry] of fields) {
    const value = draft[key];
    if (entry.inputs) {
      if (entry.toggle) entry.toggle.checked = value !== null;
      entry.inputs.forEach((input, i) => {
        input.disabled = value === null;
        if (document.activeElement !== input) input.value = value?.[i] ?? entry.fallback[i];
      });
    } else if (entry.type === 'checkbox') entry.input.checked = value;
    else {
      entry.input.disabled = key === 'waterY' && value === null;
      if (document.activeElement !== entry.input) entry.input.value = value ?? '';
    }
  }
  $('enable-waterY').checked = draft.waterY !== null;
  $('yaw-slider').value = ((draft.yaw + 180) % 360 + 360) % 360 - 180; $('pitch-slider').value = draft.pitch;
  $('yaw-output').textContent = `${draft.yaw}°`; $('pitch-output').textContent = `${draft.pitch}°`;
  $('initial-direction').hidden = draft.initialVelocity === null;
  if (draft.initialVelocity?.every(Number.isFinite)) {
    const angles = anglesFromVelocity(draft.initialVelocity);
    for (const key of ['speed', 'yaw', 'pitch']) if (document.activeElement !== $(`initial-${key}`)) $(`initial-${key}`).value = pretty(angles[key]);
  }
  const selectedWeapon = catalog.weapons.find(weapon => weapon.id === draft.weapon);
  $('weapon-hint').textContent = selectedWeapon ? `${selectedWeapon.type} · 平台速度继承：${selectedWeapon.inheritVehicleVelocity ? '开启' : '未开启'}。实际初始化以 Java 回放首帧为准。` : '此 ID 未在当前权威武器目录中找到；请核对后选择。';
  if ($('weapon').value !== draft.weapon) renderWeapons();
  $('aim-target').disabled = !draft.target;
  $('focus-target').disabled = !draft.target;
  // 调用轨迹点局部同步，保留当前键入位置并明确速度字段在自由轨迹中不生效。
  syncPathFields();
}

/** 轨迹列表与单点坐标从输入副本读取；坏输入仍留在表单，便于原地修正。 */
function syncPathFields() {
  const path = draft.targetPath, enabled = path.length > 0;
  selectedPathIndex = Math.max(-1, Math.min(selectedPathIndex, path.length - 1));
  $('target-motion').value = enabled ? 'path' : 'analytic'; $('target-motion').disabled = !draft.target;
  $('path-controls').hidden = !enabled;
  $('analytic-target-fields').hidden = enabled; $('target-preset').closest('label').hidden = enabled;
  $('motion-help').textContent = enabled ? '自由轨迹已接管目标运动。初速度和加速度数值保留，切回原方式时使用。'
    : '目标位置 = 起点 + 初速 × 秒数 + ½ × 加速度 × 秒数²。也可双击玫红路线，转为可编辑的到达点。';
  for (const key of ['targetVelocity', 'targetAcceleration']) for (const input of fields.get(key).inputs) input.disabled = enabled;
  $('target-preset').disabled = enabled;
  $('drag-mode').querySelector('[value="targetVelocity"]').disabled = enabled;
  if (enabled && $('drag-mode').value === 'targetVelocity') { $('drag-mode').value = 'pathPoint'; view?.setDragMode('pathPoint'); }
  const signature = JSON.stringify([draft.target, path]);
  if (signature !== pathListSignature) {
    $('path-points').replaceChildren(new Option('起点 · 第 0 刻', '-1'), ...path.map((point, index) => {
      const option = new Option(`${index + 1} · 第 ${point.tick} 刻 · ${(point.tick / 20).toFixed(2)} 秒`, String(index));
      option.title = `X / Y / Z：${point.position.map(pretty).join(' / ')}`; return option;
    }));
    $('path-ticks').replaceChildren(...path.map(point => new Option(`第 ${point.tick} 刻`, String(point.tick))));
    pathListSignature = signature;
  }
  $('path-points').value = String(selectedPathIndex); $('path-count').textContent = `${path.length} / 256 个到达点`;
  const point = selectedPathIndex < 0 ? { tick: 0, position: draft.target } : path[selectedPathIndex];
  $('path-tick').disabled = selectedPathIndex < 0; $('path-tick').max = draft.ticks;
  if (document.activeElement !== $('path-tick')) $('path-tick').value = point?.tick ?? 0;
  $('path-seconds').textContent = selectedPathIndex < 0 ? '起点固定在第 0 刻；可编辑下方坐标。' : `${(point.tick / 20).toFixed(2)} 秒；修改后须仍位于前后点之间。`;
  for (const [axis, index] of ['x', 'y', 'z'].map((axis, index) => [axis, index])) {
    if (document.activeElement !== $(`path-${axis}`)) $(`path-${axis}`).value = point?.position?.[index] ?? '';
  }
  $('delete-path-point').disabled = selectedPathIndex < 0;
  $('path-insert-tick').max = draft.ticks; $('add-path-point').disabled = !valid;
  // 调用画面选中同步，坐标控件始终挂在正在编辑的点上。
  view?.selectPathPoint(selectedPathIndex);
}

/** 所有分组切换共用入口，画面选点时也能直接打开目标参数。 */
function showTab(name) {
  for (const button of document.querySelectorAll('[data-tab]')) button.classList.toggle('active', button.dataset.tab === name);
  for (const panel of document.querySelectorAll('[data-panel]')) panel.hidden = panel.dataset.panel !== name;
  document.querySelector('.editor-panel').scrollTop = 0;
}

/** 选点同时定位到达时刻并启用坐标轴；起点和后续点采用同样的操作。 */
function selectPathPoint(index) {
  if (!valid || !scene.targetPath.length) return;
  selectedPathIndex = Math.max(-1, Math.min(index, scene.targetPath.length - 1));
  tick = selectedPathIndex < 0 ? 0 : scene.targetPath[selectedPathIndex].tick;
  setPlaying(false); $('drag-mode').value = 'pathPoint';
  syncPathFields(); view?.setDragMode('pathPoint'); updateTime();
}

/** 插点前按当前路线取样，已有时刻只选中；首次插点会保留原终点，恒加速曲线转为分段直线。 */
function addPathPoint(atTick) {
  if (!valid) { message('请先修正当前参数，再添加轨迹点。', true); return; }
  const existing = scene.targetPath.findIndex(point => point.tick === atTick);
  if (existing >= 0 || (atTick === 0 && scene.targetPath.length)) { selectPathPoint(existing); showTab('target'); return; }
  try {
    draft = insertTargetPoint(scene, atTick); selectedPathIndex = draft.targetPath.findIndex(point => point.tick === atTick);
    tick = atTick; setPlaying(false); $('drag-mode').value = 'pathPoint';
    updateDraft('已插入轨迹点。拖动彩色坐标轴，或修改坐标与到达时刻；点间匀速直行，最后一点后停住。');
    view?.setDragMode('pathPoint'); showTab('target');
  } catch (error) { message(error.message, true); }
}

/** 校验成功才更新场景、草稿和导出内容；出错时保留最后有效的几何画面。 */
function updateDraft(notice = '') {
  try {
    scene = normalizeScenario(draft); valid = true;
    tick = Math.min(tick, scene.ticks); view?.update(scene, tick);
    $('preview-title').textContent = scene.name;
    $('timeline').max = scene.ticks; $('duration').textContent = `/ ${(scene.ticks / 20).toFixed(2)} s`;
    if (document.activeElement !== $('json-editor')) $('json-editor').value = JSON.stringify(scene, null, 2);
    $('command').textContent = simulationCommand(scene);
    try { localStorage.setItem(DRAFT_KEY, JSON.stringify(scene)); $('draft-status').textContent = '本机草稿已保留 · 仅创建新文件'; }
    catch { $('draft-status').textContent = '当前浏览器无法保存草稿，请下载 JSON 保留'; }
    message(notice || '参数有效 · 画面只预览目标运动与发射初态；真实导弹轨迹请运行 Java 仿真。');
  } catch (error) { valid = false; setPlaying(false); message(`${error.message}。画面保留最后一次有效输入。`, true); }
  for (const id of ['download', 'save', 'copy-command']) $(id).disabled = !valid;
  syncFields(); updateTime();
}

/** 下拉目录可筛选，当前武器始终保留，避免筛选时意外更改场景。 */
function renderWeapons() {
  const query = $('weapon-search').value.trim().toLowerCase();
  const weapons = catalog.weapons.filter(weapon => weapon.id === draft.weapon || `${weapon.id} ${weapon.name} ${weapon.type}`.toLowerCase().includes(query));
  const options = weapons.map(weapon => new Option(`${weapon.name} · ${weapon.id}`, weapon.id));
  if (!weapons.some(weapon => weapon.id === draft.weapon)) options.unshift(new Option(draft.weapon || '请选择权威武器', draft.weapon));
  $('weapon').replaceChildren(...options); $('weapon').value = draft.weapon;
}

/** 模板按显示名/武器/文件名筛选，不改变已编辑的输入。 */
function renderTemplates() {
  const previous = $('template').value, query = $('template-search').value.trim().toLowerCase();
  const entries = catalog.scenarios.filter(entry => `${entry.file} ${entry.scene.name} ${entry.scene.weapon}`.toLowerCase().includes(query));
  $('template').replaceChildren(...entries.map(entry => new Option(`${entry.scene.name} · ${entry.file}`, entry.file)));
  if (entries.some(entry => entry.file === previous)) $('template').value = previous;
  $('load-template').disabled = !entries.length;
  $('catalog-count').textContent = `${entries.length}/${catalog.scenarios.length} 个模板 · ${catalog.weapons.length} 把武器`;
}

/** 复制模板时分配未使用的新 ID，原场景文件保持可读的独立模板。 */
function uniqueId(base) {
  const root = base.slice(0, 105), ids = new Set(catalog.scenarios.map(entry => entry.scene.id));
  ids.add(draft.id); let candidate = `${root}_custom`, suffix = 2;
  while (ids.has(candidate)) candidate = `${root}_custom_${suffix++}`;
  return candidate;
}

/** 用户主动载入的输入先校验再替换；导入失败不能损坏当前工作副本。 */
function replaceDraft(input, notice, copy = false) {
  const next = normalizeScenario(input);
  if (copy) { next.id = uniqueId(next.id); next.name = `${next.name}（副本）`; }
  draft = next; tick = 0; selectedPathIndex = -1; setPlaying(false); updateDraft(notice); view?.fit();
}

/** 重新读取插件接口，不依赖已有的仿真输出或 Minecraft 客户端。 */
async function refreshCatalog(initial = false) {
  const revision = ++catalogRevision; $('refresh').disabled = true;
  try {
    const response = await fetch('/api/plugins/projectile-scenario/catalog');
    if (!response.ok) throw new Error(await response.text());
    const data = await response.json(); if (revision !== catalogRevision) return;
    catalog = data; renderTemplates(); renderWeapons();
    $('warnings').replaceChildren(...data.warnings.map(text => { const p = document.createElement('p'); p.textContent = text; return p; }));
    $('catalog-warnings').hidden = !data.warnings.length;
    if (initial) {
      let restored;
      try { const stored = localStorage.getItem(DRAFT_KEY); if (stored) restored = normalizeScenario(JSON.parse(stored)); }
      catch { message('本机草稿无效，已改为读取当前场景模板。', true); }
      const first = catalog.scenarios.find(entry => catalog.weapons.some(weapon => weapon.id === entry.scene.weapon && weapon.type === 'rvp:missile')) ?? catalog.scenarios[0];
      if (restored) replaceDraft(restored, '已恢复本机草稿。');
      else if (first) { $('template').value = first.file; replaceDraft(first.scene, `已从 ${first.file} 建立副本，原文件不受影响。`, true); }
      else createNew();
    } else message(`目录已刷新：${catalog.scenarios.length} 个场景模板、${catalog.weapons.length} 把武器。`);
  } catch (error) {
    message(`目录读取失败：${error.message}。仍可导入本地场景 JSON。`, true);
    $('template').replaceChildren(new Option('目录不可用，可导入本地 JSON', '')); $('load-template').disabled = true;
  } finally { if (revision === catalogRevision) $('refresh').disabled = false; }
}

/** 空白用例使用当前选中武器，或目录中第一种导弹，不硬编码具体武器 ID。 */
function createNew() {
  const weapon = draft.weapon || catalog.weapons.find(entry => entry.type === 'rvp:missile')?.id || catalog.weapons[0]?.id || '';
  draft = { ...structuredClone(DEFAULT_SCENARIO), id: uniqueId('projectile'), weapon, target: [0, 164, 800] };
  tick = 0; selectedPathIndex = -1; setPlaying(false); renderWeapons(); updateDraft('已建立新用例。'); view?.fit();
}

/** 目标预设只修改位置存在性、速度和加速度；朝向以当前发射点与目标起点计算。 */
function applyTargetPreset(mode) {
  if (!mode) return;
  draft.target ??= [draft.position[0], draft.position[1], draft.position[2] + 800];
  draft.targetAcceleration = [0, 0, 0];
  const toward = draft.position.map((v, i) => v - draft.target[i]), distance = Math.hypot(...toward);
  if (['incoming', 'receding'].includes(mode) && !distance) { message('目标起点和发射点重合，请先分开再选择接近/远离。', true); return; }
  const values = { stationary: [0, 0, 0], crossing: [30, 0, 0], climbing: [0, 20, 0], accelerating: [15, 0, 0],
    incoming: toward.map(v => v / distance * 40), receding: toward.map(v => -v / distance * 40) };
  draft.targetVelocity = values[mode]; if (mode === 'accelerating') draft.targetAcceleration = [3, 0, 0];
  updateDraft('已设置目标运动。');
}

/** 播放状态只操作目标运动标记，随倒拖即时恢复。 */
function setPlaying(value) { playing = value; $('play').textContent = value ? 'Ⅱ' : '▶'; $('play').setAttribute('aria-label', value ? '暂停目标运动' : '播放目标运动'); }
function updateTime() {
  if (!scene) return;
  $('timeline').value = tick; $('time').textContent = `${(tick / 20).toFixed(2)} s · 刻 ${Math.floor(tick)}`;
  const state = targetAt(scene, tick); view?.seek(tick);
  $('target-position').textContent = state ? `目标 X ${state.position[0].toFixed(2)}　Y ${state.position[1].toFixed(2)}　Z ${state.position[2].toFixed(2)}` : '当前无目标';
  $('target-speed').textContent = state ? `目标速度 ${Math.hypot(...state.velocity).toFixed(2)} 格/秒　·　X/Y/Z ${state.velocity.map(v => v.toFixed(2)).join(' / ')}` : '可在“目标运动”中启用目标起点';
  if (document.activeElement !== $('path-insert-tick')) $('path-insert-tick').value = Math.round(tick);
}

buildFields();
try {
  view = new ScenarioView($('preview-host'), (key, value) => {
    if (key === 'pathPoint' && selectedPathIndex >= 0) draft.targetPath[selectedPathIndex].position = value;
    else draft[key === 'pathPoint' ? 'target' : key] = value;
    setPlaying(false); updateDraft();
  }, index => { selectPathPoint(index); showTab('target'); }, atTick => addPathPoint(atTick));
} catch (error) { $('preview-error').hidden = false; $('preview-error').textContent = `三维画面无法启动：${error.message}。参数编辑、目标数值预览与 JSON 保存仍可使用。`; }
$('editor-form').addEventListener('submit', event => event.preventDefault());
for (const tab of document.querySelectorAll('[data-tab]')) tab.addEventListener('click', () => {
  // 分组切换回到面板顶部，避免继承另一组的滚动位置而漏看关键输入。
  showTab(tab.dataset.tab);
});
$('weapon').addEventListener('change', event => { draft.weapon = event.target.value; updateDraft(); });
$('weapon-search').addEventListener('input', renderWeapons); $('template-search').addEventListener('input', renderTemplates);
$('refresh').addEventListener('click', () => refreshCatalog()); $('new').addEventListener('click', createNew);
$('duplicate').addEventListener('click', () => { if (valid) replaceDraft(scene, '已复制为新用例。', true); });
$('load-template').addEventListener('click', () => {
  const entry = catalog.scenarios.find(entry => entry.file === $('template').value);
  if (entry) replaceDraft(entry.scene, `已从 ${entry.file} 建立可编辑副本。`, true);
});
for (const key of ['yaw', 'pitch']) $(`${key}-slider`).addEventListener('input', event => { draft[key] = Number(event.target.value); updateDraft(); });
$('aim-target').addEventListener('click', () => {
  if (!draft.target) return;
  const angles = anglesFromVelocity(draft.target.map((v, i) => v - draft.position[i]));
  if (!angles.speed) { message('目标起点和发射点重合，无法确定机头方向。', true); return; }
  draft.yaw = angles.yaw; draft.pitch = angles.pitch; updateDraft();
});
for (const key of ['speed', 'yaw', 'pitch']) $(`initial-${key}`).addEventListener('input', () => {
  const speed = numberValue($('initial-speed')), yaw = numberValue($('initial-yaw')), pitch = numberValue($('initial-pitch'));
  if (![speed, yaw, pitch].every(Number.isFinite) || speed < 0 || Math.abs(pitch) > 90) {
    valid = false; setPlaying(false); for (const id of ['download', 'save', 'copy-command']) $(id).disabled = true;
    message('初速速率须非负，航向须有效，俯仰须在 ±90 度内。', true); return;
  }
  draft.initialVelocity = velocityFromAngles(yaw, pitch, speed); updateDraft();
});
$('velocity-from-head').addEventListener('click', () => {
  draft.initialVelocity = velocityFromAngles(draft.yaw, draft.pitch, Math.hypot(...(draft.initialVelocity ?? [0, 0, 40]))); updateDraft();
});
$('target-preset').addEventListener('change', event => { applyTargetPreset(event.target.value); event.target.value = ''; });
$('target-motion').addEventListener('change', event => {
  if (event.target.value === 'path') { addPathPoint(scene?.ticks); return; }
  draft.targetPath = []; selectedPathIndex = -1; setPlaying(false);
  updateDraft('已切回初速度与恒定加速度运动，轨迹点已清除。');
});
$('path-points').addEventListener('change', event => selectPathPoint(Number(event.target.value)));
$('path-tick').addEventListener('input', event => {
  if (selectedPathIndex < 0) return;
  const value = numberValue(event.target); draft.targetPath[selectedPathIndex].tick = value;
  setPlaying(false); updateDraft();
  if (valid) { tick = value; updateTime(); }
});
for (const axis of ['x', 'y', 'z']) $(`path-${axis}`).addEventListener('input', () => {
  const position = ['x', 'y', 'z'].map(key => numberValue($(`path-${key}`)));
  if (selectedPathIndex < 0) draft.target = position; else draft.targetPath[selectedPathIndex].position = position;
  setPlaying(false); updateDraft();
});
$('add-path-point').addEventListener('click', () => addPathPoint(numberValue($('path-insert-tick'))));
$('delete-path-point').addEventListener('click', () => {
  if (selectedPathIndex < 0) return;
  draft.targetPath.splice(selectedPathIndex, 1); selectedPathIndex--;
  setPlaying(false); updateDraft(draft.targetPath.length ? '已删除轨迹点，其余点的到达时刻保持不变。' : '最后一个轨迹点已删除，恢复初速度与恒定加速度运动。');
  if (valid && draft.targetPath.length) selectPathPoint(selectedPathIndex);
});
$('drag-mode').addEventListener('change', event => {
  const key = event.target.value;
  if (key === 'initialVelocity' && !draft.initialVelocity) { draft.initialVelocity = velocityFromAngles(draft.yaw, draft.pitch, 40); updateDraft('已启用初速输入，可拖动青绿箭头端点。'); }
  if (['target', 'targetVelocity', 'pathPoint'].includes(key) && !draft.target) { draft.target = [draft.position[0], draft.position[1], draft.position[2] + 800]; updateDraft('已启用目标，可拖动目标或速度端点。'); }
  if (key === 'pathPoint' && !draft.targetPath.length) { addPathPoint(scene?.ticks); return; }
  view?.setDragMode(key);
});
for (const mode of ['iso', 'top', 'side']) $(mode).addEventListener('click', () => {
  for (const id of ['iso', 'top', 'side']) $(id).classList.toggle('active', id === mode); view?.fit(mode);
});
$('fit').addEventListener('click', () => view?.fit());
$('focus-target').addEventListener('click', () => view?.fit(view.mode, true));
$('timeline').addEventListener('input', event => { setPlaying(false); tick = Number(event.target.value); updateTime(); });
$('play').addEventListener('click', () => { if (!scene || !valid) return; if (tick >= scene.ticks) tick = 0; setPlaying(!playing); updateTime(); });
$('previous').addEventListener('click', () => { setPlaying(false); tick = Math.max(0, Math.ceil(tick) - 1); updateTime(); });
$('next').addEventListener('click', () => { setPlaying(false); tick = Math.min(scene?.ticks ?? 0, Math.floor(tick) + 1); updateTime(); });
$('apply-json').addEventListener('click', () => {
  try { replaceDraft(JSON.parse($('json-editor').value.replace(/^\uFEFF/, '')), '已应用 JSON。'); }
  catch (error) { message(`JSON 未应用：${error.message}。当前工作副本保留。`, true); }
});
$('import').addEventListener('change', async event => {
  const file = event.target.files[0]; if (!file) return;
  try {
    if (file.size > 50 * 1024 * 1024) throw new Error('文件超过 50 MB');
    const data = JSON.parse((await file.text()).replace(/^\uFEFF/, ''));
    replaceDraft(data.format === 'rvp-projectile-simulation' ? data.scene : data, `已从 ${file.name} 导入为副本。`, true);
  } catch (error) { message(`导入失败：${error.message}。当前工作副本保留。`, true); }
  finally { event.target.value = ''; }
});
$('download').addEventListener('click', () => {
  if (!valid) return;
  const url = URL.createObjectURL(new Blob([`${JSON.stringify(scene, null, 2)}\n`], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = `${scene.id}.json`; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000); message('已下载场景 JSON，可放入 src/test/resources/projectile-simulation/。');
});
$('save').addEventListener('click', async () => {
  if (!valid) return;
  const saved = structuredClone(scene); $('save').disabled = true;
  try {
    const response = await fetch('/api/plugins/projectile-scenario/scenarios', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(saved) });
    if (!response.ok) throw new Error(await response.text());
    const result = await response.json(); catalog.scenarios.push({ file: `${saved.id}.json`, scene: saved }); renderTemplates();
    message(`已保存 ${result.file}。复制仿真命令运行后，可到回放工作台刷新目录查看。`);
    $('draft-status').textContent = `已保存 ${saved.id}.json · 继续生成对照例可点“复制为新用例”`;
  } catch (error) { message(`保存失败：${error.message}`, true); }
  finally { $('save').disabled = !valid; }
});
$('copy-command').addEventListener('click', async () => {
  if (!valid) return;
  try { await navigator.clipboard.writeText(simulationCommand(scene)); message('已复制仿真命令。先保存同 ID 场景，再在项目根目录执行。'); }
  catch { $('command').closest('details').open = true; message('剪贴板不可用，请在下方展开的命令中手动复制。'); }
});
document.addEventListener('keydown', event => {
  if (!scene || !valid || /INPUT|SELECT|TEXTAREA|BUTTON|SUMMARY/.test(event.target.tagName) || event.target.isContentEditable) return;
  if (['Space', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.code)) event.preventDefault();
  if (event.code === 'Space') { if (tick >= scene.ticks) tick = 0; setPlaying(!playing); }
  if (event.code === 'ArrowLeft') { setPlaying(false); tick = Math.max(0, Math.ceil(tick) - 1); }
  if (event.code === 'ArrowRight') { setPlaying(false); tick = Math.min(scene.ticks, Math.floor(tick) + 1); }
  if (event.code === 'Home' || event.code === 'End') { setPlaying(false); tick = event.code === 'Home' ? 0 : scene.ticks; }
  updateTime();
});
/** 只有播放头随实际时间前进，场景参数与导出内容保持不变。 */
let previousTime = performance.now();
function animate(time) {
  const seconds = Math.min((time - previousTime) / 1000, 0.1); previousTime = time;
  if (playing && scene) { tick = Math.min(scene.ticks, tick + seconds * 20 * Number($('rate').value)); if (tick >= scene.ticks) setPlaying(false); updateTime(); }
  view?.render(); requestAnimationFrame(animate);
}
window.addEventListener('pagehide', () => view?.dispose());
requestAnimationFrame(animate);
await refreshCatalog(true);
