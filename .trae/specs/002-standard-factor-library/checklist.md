# 标准因子库 - Verification Checklist

## 因子定义加载与持久化

- [ ] engine 启动时从 `stock-engine/data/factors.json` 加载因子定义
- [ ] 文件不存在时，从内置默认因子初始化并写入文件
- [ ] 文件损坏时，从内置默认因子恢复并告警
- [ ] 内存与文件始终保持一致：每次 CRUD 后立即写入文件

## 因子元数据查询

- [ ] `GET /python/v1/factors` 返回 49 个因子，分类、参数、描述与初始定义一致
- [ ] `GET /python/v1/factors?category=OVERLAP` 只返回趋势指标类因子
- [ ] `GET /python/v1/factors?source=AKQUANT` 只返回技术面因子
- [ ] `GET /python/v1/factors/{factorKey}` 返回完整的因子定义（参数、输入、输出、lookbackHint）
- [ ] `GET /python/v1/factors/categories` 返回所有因子分类
- [ ] 不存在的 factorKey 返回 404

## 因子 CRUD

- [ ] `POST /python/v1/factors` 新增因子返回 201，随后 GET 可查到
- [ ] 新增因子持久化：重启服务后仍然存在
- [ ] `PUT /python/v1/factors/{factorKey}` 修改因子返回 200，修改生效
- [ ] 修改因子持久化：重启服务后仍然生效
- [ ] `DELETE /python/v1/factors/{factorKey}` 删除因子返回 200，随后 GET 返回 404
- [ ] 删除因子持久化：重启服务后仍然删除
- [ ] factorKey 唯一约束：重复新增返回 400 + 错误码 `FACTOR_ALREADY_EXISTS`
- [ ] 修改不存在的 factorKey 返回 400 + 错误码 `FACTOR_NOT_FOUND`
- [ ] 删除不存在的 factorKey 返回 400 + 错误码 `FACTOR_NOT_FOUND`

## JSON 文件写入安全

- [ ] 原子写入：写临时文件 + rename，不会出现半写损坏
- [ ] 并发写入安全：多线程同时写，结果正确无损坏
- [ ] 写入过程中崩溃，文件要么是旧版本要么是新版本，不会损坏

## 技术面因子计算正确性

- [ ] MA 计算正确：结果与 `akquant.talib.MA` 原生调用 `np.allclose`
- [ ] EMA 计算正确：结果与 `akquant.talib.EMA` 原生调用 `np.allclose`
- [ ] MACD 三输出顺序正确：(dif, dea, hist)，与 talib 输出顺序一致
- [ ] BOLL 三输出顺序正确：(upper, mid, lower)，与因子定义 outputLabels 一致
- [ ] KDJ 三输出正确：(K, D, J)，其中 J = 3*K - 2*D
- [ ] RSI/CCI/WILLR/ADX/PLUS_DI/MINUS_DI 等单输出因子计算正确
- [ ] ATR/NATR/TRANGE 波动率因子计算正确
- [ ] OBV/AD/ADOSC 成交量因子计算正确
- [ ] STDDEV 统计因子计算正确
- [ ] SAR 抛物线指标计算正确
- [ ] WMA/DEMA/TEMA/TRIMA/KAMA/T3/MAMA/STOCHRSI/ROC/MOM/APO/PPO/TRIX 等扩展因子均可正常计算

## 价格直通与衍生因子

- [ ] OPEN/HIGH/LOW/CLOSE/VOLUME 直通因子返回值与输入对应列完全相同
- [ ] VOL_MA 结果等于对 volume 列调用 MA 的结果
- [ ] VOL_EMA 结果等于对 volume 列调用 EMA 的结果

## 多输出与 output_index

- [ ] `output_index=0` 返回第一个输出序列
- [ ] `output_index=2` 返回第三个输出序列（如 MACD hist、BOLL lower、KDJ J）
- [ ] `output_index` 越界时返回 400 错误 + 错误码 `INVALID_OUTPUT_INDEX`

## 异常处理与参数校验

- [ ] 非法 factorKey 返回 400 + 错误码 `UNKNOWN_FACTOR`
- [ ] TUSHARE 来源因子（PE_TTM、ROE_TTM 等）返回 400 + 错误码 `FACTOR_NOT_COMPUTABLE`
- [ ] 参数超出 min/max 范围返回 400 + 错误码 `INVALID_PARAM` + 具体字段提示
- [ ] 缺少必填参数返回 422（Pydantic 校验）
- [ ] 输入数据为空返回 400 + 明确错误信息
- [ ] 输入数据缺少必要列（如 close）返回 400 + 明确错误信息

## 边界条件

- [ ] 数据长度 < lookback 时，结果前补 NaN，不报错
- [ ] 只有 1 根数据时，计算 MA 等因子返回全 NaN（不崩溃）
- [ ] NaN 传播行为与 talib 一致（预热期为 NaN）
- [ ] 输入数据乱序时，内部按时间排序后计算

## 批量计算

- [ ] 单标的多因子计算：一次请求计算 5 个因子，返回结果完整
- [ ] 多标的批量计算：30 只股票 × 5 个因子，每只股票每个因子都有正确结果
- [ ] 批量计算的单因子结果与单独计算结果一致

## 性能指标

- [ ] 单标的单因子（250 根日线）平均响应时间 < 10ms
- [ ] 单标的 10 个因子（250 根日线）平均响应时间 < 50ms
- [ ] 50 只股票 × 5 个因子（250 根日线）平均响应时间 < 500ms
- [ ] 因子元数据全量查询响应时间 < 5ms
- [ ] 因子新增/修改操作响应时间 < 50ms

## Watcher 侧缓存一致性

- [ ] 首次查询因子列表后，watcher 侧 Caffeine 缓存中有数据
- [ ] 第二次查询走缓存，不调用 engine API（可用 mock 验证）
- [ ] 新增因子后，watcher 的 list 缓存和 categories 缓存被清除
- [ ] 修改因子后，watcher 的 list 缓存、detail 缓存、categories 缓存都被清除
- [ ] 删除因子后，watcher 的 list 缓存、detail 缓存、categories 缓存都被清除
- [ ] 缓存兜底 TTL 生效：5 分钟后自动过期（可加速测试验证）

## 架构与代码质量

- [ ] 四层架构清晰：定义加载（registry）→ Provider 层 → Service 层 → API 层
- [ ] Provider 模式实现：新增因子来源只需新增 Provider，不改 Service 层
- [ ] 因子定义是唯一真相源：所有元数据从 JSON 读取，不硬编码
- [ ] 代码符合 `.trae/rules/stock-engine/python/` 编码规范
- [ ] 公共类和方法有完整 docstring

## 不触库硬约束

- [ ] 因子库模块中不出现 `sqlite3`、`sqlalchemy`、`.db` 等数据库操作代码
- [ ] 所有输入数据通过 API 参数传入，无不透明依赖
- [ ] engine 不主动回调 watcher，遵循单向通信

## API 文档与接口

- [ ] `/docs` 页面可访问，所有因子相关接口都有展示
- [ ] 每个接口有清晰的 summary 和 description
- [ ] 请求/响应模型完整，字段有描述
- [ ] 接口有示例值（example）
- [ ] HTTP 状态码使用正确：200 成功、201 已创建、400 参数错误、404 不存在、422 校验失败

## 测试覆盖

- [ ] 单元测试覆盖 FactorRegistry、各 Provider、FactorCalculatorService
- [ ] 集成测试覆盖 API 路由（FastAPI TestClient）
- [ ] 测试覆盖率 > 80%
- [ ] 异常路径测试覆盖：非法输入、参数越界、数据不足等
- [ ] 边界值测试覆盖：0 根数据、1 根数据、刚好 lookback 根数据
