/** 两个对象、稀疏刻号与离散观察值，独立于 Java 构建产物。 */
export function fixture() {
  const object = (id, x) => ({ id, active: true, position: [x, 0, 0], yaw: 179, pitch: 0, roll: 0, values: { phase: 'FIRST' } });
  return {
    format: 'rvp-test-replay', schemaVersion: 1, id: 'fixture', name: '测试记录', description: '', tickRate: 20, groundY: 0,
    objects: [
      { id: 'a', name: '甲', renderer: 'box', color: '#77ddcc', size: [2, 1, 2] },
      { id: 'b', name: '乙', renderer: 'sphere', color: '#eebb88', size: [1, 1, 1] },
    ],
    obstacles: [],
    frames: [{ tick: 0, objects: [{ ...object('a', 0), targetId: 'b' }, object('b', 20)] },
      { tick: 10, objects: [{ ...object('a', 10), yaw: -179, targetId: null, values: { phase: 'SECOND' } }, object('b', 25)] }],
    events: [{ tick: 3, objectId: 'a', type: 'OBSERVE', message: '事件不必位于采样刻' }],
    summary: [{ label: '样本', value: 2 }], checks: [], coverage: [], limitations: [],
  };
}
