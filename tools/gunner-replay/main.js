import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { sampleReplay, validateReplay, phaseNames, timeLabel } from './replay.js';

/** 页面控件引用。 */
const $ = id => document.getElementById(id);
/** 播放状态；画面、轨迹和观察面板统一从 playhead 重建。 */
let replay = null, playhead = 0, playing = false, selected = '', follow = false, view = 'iso', loadRevision = 0;
/** 渲染状态与对象映射。 */
let renderer, controls, previousFollow = null, lastEventIndex = -1;
const scene = new THREE.Scene();
scene.background = new THREE.Color('#0c171e');
const camera = new THREE.PerspectiveCamera(48, 1, 0.1, 100000);
const world = new THREE.Group();
const visuals = new Map();
const light = new THREE.DirectionalLight('#cfefeb', 2.1);
light.position.set(-100, 220, 90);
scene.add(world, new THREE.HemisphereLight('#bfdcde', '#152330', 2), light);
/** 场景居中与相机复位参数。 */
let center = new THREE.Vector3(), extent = 160;
/** 全部事件仅作索引；面板按当前刻过滤。 */
let allEvents = [];

function showError(message) { $('error').textContent = message; $('error').hidden = !message; }

try {
  renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  $('canvas-host').append(renderer.domElement);
  controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.maxPolarAngle = Math.PI * 0.49;
  controls.minDistance = 8;
  new ResizeObserver(() => {
    const { width, height } = $('canvas-host').getBoundingClientRect();
    camera.aspect = width / Math.max(1, height); camera.updateProjectionMatrix();
    renderer.setSize(width, height);
  }).observe($('canvas-host'));
} catch (error) {
  showError(`三维画面未能启动：${error.message}。请使用支持 WebGL 的浏览器；仍可查看时间轴和记录。`);
}

/** 场景重载时释放 GPU 资源，避免反复导入后显存累积。 */
function clearWorld() {
  world.traverse(object => {
    object.geometry?.dispose();
    const materials = Array.isArray(object.material) ? object.material : object.material ? [object.material] : [];
    for (const material of materials) { material.map?.dispose(); material.dispose(); }
  });
  world.clear(); visuals.clear();
}

/** 名称只画到本地 canvas，不将导入内容写成 HTML。 */
function labelSprite(name) {
  const canvas = document.createElement('canvas'); canvas.width = 384; canvas.height = 72;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#11222de0'; ctx.fillRect(0, 0, 384, 72);
  ctx.font = '26px "Segoe UI", "Microsoft YaHei UI", sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillStyle = '#d2e8e5'; ctx.fillText(name, 192, 36, 360);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  const label = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, sizeAttenuation: false, depthTest: false }));
  label.scale.set(0.19, 0.036, 1); label.renderOrder = 10;
  return label;
}

/** 生成方向明确的简化载具图形，尺寸来自场景；不读取或改写游戏模型资产。 */
function buildActor(config) {
  const color = config.team === 'red' ? '#ee8d80' : config.mode !== 'scripted' ? '#7bdfc5' : '#7fbb8c';
  const root = new THREE.Group(), attitude = new THREE.Group(); root.add(attitude);
  const r = config.radius, h = config.height;
  const material = new THREE.MeshStandardMaterial({ color, roughness: 0.62, metalness: 0.12 });
  // 图形主体保持在场景碰撞盒内；长箭头只是朝向标记，不表示实体尺寸。
  const body = new THREE.Mesh(new THREE.BoxGeometry(r * (config.mode === 'fixed_wing' ? 0.5 : 1.6), h * 0.6, r * 1.7), material);
  body.position.y = h * 0.35; attitude.add(body);
  const edges = new THREE.LineSegments(new THREE.EdgesGeometry(body.geometry), new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.7 }));
  body.add(edges);
  if (config.mode === 'fixed_wing') {
    const wing = new THREE.Mesh(new THREE.BoxGeometry(r * 1.9, h * 0.12, r * 0.45), material.clone());
    wing.position.y = h * 0.6; attitude.add(wing);
    const tail = new THREE.Mesh(new THREE.BoxGeometry(r * 0.9, h * 0.12, r * 0.25), material.clone());
    tail.position.set(0, h * 0.6, -r * 0.7); attitude.add(tail);
  } else {
    const turret = new THREE.Mesh(new THREE.BoxGeometry(r, h * 0.25, r * 0.8), material.clone());
    turret.position.set(0, h * 0.74, r * 0.05); attitude.add(turret);
    const barrel = new THREE.Mesh(new THREE.CylinderGeometry(Math.min(r * 0.08, h * 0.08), Math.min(r * 0.08, h * 0.08), r, 8), material.clone());
    barrel.rotation.x = Math.PI / 2; barrel.position.set(0, h * 0.78, r * 0.45); attitude.add(barrel);
  }
  const arrow = new THREE.ArrowHelper(new THREE.Vector3(0, 0, 1), new THREE.Vector3(0, 0.25, 0), r * 4, color, r, r * 0.65);
  attitude.add(arrow);
  const label = labelSprite(config.name); label.position.set(0, h + r * 2, 0); root.add(label);
  const ring = new THREE.Mesh(new THREE.RingGeometry(r * 2, r * 2.15, 48), new THREE.MeshBasicMaterial({ color: '#e6f9f1', side: THREE.DoubleSide, transparent: true, opacity: 0.8 }));
  ring.rotation.x = -Math.PI / 2; ring.position.y = 0.1; root.add(ring);
  const rangePoints = Array.from({ length: 128 }, (_, i) => new THREE.Vector3(Math.cos(i / 128 * Math.PI * 2) * config.searchRadius, 0.15, Math.sin(i / 128 * Math.PI * 2) * config.searchRadius));
  const range = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(rangePoints), new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.22 }));
  root.add(range);
  const line = new THREE.Line(new THREE.BufferGeometry().setAttribute('position', new THREE.BufferAttribute(new Float32Array(6), 3)), new THREE.LineBasicMaterial({ color: '#ee8d80', transparent: true, opacity: 0.7 }));
  const trailPoints = new Float32Array(replay.frames.length * 3);
  for (let i = 0; i < replay.frames.length; i++) {
    const sample = replay.frames[i].actors.find(a => a.id === config.id);
    trailPoints[i * 3] = sample.position[0]; trailPoints[i * 3 + 1] = sample.position[1] - replay.scene.groundY + 0.22; trailPoints[i * 3 + 2] = sample.position[2];
  }
  const trail = new THREE.Line(new THREE.BufferGeometry().setAttribute('position', new THREE.BufferAttribute(trailPoints, 3)), new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.65 }));
  // 三角面和线条都可点击选中该对象。
  root.traverse(object => { object.userData.actorId = config.id; });
  world.add(root, line, trail); visuals.set(config.id, { root, attitude, ring, range, line, trail, config });
}

/** 依据整场范围构建地面与障碍，轨迹仍只显示到当前刻。 */
function buildWorld() {
  clearWorld();
  const bounds = new THREE.Box3();
  for (const f of replay.frames) for (const a of f.actors) if (a.active) bounds.expandByPoint(new THREE.Vector3(a.position[0], a.position[1] - replay.scene.groundY, a.position[2]));
  for (const box of replay.scene.obstacles) {
    const size = new THREE.Vector3(...box.max).sub(new THREE.Vector3(...box.min));
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(size.x, size.y, size.z), new THREE.MeshStandardMaterial({ color: '#3b5263', roughness: 0.85, metalness: 0.05 }));
    mesh.position.set((box.min[0] + box.max[0]) / 2, (box.min[1] + box.max[1]) / 2 - replay.scene.groundY, (box.min[2] + box.max[2]) / 2);
    mesh.add(new THREE.LineSegments(new THREE.EdgesGeometry(mesh.geometry), new THREE.LineBasicMaterial({ color: '#7395a5', transparent: true, opacity: 0.6 })));
    world.add(mesh); bounds.expandByObject(mesh);
  }
  bounds.getCenter(center); const size = bounds.getSize(new THREE.Vector3());
  extent = Math.max(110, size.x, size.z, size.y * 1.3);
  const ground = new THREE.Mesh(new THREE.PlaneGeometry(extent * 4, extent * 4), new THREE.MeshStandardMaterial({ color: '#10232b', roughness: 1 }));
  ground.rotation.x = -Math.PI / 2; ground.position.set(center.x, -0.1, center.z); world.add(ground);
  const grid = new THREE.GridHelper(extent * 3, 60, '#36535b', '#213942'); grid.position.set(center.x, 0, center.z);
  grid.material.transparent = true; grid.material.opacity = 0.7; world.add(grid);
  for (const config of replay.scene.actors) buildActor(config);
  resetCamera();
}

function resetCamera() {
  if (!controls) return;
  controls.target.copy(center);
  camera.position.copy(center).add(view === 'top' ? new THREE.Vector3(0, extent * 1.55, 0.01) : new THREE.Vector3(extent * 0.85, extent * 0.9, -extent * 0.95));
  camera.up.set(0, 1, 0); camera.lookAt(center); controls.update(); previousFollow = null;
}

function setPlaying(value) {
  playing = value; $('play').textContent = value ? 'Ⅱ' : '▶';
  $('play').setAttribute('aria-label', value ? '暂停' : '播放');
}

/** 所有跳转均从原始记录重新采样，倒拖不依赖上一次播放状态。 */
function seek(tick) {
  if (!replay) return;
  playhead = Math.max(0, Math.min(replay.scene.ticks, tick)); previousFollow = null; update();
}

function update() {
  if (!replay) return;
  const sample = sampleReplay(replay, playhead), byId = new Map(sample.actors.map(a => [a.id, a]));
  for (const a of sample.actors) {
    const v = visuals.get(a.id); v.root.visible = a.active;
    v.root.position.set(a.position[0], a.position[1] - replay.scene.groundY, a.position[2]);
    v.attitude.rotation.set(-THREE.MathUtils.degToRad(a.pitch), -THREE.MathUtils.degToRad(a.yaw), THREE.MathUtils.degToRad(a.bank), 'YXZ');
    v.ring.visible = a.id === selected; v.range.visible = $('vision').checked && a.id === selected;
    v.trail.visible = $('trails').checked; v.trail.geometry.setDrawRange(0, sample.index + 1);
    const target = byId.get(a.targetId);
    v.line.visible = $('target-line').checked && a.active && !!target?.active;
    if (v.line.visible) {
      const values = v.line.geometry.attributes.position;
      values.setXYZ(0, a.position[0], a.position[1] - replay.scene.groundY + v.config.height * 0.6, a.position[2]);
      values.setXYZ(1, target.position[0], target.position[1] - replay.scene.groundY + visuals.get(target.id).config.height * 0.6, target.position[2]);
      values.needsUpdate = true; v.line.geometry.computeBoundingSphere();
      v.line.material.color.set(a.fireAttempt ? '#f5d385' : '#ee8d80');
    }
  }
  const active = byId.get(selected), config = visuals.get(selected)?.config;
  if (active) {
    $('phase').textContent = active.active ? phaseNames[active.phase] ?? active.phase : '未在场';
    $('behavior').textContent = active.behavior;
    $('speed').textContent = active.speed.toFixed(1); $('yaw').textContent = (((active.yaw % 360) + 360) % 360).toFixed(1);
    $('coordinates').textContent = active.position.map((n, i) => `${['X', 'Y', 'Z'][i]} ${n.toFixed(1)}`).join('  ');
    $('target').textContent = active.targetId ? visuals.get(active.targetId).config.name : '无';
    $('ammo').textContent = config.mode === 'scripted' ? '—' : active.combatAmmo ? '有弹' : '装填中';
    $('motion').textContent = !active.active ? '未在场' : active.blocked ? '受到阻挡' : active.fireAttempt ? '模拟开火尝试' : Math.abs(active.speed) < 0.1 ? '静止' : '运动中';
    $('motion').style.color = active.blocked ? 'var(--red)' : 'var(--text)';
    for (const key of ['forward', 'backward', 'left', 'right']) $(`key-${key}`).classList.toggle('on', active.command[key]);
    const flight = config.mode === 'fixed_wing'; $('axes').hidden = !flight; $('flight-label').textContent = flight ? '公共飞控' : '';
    if (flight) $('axes').textContent = `俯仰 ${active.command.pitch.toFixed(2)}  偏航 ${active.command.yaw.toFixed(2)}\n滚转 ${active.command.roll.toFixed(2)}  油门 ${active.command.throttle.toFixed(0)}%`;
    $('diagnostics').textContent = active.diagnostics.length ? active.diagnostics.join('\n') : '本刻无附加反馈';
    if (follow && active.active && controls) {
      const position = new THREE.Vector3(...active.position); position.y -= replay.scene.groundY;
      if (previousFollow) camera.position.add(position.clone().sub(previousFollow));
      else {
        const direction = camera.position.clone().sub(controls.target).normalize();
        camera.position.copy(position).add(direction.multiplyScalar(Math.max(40, config.radius * 18)));
      }
      controls.target.copy(position); previousFollow = position;
    }
  }
  $('timeline').value = sample.tick; $('time').textContent = timeLabel(sample.tick, replay.tickRate);
  $('tick').textContent = `TICK ${String(sample.index).padStart(5, '0')}`;
  if (lastEventIndex !== sample.index) { updateEvents(sample.index); lastEventIndex = sample.index; }
}

/** 只展示当前时间以前的事件，点击回到事件发生刻。 */
function updateEvents(index) {
  const events = allEvents.filter(e => e.tick <= index);
  $('event-count').textContent = `${events.length} EVENTS`; $('events').replaceChildren();
  if (!events.length) { const text = document.createElement('p'); text.className = 'muted'; text.textContent = '当前刻暂无事件'; $('events').append(text); }
  for (const event of events.slice(-60).reverse()) {
    const button = document.createElement('button'); button.className = `event ${event.type}`;
    const time = document.createElement('time'); time.textContent = timeLabel(event.tick, replay.tickRate);
    const text = document.createElement('span');
    text.textContent = `${visuals.get(event.actorId).config.name} · ${event.type === 'PHASE' ? phaseNames[event.message] ?? event.message : event.message}`;
    button.append(time, text); button.addEventListener('click', () => { setPlaying(false); seek(event.tick); }); $('events').append(button);
  }
}

/** 数据校验成功后才替换已有回放；导入失败时原场景仍可继续操作。 */
function load(data) {
  const validated = validateReplay(data);
  setPlaying(false); replay = validated; playhead = 0; follow = false; previousFollow = null; lastEventIndex = -1;
  selected = replay.scene.actors.find(a => a.mode !== 'scripted')?.id ?? replay.scene.actors[0].id;
  allEvents = replay.frames.flatMap(f => f.events);
  $('actor').replaceChildren(...replay.scene.actors.map(a => new Option(`${a.name} · ${a.team}`, a.id)));
  $('actor').value = selected; $('actor').disabled = false; $('empty').hidden = true;
  $('scene-name').textContent = replay.scene.name; $('scene-description').textContent = replay.scene.description;
  $('record-badge').textContent = `${replay.frames.length.toLocaleString()} 帧 · SEED ${replay.scene.seed}`;
  $('timeline').max = replay.scene.ticks;
  for (const id of ['play', 'previous', 'next', 'timeline']) $(id).disabled = false;
  $('duration').textContent = `/ ${timeLabel(replay.scene.ticks, replay.tickRate)}`;
  $('summary').textContent = `全场汇总  受阻 ${replay.summary.blockedTicks} 刻 · 脱困 ${replay.summary.recoveries} 次 · 模拟开火 ${replay.summary.fireAttempts} 次`;
  $('scope').replaceChildren($('scope').querySelector('summary'));
  const scope = document.createElement('div');
  for (const [heading, lines] of [['接入的生产算法', replay.coverage], ['测试环境与限制', replay.limitations]]) {
    const title = document.createElement('h3'); title.textContent = heading; scope.append(title);
    for (const line of lines) { const p = document.createElement('p'); p.textContent = line; scope.append(p); }
  }
  $('scope').append(scope); $('markers').replaceChildren();
  for (const event of allEvents) {
    const marker = document.createElement('i'); marker.className = `marker ${event.type}`;
    marker.style.left = `${event.tick / replay.scene.ticks * 100}%`; $('markers').append(marker);
  }
  $('follow').classList.remove('active'); $('follow').setAttribute('aria-pressed', 'false');
  buildWorld(); update(); if (renderer) showError('');
}

async function selectScenario(file) {
  if (!file) return;
  const revision = ++loadRevision; setPlaying(false);
  try {
    const response = await fetch(`/replays/${encodeURIComponent(file)}`);
    if (!response.ok) throw new Error('未找到此回放，请重新运行 ./gradlew gunnerSimulation');
    const data = await response.json(); if (revision === loadRevision) load(data);
  } catch (error) { if (revision === loadRevision) showError(error.message); }
}

$('scenario').addEventListener('change', e => selectScenario(e.target.value));
$('file').addEventListener('change', async event => {
  const file = event.target.files[0]; if (!file) return;
  const revision = ++loadRevision;
  try {
    if (file.size > 50 * 1024 * 1024) throw new Error('文件超过 50 MB，请拆分场景或缩短仿真时间。');
    const data = JSON.parse(await file.text());
    if (revision === loadRevision) { load(data); $('scenario').value = ''; }
  } catch (error) { if (revision === loadRevision) showError(error.message); }
  event.target.value = '';
});
$('actor').addEventListener('change', e => { selected = e.target.value; previousFollow = null; update(); });
$('play').addEventListener('click', () => { if (playhead >= replay.scene.ticks) seek(0); setPlaying(!playing); });
$('previous').addEventListener('click', () => { setPlaying(false); seek(Math.floor(playhead) - 1); });
$('next').addEventListener('click', () => { setPlaying(false); seek(Math.floor(playhead) + 1); });
$('timeline').addEventListener('input', e => { setPlaying(false); seek(Number(e.target.value)); });
for (const id of ['trails', 'vision', 'target-line']) $(id).addEventListener('change', update);
for (const id of ['iso', 'top']) $(id).addEventListener('click', () => {
  view = id; $('iso').classList.toggle('active', id === 'iso'); $('top').classList.toggle('active', id === 'top'); resetCamera();
});
$('reset').addEventListener('click', resetCamera);
$('follow').addEventListener('click', () => { follow = !follow; previousFollow = null; $('follow').classList.toggle('active', follow); $('follow').setAttribute('aria-pressed', String(follow)); update(); });
document.addEventListener('keydown', event => {
  if (!replay || /INPUT|SELECT|TEXTAREA|BUTTON|SUMMARY/.test(event.target.tagName) || event.target.isContentEditable) return;
  if (['Space', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.code)) event.preventDefault();
  if (event.code === 'Space') { if (playhead >= replay.scene.ticks) seek(0); setPlaying(!playing); }
  if (event.code === 'ArrowLeft') { setPlaying(false); seek(Math.floor(playhead) - 1); }
  if (event.code === 'ArrowRight') { setPlaying(false); seek(Math.floor(playhead) + 1); }
  if (event.code === 'Home') { setPlaying(false); seek(0); }
  if (event.code === 'End') { setPlaying(false); seek(replay.scene.ticks); }
});

/** 点击对象切换观察；拖动相机不误触选中。 */
let pointerStart = null;
renderer?.domElement.addEventListener('pointerdown', event => { pointerStart = [event.clientX, event.clientY]; });
renderer?.domElement.addEventListener('pointerup', event => {
  if (!replay || !pointerStart || Math.hypot(event.clientX - pointerStart[0], event.clientY - pointerStart[1]) > 5) return;
  const rect = renderer.domElement.getBoundingClientRect(), ray = new THREE.Raycaster();
  ray.setFromCamera(new THREE.Vector2((event.clientX - rect.left) / rect.width * 2 - 1, 1 - (event.clientY - rect.top) / rect.height * 2), camera);
  const roots = [...visuals.values()].filter(v => v.root.visible).map(v => v.root);
  const hit = ray.intersectObjects(roots, true).find(h => h.object.userData.actorId);
  if (hit) { selected = hit.object.userData.actorId; $('actor').value = selected; previousFollow = null; update(); }
});

/** 使用真实墙钟推进播放头；切后台的超大间隔限制为 0.1 秒，防止突然跳完整场。 */
let lastTime = performance.now();
function animate(now) {
  const delta = Math.min(0.1, (now - lastTime) / 1000); lastTime = now;
  if (playing && replay) {
    playhead = Math.min(replay.scene.ticks, playhead + delta * replay.tickRate * Number($('rate').value));
    if (playhead >= replay.scene.ticks) setPlaying(false); update();
  }
  controls?.update(); if (renderer) renderer.render(scene, camera);
  requestAnimationFrame(animate);
}
requestAnimationFrame(animate);

/** 默认读取 Java 导出的索引，不生成浏览器演示轨迹。 */
try {
  const response = await fetch('/replays/index.json');
  if (!response.ok) throw new Error('尚未找到仿真记录。请先运行 ./gradlew gunnerSimulation，再刷新页面；也可直接导入回放 JSON。');
  const index = await response.json();
  if (index.schemaVersion !== 1 || !Array.isArray(index.replays) || !index.replays.length
      || !index.replays.every(e => typeof e.name === 'string' && typeof e.file === 'string' && /^[a-z0-9_-]+\.json$/.test(e.file))) throw new Error('回放索引无效，请重新生成。');
  $('scenario').replaceChildren(new Option('选择场景 / 本地导入', ''), ...index.replays.map(e => new Option(e.name, e.file)));
  const first = index.replays.find(e => e.id === '02_encounter') ?? index.replays[0];
  $('scenario').value = first.file; await selectScenario(first.file);
} catch (error) { $('scenario').replaceChildren(new Option('未找到生成的回放', '')); showError(error.message); }
