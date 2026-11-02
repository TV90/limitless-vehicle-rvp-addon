/** 直接读取统一格式。新增普通测试只需导出 JSON 并加入数据源索引。 */
export default {
  /** 插件接口版本和目录标识。 */
  apiVersion: 1, id: 'standard',
  /** 用明确的 format 标识判断，避免与特定业务插件抢文件。 */
  accepts: data => data?.format === 'rvp-test-replay',
  /** 主体会严格校验当前 schema；没有字段猜测或历史迁移。 */
  adapt: data => data,
};
