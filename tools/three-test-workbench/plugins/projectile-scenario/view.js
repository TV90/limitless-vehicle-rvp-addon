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
  /** 容器用于自适应；三个回调分别处理拖动、选择轨迹点、双击路线插点。 */
  constructor(host, onChange, onSelect, onInsert) {
    /** WebGL、世界、相机和轨道控制器，均在页面退出时释放。 */
    this.renderer = new THREE.WebGLRenderer({ antialias: true });
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, 2)); this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.world = new THREE.Scene(); this.world.background = new THREE.Color('#0d1920');
    this.camera = new THREE.PerspectiveCamera(48, 1, 0.1, 1e8);
    this.controls = new OrbitControls(this.camera, this.renderer.domElement); this.controls.enableDamping = true;
    this.controls.minDistance = 2;
    /** 输入副本、视角与速度显示比例；比例仅用于箭头易读，不影响导出的物理单位。 */
    this.scene = null; this.mode = 'iso'; this.dragMode = 'launch'; this.velocityScale = 1;
    /** 轨迹点下标、显示节点、折线顶点和对应绝对时刻，确保短转弯段也能准确显示和点击。 */
    this.selectedPathIndex = -1; this.pathHandles = []; this.pathVertices = []; this.pathTicks = [];
    /** 鼠标按下位置用于区分选点与旋转相机；标记大小不影响世界坐标。 */
    this.pointerOrigin = null; this.markerSize = 1;
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
    /** 选中点的橙色线框球独立于轨迹节点，拖动时不会重建 TransformControls 挂接对象。 */
    this.handles.pathPoint = new THREE.Mesh(new THREE.SphereGeometry(1, 16, 12), new THREE.MeshBasicMaterial({ color: '#ffc978', wireframe: true, depthTest: false }));
    this.handles.pathPoint.renderOrder = 2; this.content.add(this.handles.pathPoint);
    this.handles.target.userData.pathIndex = -1;
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
    /** 只把没有移动的左键松开当作选点，拖动相机与坐标轴不会误改选中点。 */
    this.onPointerDown = event => { if (event.button === 0) this.pointerOrigin = [event.clientX, event.clientY]; };
    this.onPointerUp = event => {
      if (event.button !== 0 || !this.pointerOrigin) return;
      const moved = Math.hypot(event.clientX - this.pointerOrigin[0], event.clientY - this.pointerOrigin[1]); this.pointerOrigin = null;
      if (moved > 4 || this.gizmo.dragging || this.gizmo.axis || !this.scene?.targetPath.length) return;
      // 调用统一鼠标射线换算，直接点击已有点也能选择并打开对应表单。
      const hit = this.pointerRay(event).intersectObjects([this.handles.target, ...this.pathHandles], false)[0];
      if (hit) onSelect(hit.object.userData.pathIndex);
    };
    this.onDoubleClick = event => {
      if (event.button !== 0 || !this.scene?.target || this.gizmo.dragging || this.gizmo.axis) return;
      // 沿折线投影求对应时刻，按整数刻插入；不把相机坐标误当成目标世界坐标。
      const ray = this.pointerRay(event);
      ray.params.Line.threshold = this.camera.position.distanceTo(this.controls.target) * 2 * Math.tan(THREE.MathUtils.degToRad(this.camera.fov / 2)) / Math.max(1, this.renderer.domElement.clientHeight) * 8;
      const hit = ray.intersectObject(this.path, false)[0]; if (!hit) return;
      const segment = new THREE.Line3(this.pathVertices[hit.index], this.pathVertices[hit.index + 1]);
      const fraction = segment.closestPointToPointParameter(hit.point, true);
      const atTick = Math.round(THREE.MathUtils.lerp(this.pathTicks[hit.index], this.pathTicks[hit.index + 1], fraction));
      onInsert(atTick);
    };
    this.renderer.domElement.addEventListener('pointerdown', this.onPointerDown);
    this.renderer.domElement.addEventListener('pointerup', this.onPointerUp);
    this.renderer.domElement.addEventListener('dblclick', this.onDoubleClick);
  }

  /** 将画布内指针转成世界射线；先更新矩阵，避免刚拖动后的点仍按旧位置拾取。 */
  pointerRay(event) {
    const rect = this.renderer.domElement.getBoundingClientRect(), ray = new THREE.Raycaster();
    this.world.updateMatrixWorld(true); this.camera.updateMatrixWorld(true);
    ray.setFromCamera(new THREE.Vector2((event.clientX - rect.left) / rect.width * 2 - 1, -(event.clientY - rect.top) / rect.height * 2 + 1), this.camera);
    return ray;
  }

  /** 重新计算输入几何；调用解析式采样完整目标路线，不读取任何历史预览状态。 */
  update(scene, tick = 0) {
    this.scene = scene;
    this.pathTicks = scene.target ? scene.targetPath.length ? [0, ...scene.targetPath.map(point => point.tick)] : Array.from({ length: 257 }, (_, i) => scene.ticks * i / 256) : [];
    const points = this.pathTicks.map(atTick => new THREE.Vector3(...targetAt(scene, atTick).position)); this.pathVertices = points;
    const bounds = new THREE.Box3().setFromPoints([new THREE.Vector3(...scene.position), ...points]);
    const size = bounds.getSize(new THREE.Vector3()), center = bounds.getCenter(new THREE.Vector3());
    const extent = Math.max(100, size.x, size.y * 1.4, size.z);
    // 按范围调整裁剪面，避免远距离俯视时近地轨迹与地面争夺深度而断续闪烁。
    this.camera.near = Math.max(0.1, extent / 1000); this.camera.far = Math.max(10000, extent * 50); this.camera.updateProjectionMatrix();
    this.velocityScale = Math.max(0.5, extent / 700);
    const launch = new THREE.Vector3(...scene.position), markerSize = Math.max(1, extent / 220); this.markerSize = markerSize;
    this.handles.launch.position.copy(launch); this.handles.launch.scale.setScalar(markerSize);
    for (const key of ['initialVelocity', 'targetVelocity']) {
      const origin = key === 'initialVelocity' ? scene.position : scene.target;
      const velocity = scene[key]; this.handles[key].visible = !!origin && !!velocity && !(key === 'targetVelocity' && scene.targetPath.length);
      if (origin && velocity) this.handles[key].position.fromArray(origin).addScaledVector(new THREE.Vector3(...velocity), this.velocityScale);
      this.handles[key].scale.setScalar(markerSize * 0.7);
    }
    this.handles.target.visible = !!scene.target;
    if (scene.target) this.handles.target.position.fromArray(scene.target);
    this.handles.target.scale.setScalar(markerSize);
    // 复用已有轨迹点几何，增删才分配或释放；选中线框球始终保留，拖动过程不卸载控件。
    while (this.pathHandles.length > scene.targetPath.length) {
      const marker = this.pathHandles.pop(); this.content.remove(marker); marker.geometry.dispose(); marker.material.dispose();
    }
    while (this.pathHandles.length < scene.targetPath.length) {
      const marker = new THREE.Mesh(new THREE.SphereGeometry(1, 16, 12), new THREE.MeshStandardMaterial({ color: '#e89ba6', emissive: '#42212d' }));
      marker.userData.pathIndex = this.pathHandles.length; this.pathHandles.push(marker); this.content.add(marker);
    }
    this.pathHandles.forEach((marker, index) => { marker.position.fromArray(scene.targetPath[index].position); marker.scale.setScalar(markerSize); });
    this.selectPathPoint(this.selectedPathIndex);
    this.targetNow.scale.setScalar(markerSize * 1.1); this.targetRadius.scale.setScalar(scene.targetRadius);
    this.path.geometry.dispose(); this.path.geometry = new THREE.BufferGeometry().setFromPoints(points);
    this.link.geometry.dispose(); this.link.geometry = new THREE.BufferGeometry().setFromPoints([launch, scene.target ? new THREE.Vector3(...scene.target) : launch]);
    this.link.computeLineDistances(); this.link.visible = !!scene.target;
    setArrow(this.headArrow, launch, velocityFromAngles(scene.yaw, scene.pitch, extent / 9), 1);
    setArrow(this.velocityArrow, launch, scene.initialVelocity ?? [0, 0, 0], this.velocityScale);
    if (scene.target) setArrow(this.targetArrow, new THREE.Vector3(...scene.target), targetAt(scene, 0).velocity, this.velocityScale);
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

  /** -1 选择起点；其余选择到达点，仅移动选中框和挂接坐标轴，不改场景数据。 */
  selectPathPoint(index) {
    this.selectedPathIndex = Math.max(-1, Math.min(index, (this.scene?.targetPath.length ?? 0) - 1));
    const point = this.selectedPathIndex < 0 ? this.scene?.target : this.scene?.targetPath[this.selectedPathIndex]?.position;
    this.handles.pathPoint.visible = !!point && !!this.scene?.targetPath.length;
    if (point) this.handles.pathPoint.position.fromArray(point);
    this.handles.pathPoint.scale.setScalar(this.markerSize * 1.6);
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

  /** 三种视角可适配全部初态或只聚焦目标路线，俯视和侧视都可以拖动对应平面的坐标轴。 */
  fit(mode = this.mode, targetOnly = false) {
    if (!this.center) return;
    let center = this.center, extent = this.extent;
    if (targetOnly && this.pathVertices.length) {
      const bounds = new THREE.Box3().setFromPoints(this.pathVertices), size = bounds.getSize(new THREE.Vector3());
      center = bounds.getCenter(new THREE.Vector3()); extent = Math.max(100, size.x, size.y * 1.4, size.z, this.markerSize * 12);
    }
    this.mode = mode; this.controls.target.copy(center);
    const offset = mode === 'top' ? new THREE.Vector3(0, 1.6, 0.001) : mode === 'side' ? new THREE.Vector3(1.6, 0.1, 0) : new THREE.Vector3(0.85, 0.85, -1.05);
    this.camera.position.copy(center).addScaledVector(offset, extent); this.camera.lookAt(center); this.controls.update();
  }

  /** 画布刷新与目标运动计时由页面统一驱动。 */
  render() { this.controls.update(); this.renderer.render(this.world, this.camera); }

  /** 页面退出释放 GPU 几何、材质、控件监听和尺寸观察器。 */
  dispose() {
    this.renderer.domElement.removeEventListener('pointerdown', this.onPointerDown);
    this.renderer.domElement.removeEventListener('pointerup', this.onPointerUp);
    this.renderer.domElement.removeEventListener('dblclick', this.onDoubleClick);
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
