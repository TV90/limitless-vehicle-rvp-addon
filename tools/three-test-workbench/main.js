import { PluginRegistry } from './core/plugins.js';
import { displayValue, indexAt, MAX_RECORD_BYTES, sampleReplay } from './core/replay.js';
import { WorkbenchView } from './core/view.js';

/** 页面控件，所有外来文字均通过 textContent 显示。 */
const $ = id => document.getElementById(id);
/** 插件注册表与目录条目；刷新时重建目录，不写死业务插件名单。 */
let registry = new PluginRegistry(), catalogEntries = new Map();
/** 当前成功载入的会话。失败导入与过期的异步请求都不能替换它。 */
let current = null, playhead = 0, playing = false, selected = '', follow = false, loadRevision = 0, catalogRevision = 0;
/** 渲染器可独立失败；缺少 WebGL 时仍保留时间轴、事件与数据面板。 */
let view = null;
/** 面板缓存避免每个显示帧重复重建未变化的离散观察值。 */
let panelKey = '', eventIndex = -2, inspectFailed = false;

function showError(message) { $('error').textContent = message; $('error').hidden = !message; }
function showRenderError(message) { $('render-error').textContent = message; $('render-error').hidden = !message; }

try {
  view = new WorkbenchView($('canvas-host'), { resolveRenderer: name => registry.renderer(name),
    onSelect: id => { selected = id; $('object').value = id; view.resetFollow(); update(); }, onError: showRenderError });
} catch (error) { showRenderError(`三维画面启动失败：${error.message}。仍可操作时间轴、观察值与事件。`); }

/** 安全构建一条观察值，不把插件返回内容当 HTML。 */
function valueRow(label, value) {
  const row = document.createElement('div'), title = document.createElement('dt'), text = document.createElement('dd');
  title.textContent = label; text.textContent = displayValue(value); row.append(title, text); return row;
}

function setPlaying(value) {
  playing = value; $('play').textContent = value ? 'Ⅱ' : '▶'; $('play').setAttribute('aria-label', value ? '暂停' : '播放');
}

/** 一切跳转基于绝对时间；事件与已走轨迹随倒拖恢复。 */
function seek(tick) {
  if (!current) return;
  playhead = Math.max(0, Math.min(current.compiled.endTick, tick)); view?.resetFollow(); update();
}

/** 当前事件面板只显示已发生部分，顶部刻线和结果区域明确展示整场。 */
function updateEvents(tick) {
  const replay = current.compiled.replay, last = indexAt(replay.events, tick);
  if (last === eventIndex) return;
  eventIndex = last; $('events').replaceChildren(); $('event-count').textContent = `${last + 1} 条已发生`;
  if (last < 0) { const p = document.createElement('p'); p.className = 'muted'; p.textContent = '当前时间暂无事件'; $('events').append(p); }
  const names = new Map(replay.objects.map(object => [object.id, object.name]));
  for (let i = last; i >= Math.max(0, last - 59); i--) {
    const event = replay.events[i], button = document.createElement('button'), time = document.createElement('time'), text = document.createElement('span');
    button.className = 'event'; time.textContent = `${(event.tick / replay.tickRate).toFixed(2)} s`;
    text.textContent = `${names.get(event.objectId) ?? '场景'} · ${event.message}`; button.title = event.type;
    button.append(time, text); button.addEventListener('click', () => { setPlaying(false); seek(event.tick); }); $('events').append(button);
  }
}

/** 通用字段由主体展示；特殊观察值优先由插件转换成文字行。 */
function update() {
  if (!current) return;
  const { compiled, plugin } = current, replay = compiled.replay, sample = sampleReplay(compiled, playhead);
  view?.update(sample, { selected, follow, trails: $('trails').checked, ranges: $('ranges').checked, links: $('links').checked });
  const object = replay.objects.find(item => item.id === selected), state = sample.objects.find(item => item.id === selected);
  $('object-state').textContent = `${object.name} · ${state.active ? '在场' : '未在场'}`;
  $('coordinates').textContent = state.position.map((n, i) => `${['X', 'Y', 'Z'][i]} ${n.toFixed(2)}`).join('　');
  $('time').textContent = `${(sample.tick / replay.tickRate).toFixed(2)} s`; $('tick').textContent = `刻 ${Math.floor(sample.tick)}`;
  $('timeline').value = sample.tick; $('frame-info').textContent = `采样刻 ${compiled.frames[sample.index].tick}`;
  // 姿态和坐标随插值刷新，业务值仍来自当前已发生的样本。
  const key = `${selected}/${sample.index}`;
  if (panelKey !== key) {
    panelKey = key;
    let rows = Object.entries(state.values).map(([label, value]) => ({ label, value }));
    if (plugin.inspect && !inspectFailed) {
      try {
        const customRows = plugin.inspect({ sample: compiled.frames[sample.index].objects.find(item => item.id === selected), object, replay });
        if (!Array.isArray(customRows) || !customRows.every(row => row && typeof row.label === 'string')) throw new Error('inspect 必须返回 label/value 数组');
        rows = customRows;
      } catch (error) { inspectFailed = true; showError(`插件观察面板失败，已显示原始值：${error.message}`); }
    }
    $('inspector').replaceChildren(valueRow('在场状态', state.active ? '在场' : '未在场'),
      valueRow('目标', replay.objects.find(item => item.id === state.targetId)?.name ?? '无'), ...rows.map(row => valueRow(row.label, row.value)));
    $('raw-values').textContent = JSON.stringify(state.values, null, 2);
  }
  updateEvents(sample.tick);
}

/** 成功载入后更新整场说明与结果；checks 只展示测试进程给出的断言。 */
function updateRecordUI() {
  const replay = current.compiled.replay;
  $('scene-name').textContent = replay.name; $('scene-description').textContent = replay.description;
  $('plugin-name').textContent = `插件 ${current.plugin.id} · ${replay.frames.length.toLocaleString()} 帧 · ${replay.tickRate} 刻/秒`;
  $('empty').hidden = true; $('object').disabled = false;
  $('object').replaceChildren(...replay.objects.map(object => new Option(object.name, object.id))); $('object').value = selected;
  for (const id of ['play', 'previous', 'next', 'timeline']) $(id).disabled = false;
  $('timeline').max = current.compiled.endTick; $('duration').textContent = `/ ${(current.compiled.endTick / replay.tickRate).toFixed(2)} s`;
  $('summary').replaceChildren();
  for (const row of replay.summary) {
    const item = document.createElement('div'), label = document.createElement('span'), value = document.createElement('strong');
    item.className = 'metric'; label.textContent = `整场 · ${row.label}`; value.textContent = displayValue(row.value); item.append(label, value); $('summary').append(item);
  }
  $('checks').replaceChildren();
  if (!replay.checks.length) {
    const text = document.createElement('span'); text.className = 'muted'; text.textContent = '此记录未提供自动断言；请结合测试范围观察结果。'; $('checks').append(text);
  }
  for (const check of replay.checks) {
    const text = document.createElement('span'); text.className = `check ${check.status}`;
    text.textContent = `${{ passed: '✓ 通过', failed: '✕ 失败', info: '· 说明' }[check.status]} · ${check.name}`; text.title = check.message; $('checks').append(text);
  }
  $('scope').replaceChildren();
  for (const [title, lines] of [['接入范围', replay.coverage], ['环境与限制', replay.limitations]]) {
    const heading = document.createElement('h3'); heading.textContent = title; $('scope').append(heading);
    for (const line of lines) { const p = document.createElement('p'); p.textContent = line; $('scope').append(p); }
  }
  $('markers').replaceChildren();
  for (const event of replay.events) {
    const marker = document.createElement('i'); marker.style.left = `${current.compiled.endTick ? event.tick / current.compiled.endTick * 100 : 0}%`;
    marker.title = event.message; $('markers').append(marker);
  }
  $('follow').classList.remove('active'); $('follow').setAttribute('aria-pressed', 'false');
}

/** 数据、插件与图形工厂都成功后才替换会话；快速切换时丢弃过期请求。 */
async function loadRecord(loader, pluginId, source = '') {
  const revision = ++loadRevision; setPlaying(false);
  try {
    const data = await loader();
    if (revision !== loadRevision) return;
    const decoded = await registry.decode(data, pluginId);
    if (revision !== loadRevision) return;
    const stage = view?.prepare(decoded.compiled);
    if (stage) { view.commit(stage); showRenderError(''); }
    current = decoded; playhead = 0; selected = decoded.compiled.replay.objects[0].id; follow = false;
    panelKey = ''; eventIndex = -2; inspectFailed = false; showError('');
    $('scenario').value = source; updateRecordUI(); update();
  } catch (error) { if (revision === loadRevision) { $('scenario').value = current?.source ?? ''; showError(error.message); } }
  if (current && revision === loadRevision) current.source = $('scenario').value;
}

/** 有大小限制的 JSON 读取，手动文件与 HTTP 响应使用同一解释方式。 */
function parseRecord(text) {
  if (new TextEncoder().encode(text).length > MAX_RECORD_BYTES) throw new Error('记录超过 50 MB，请缩短场景或减少采样频率。');
  return JSON.parse(text.replace(/^\uFEFF/, ''));
}

async function selectRecord(url) {
  const entry = catalogEntries.get(url); if (!entry) return;
  await loadRecord(async () => {
    const response = await fetch(url); if (!response.ok) throw new Error(await response.text());
    return parseRecord(await response.text());
  }, entry.pluginId, url);
}

/** 动态 import 的 URL 来自本机清单。导入的回放 JSON 自身不能指定或执行模块。 */
async function refreshCatalog() {
  const revision = ++catalogRevision, initialLoadRevision = loadRevision;
  $('refresh').disabled = true;
  try {
    const response = await fetch('/api/catalog'); if (!response.ok) throw new Error('读取测试目录失败');
    const catalog = await response.json(), nextRegistry = new PluginRegistry(), entries = new Map(), warnings = [...catalog.warnings];
    const groups = [], toolLinks = [], importOptions = [new Option('导入格式：自动识别', '')];
    for (const description of catalog.plugins) {
      warnings.push(...description.warnings.map(message => `${description.name}：${message}`));
      try {
        const module = await import(description.module);
        nextRegistry.add(module.default, description.id);
        for (const tool of description.tools ?? []) {
          const link = document.createElement('a'); link.className = 'button'; link.textContent = `${tool.name} ↗`;
          link.href = tool.url; link.target = '_blank'; link.rel = 'noopener'; link.style.textDecoration = 'none'; toolLinks.push(link);
        }
        importOptions.push(new Option(`导入格式：${description.name}`, description.id));
        const group = document.createElement('optgroup'); group.label = description.name;
        for (const entry of description.cases) { group.append(new Option(entry.name, entry.url)); entries.set(entry.url, { ...entry, pluginId: description.id }); }
        groups.push(group);
      } catch (error) { warnings.push(`${description.name} 未加载：${error.message}`); }
    }
    if (revision !== catalogRevision) return;
    registry = nextRegistry; catalogEntries = entries;
    $('plugin-tools').replaceChildren(...toolLinks);
    $('scenario').replaceChildren(new Option('选择测试 / 本地导入', ''), ...groups);
    $('scenario').value = current?.source ?? '';
    const importSelection = $('import-plugin').value;
    $('import-plugin').replaceChildren(...importOptions); $('import-plugin').value = registry.has(importSelection) ? importSelection : '';
    $('warnings').replaceChildren(...warnings.map(message => { const p = document.createElement('p'); p.textContent = message; return p; }));
    $('plugin-notices').hidden = !warnings.length; $('plugin-status').textContent = `${importOptions.length - 1} 个插件可用 · ${entries.size} 个测试 · ${warnings.length} 条提示`;
    if (!current && entries.size && loadRevision === initialLoadRevision) await selectRecord(entries.keys().next().value);
  } catch (error) { if (revision === catalogRevision) showError(error.message); }
  finally { if (revision === catalogRevision) $('refresh').disabled = false; }
}

$('scenario').addEventListener('change', event => selectRecord(event.target.value));
$('refresh').addEventListener('click', refreshCatalog);
$('file').addEventListener('change', async event => {
  const file = event.target.files[0]; if (!file) return;
  const pluginId = $('import-plugin').value || undefined;
  await loadRecord(async () => {
    if (file.size > MAX_RECORD_BYTES) throw new Error('文件超过 50 MB，请拆分记录。');
    return parseRecord(await file.text());
  }, pluginId);
  event.target.value = '';
});
$('object').addEventListener('change', event => { selected = event.target.value; view?.resetFollow(); update(); });
$('play').addEventListener('click', () => { if (!current) return; if (playhead >= current.compiled.endTick) seek(0); setPlaying(!playing); });
$('previous').addEventListener('click', () => { setPlaying(false); seek(Math.ceil(playhead) - 1); });
$('next').addEventListener('click', () => { setPlaying(false); seek(Math.floor(playhead) + 1); });
$('timeline').addEventListener('input', event => { setPlaying(false); seek(Number(event.target.value)); });
for (const id of ['trails', 'ranges', 'links']) $(id).addEventListener('change', update);
for (const id of ['iso', 'top']) $(id).addEventListener('click', () => {
  $('iso').classList.toggle('active', id === 'iso'); $('top').classList.toggle('active', id === 'top'); view?.resetCamera(id);
});
$('reset').addEventListener('click', () => view?.resetCamera());
$('follow').addEventListener('click', () => {
  follow = !follow; view?.resetFollow(); $('follow').classList.toggle('active', follow); $('follow').setAttribute('aria-pressed', String(follow)); update();
});
document.addEventListener('keydown', event => {
  if (!current || /INPUT|SELECT|TEXTAREA|BUTTON|SUMMARY/.test(event.target.tagName) || event.target.isContentEditable) return;
  if (['Space', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.code)) event.preventDefault();
  if (event.code === 'Space') { if (playhead >= current.compiled.endTick) seek(0); setPlaying(!playing); }
  if (event.code === 'ArrowLeft') { setPlaying(false); seek(Math.ceil(playhead) - 1); }
  if (event.code === 'ArrowRight') { setPlaying(false); seek(Math.floor(playhead) + 1); }
  if (event.code === 'Home') { setPlaying(false); seek(0); }
  if (event.code === 'End') { setPlaying(false); seek(current.compiled.endTick); }
});

/** 真实时间只控制播放头；观察结果完全来自已导出的记录。 */
let previousTime = performance.now();
function animate(time) {
  const seconds = Math.min((time - previousTime) / 1000, 0.1); previousTime = time;
  if (playing && current) {
    playhead = Math.min(current.compiled.endTick, playhead + seconds * current.compiled.replay.tickRate * Number($('rate').value));
    if (playhead >= current.compiled.endTick) setPlaying(false); update();
  }
  view?.render(); requestAnimationFrame(animate);
}
window.addEventListener('pagehide', () => view?.dispose());
requestAnimationFrame(animate);
await refreshCatalog();
