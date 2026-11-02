import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { TransformControls } from 'three/addons/controls/TransformControls.js';
import { targetAt, velocityFromAngles } from './scenario.js';

/** 所有方向箭头都以世界坐标显示；零速度隐藏箭头，不借用机头方向。 */
function setArrow(arrow, origin, velocity, scale) {
  const direction = new THREE.Vector3(...velocity), length = direction.length() * scale;
  arrow.visible = length > 1e-8; arrow.position.copy(origin);
  if (arrow.visible) { arrow.setDirection(direction.normalize()); arrow.setLength(length, Math.min(length * 0.25, scale * 10), Math.min(length * 0.12, scale * 5)); }
}

/** 专用场景画布：只画目标解析轨迹、发射姿态和速度输入，不计算导弹飞行。 */
export class ScenarioView {
  /** 容器用于自适应；onChange 回传拖动产生的单个场景字段。 */
  constructor(host, onChange) {
    /** WebGL、世界、相机和轨道控制器，均在页面退出时释放。 */
    this.renderer = new THREE.WebGLRenderer({ antialias: true });
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, 2)); this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.world = new THREE.Scene(); this.world.background = new THREE.Color('#0d1920');
    this.camera = new THREE.PerspectiveCamera(48, 1, 0.1, 1e8);
    this.controls = new OrbitControls(this.camera, this.renderer.domElement); this.controls.enableDamping = true;
    this.controls.minDistance = 2;
    /** 输入副本、视角与速度显示比例；比例仅用于箭头易读，不影响导出的物理单位。 */
    this.scene = null; this.mode = 'iso'; this.dragMode = 'launch'; this.velocityScale = 1;
    /** 场景几何和坐标控件各自维护，避免播放时重建资源。 */
    this.content = new THREE.Group(); this.world.add(this.content);
    this.gizmo = new TransformControls(this.camera, this.renderer.domElement); this.gizmo.setSize(0.85);
    this.world.add(this.gizmo.getHelper());
    this.world.add(new THREE.HemisphereLight('#d5e8ef', '#172d3a', 2.5));
    const light = new THREE.DirectionalLight('#fff2dd', 2); light.position.set(100, 400, -100); this.world.add(light);
    /** 四个可拖动端点使用独立节点，不把目标当前时刻位置当成目标起点。 */
    this.handles = {};
    for (const [key, color] of [['launch', '#f2bd72'], ['target', '#e89ba6'], ['initialVelocity', '#76d9c2'], ['targetVelocity', '#9eb5ff']]) {
      const marker = new THREE.Mesh(new THREE.SphereGeometry(1, 16, 12), new THREE.MeshStandardMaterial({ color, roughness: 0.4 }));
      this.handles[key] = marker; this.content.add(marker);
    }
    /** 目标当前点、真实半径球、初始连线以及完整目标路线。 */
    this.targetNow = new THREE.Mesh(new THREE.SphereGeometry(1, 16, 12), new THREE.MeshStandardMaterial({ color: '#f9c6d0', emissive: '#5b222c' }));
    this.targetRadius = new THREE.Mesh(new THREE.SphereGeometry(1, 24, 16), new THREE.MeshBasicMaterial({ color: '#e89ba6', wireframe: true, transparent: true, opacity: 0.2 }));
    this.path = new THREE.Line(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({ color: '#e89ba6', transparent: true, opacity: 0.7 }));
    this.link = new THREE.Line(new THREE.BufferGeometry(), new THREE.LineDashedMaterial({ color: '#546b78', dashSize: 10, gapSize: 7 }));
    this.content.add(this.targetNow, this.targetRadius, this.path, this.link);
    /** 金色机头、青绿初速和淡蓝目标速度；颜色与页面图例一致。 */
    this.headArrow = new THREE.ArrowHelper(new THREE.Vector3(0, 0, 1), new THREE.Vector3(), 1, '#f2bd72');
    this.velocityArrow = new THREE.ArrowHelper(new THREE.Vector3(0, 0, 1), new THREE.Vector3(), 1, '#76d9c2');
    this.targetArrow = new THREE.ArrowHelper(new THREE.Vector3(0, 0, 1), new THREE.Vector3(), 1, '#9eb5ff');
    this.content.add(this.headArrow, this.velocityArrow, this.targetArrow);
    /** 地面、水面和网格按场景范围缩放，几何只创建一次。 */
    this.ground = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshStandardMaterial({ color: '#11262e', side: THREE.DoubleSide, polygonOffset: true, polygonOffsetFactor: 1, polygonOffsetUnits: 1 }));
    this.ground.rotation.x = -Math.PI / 2;
    this.water = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ color: '#3d7b92', transparent: true, opacity: 0.18, side: THREE.DoubleSide, depthWrite: false }));
    this.water.rotation.x = -Math.PI / 2;
    this.grid = new THREE.GridHelper(1, 50, '#36545d', '#223e46');
    this.axes = new THREE.AxesHelper(1); this.content.add(this.ground, this.water, this.grid, this.axes);
    this.gizmo.addEventListener('dragging-changed', event => { this.controls.enabled = !event.value; });
    this.gizmo.addEventListener('objectChange', () => {
      if (!this.scene || !this.gizmo.dragging) return;
      const handle = this.handles[this.dragMode]; if (!handle) return;
      let value = handle.position.toArray();
      if (this.dragMode === 'initialVelocity' || this.dragMode === 'targetVelocity') {
        const origin = this.dragMode === 'initialVelocity' ? this.scene.position : this.scene.target;
        value = value.map((v, i) => (v - origin[i]) / this.velocityScale);
      }
      // 拖动只更新对应输入字段；统一表单校验后再同步其余画面。
      onChange(this.dragMode === 'launch' ? 'position' : this.dragMode, value.map(v => Number(v.toFixed(6))));
    });
    host.append(this.renderer.domElement);
    /** ResizeObserver 随面板与窗口变化调整画布，不依赖固定分辨率。 */
    this.resizeObserver = new ResizeObserver(() => {
      const { width, height } = host.getBoundingClientRect(); this.camera.aspect = width / Math.max(1, height);
      this.camera.updateProjectionMatrix(); this.renderer.setSize(width, height);
    });
    this.resizeObserver.observe(host);
  }

  /** 重新计算输入几何；调用解析式采样完整目标路线，不读取任何历史预览状态。 */
  update(scene, tick = 0) {
    this.scene = scene;
    const points = scene.target ? Array.from({ length: 257 }, (_, i) => new THREE.Vector3(...targetAt(scene, scene.ticks * i / 256).position)) : [];
    const bounds = new THREE.Box3().setFromPoints([new THREE.Vector3(...scene.position), ...points]);
    const size = bounds.getSize(new THREE.Vector3()), center = bounds.getCenter(new THREE.Vector3());
    const extent = Math.max(100, size.x, size.y * 1.4, size.z);
    // 按范围调整裁剪面，避免远距离俯视时近地轨迹与地面争夺深度而断续闪烁。
    this.camera.near = Math.max(0.1, extent / 1000); this.camera.far = Math.max(10000, extent * 50); this.camera.updateProjectionMatrix();
    this.velocityScale = Math.max(0.5, extent / 700);
    const launch = new THREE.Vector3(...scene.position), markerSize = Math.max(1, extent / 220);
    this.handles.launch.position.copy(launch); this.handles.launch.scale.setScalar(markerSize);
    for (const key of ['initialVelocity', 'targetVelocity']) {
      const origin = key === 'initialVelocity' ? scene.position : scene.target;
      const velocity = scene[key]; this.handles[key].visible = !!origin && !!velocity;
      if (origin && velocity) this.handles[key].position.fromArray(origin).addScaledVector(new THREE.Vector3(...velocity), this.velocityScale);
      this.handles[key].scale.setScalar(markerSize * 0.7);
    }
    this.handles.target.visible = !!scene.target;
    if (scene.target) this.handles.target.position.fromArray(scene.target);
    this.handles.target.scale.setScalar(markerSize);
    this.targetNow.scale.setScalar(markerSize * 1.1); this.targetRadius.scale.setScalar(scene.targetRadius);
    this.path.geometry.dispose(); this.path.geometry = new THREE.BufferGeometry().setFromPoints(points);
    this.link.geometry.dispose(); this.link.geometry = new THREE.BufferGeometry().setFromPoints([launch, scene.target ? new THREE.Vector3(...scene.target) : launch]);
    this.link.computeLineDistances(); this.link.visible = !!scene.target;
    setArrow(this.headArrow, launch, velocityFromAngles(scene.yaw, scene.pitch, extent / 9), 1);
    setArrow(this.velocityArrow, launch, scene.initialVelocity ?? [0, 0, 0], this.velocityScale);
    if (scene.target) setArrow(this.targetArrow, new THREE.Vector3(...scene.target), scene.targetVelocity, this.velocityScale);
    else this.targetArrow.visible = false;
    this.ground.position.set(center.x, scene.groundY - 0.1, center.z); this.ground.scale.set(extent * 4, extent * 4, 1);
    this.grid.position.set(center.x, scene.groundY + 0.02, center.z); this.grid.scale.setScalar(extent * 3);
    this.water.visible = scene.waterY !== null;
    this.water.position.set(center.x, scene.waterY ?? scene.groundY, center.z); this.water.scale.set(extent * 4, extent * 4, 1);
    this.axes.position.set(scene.position[0], scene.groundY + 0.2, scene.position[2]); this.axes.scale.setScalar(extent / 7);
    /** 缓存适配相机所需的范围，只在主动切换视角/适配时改变相机位置。 */
    this.center = center; this.extent = extent;
    this.setDragMode(this.dragMode); this.seek(tick);
  }

  /** 端点拖动只作用于当前启用的输入，关闭目标或初速时自动卸下对应控件。 */
  setDragMode(mode) {
    this.dragMode = mode;
    const handle = this.handles[mode];
    if (handle?.visible) { if (this.gizmo.object !== handle) this.gizmo.attach(handle); }
    else this.gizmo.detach();
  }

  /** 目标播放直接代入绝对时刻；正拖、倒拖都没有累计误差。 */
  seek(tick) {
    if (!this.scene) return;
    const state = targetAt(this.scene, tick);
    this.targetNow.visible = this.targetRadius.visible = !!state;
    if (state) { this.targetNow.position.fromArray(state.position); this.targetRadius.position.fromArray(state.position); }
  }

  /** 三种视角围绕同一几何范围，俯视和侧视都可以拖动对应平面的坐标轴。 */
  fit(mode = this.mode) {
    if (!this.center) return;
    this.mode = mode; this.controls.target.copy(this.center);
    const offset = mode === 'top' ? new THREE.Vector3(0, 1.6, 0.001) : mode === 'side' ? new THREE.Vector3(1.6, 0.1, 0) : new THREE.Vector3(0.85, 0.85, -1.05);
    this.camera.position.copy(this.center).addScaledVector(offset, this.extent); this.camera.lookAt(this.center); this.controls.update();
  }

  /** 画布刷新与目标运动计时由页面统一驱动。 */
  render() { this.controls.update(); this.renderer.render(this.world, this.camera); }

  /** 页面退出释放 GPU 几何、材质、控件监听和尺寸观察器。 */
  dispose() {
    this.resizeObserver.disconnect(); this.gizmo.dispose(); this.controls.dispose();
    const resources = new Set();
    this.content.traverse(node => {
      if (node.geometry) resources.add(node.geometry);
      for (const material of Array.isArray(node.material) ? node.material : node.material ? [node.material] : []) resources.add(material);
    });
    for (const resource of resources) resource.dispose();
    this.renderer.dispose();
  }
}
