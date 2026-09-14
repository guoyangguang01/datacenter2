# SDNCustom - 测点方向（输入测点 / 输出测点）设计文档

> 状态：已评审待实现
> 日期：2026-09-14
> 关联：取代 `2026-08-26-sdncustom-design.md` 中关于 `writable` 字段与单向数据流的描述

## 1. 背景与目标

### 1.1 现状

当前平台的测点只有一种语义：**数据从外部经 Channel 采集进来，落库并推送**。写方向只有 `PUT /api/points/{id}/value` 这一条人工触发的路径。

```
外部设备 ──Channel适配器(读)──→ AcquisitionEngine ──→ ChangeGate ──→ Redis/TDengine/WebSocket
前端/API ──PointService.writeValue──→ Channel适配器(写)──→ 外部设备
```

### 1.2 目标

引入**测点方向**，让平台成为**跨管道的数据路由中枢**：一个管道读进来的值，可以自动写向另一个管道，实现不同协议、不同设备之间的数据流转。

- **输出测点（OUTPUT）**：数据从外部**流入**平台。绑定 Channel 用于采集读取。
- **输入测点（INPUT）**：数据从平台**流出**到外部。绑定 Channel 用于事件触发后写出，其值由所引用的输出测点驱动。

```
【输出测点 O】绑定 Channel A
  外部设备A ──(Channel A 读)──→ 平台存储

【输入测点 I】绑定 Channel B，引用 O
  O 的值变化 ──→ 事件驱动 ──→ 通过 Channel B 写出到外部设备B
                          └──→ 同时更新 I 自身的缓存/历史/推送
```

### 1.3 关键决策（已与需求方确认）

| 决策点 | 结论 |
|--------|------|
| 引用基数 | 一个 INPUT 引用**恰好一个** OUTPUT；多个 INPUT 可引用同一个 OUTPUT |
| 触发机制 | **事件驱动**——OUTPUT 通过 ChangeGate 后立即扇出，不等待下一个采集周期 |
| 独立存储 | INPUT 是完整的一等测点，有自己的 Redis 缓存与 TDengine 历史 |
| 写出失败 | **无论写出成功与否**，都用 OUTPUT 的值更新 INPUT 的缓存与历史 |
| `writable` 字段 | **删除**，由 `direction` 替代 |
| 绑定方向 | PointSource **不加方向字段**，方向由所属测点的 `direction` 推导 |
| 向后兼容 | **不要求**。存量测点显式迁移为 OUTPUT |

---

## 2. 数据模型

### 2.1 MeasurementPoint 实体

`E:\dev\workspace\learnProject\SDNCustom\sdncustom-common\src\main\java\com\sdncustom\common\model\MeasurementPoint.java`

| 字段 | 变更 | 类型 | 说明 |
|------|------|------|------|
| `writable` | **删除** | — | 由 `direction` 替代语义 |
| `direction` | **新增** | `PointDirection` | `INPUT` / `OUTPUT`，业务上必填 |
| `referencePointId` | **新增** | `String`，nullable | 仅 INPUT 使用，指向一个 OUTPUT 测点；OUTPUT 时必须为 null |

新增枚举 `com.sdncustom.common.model.enums.PointDirection`（与既有的 `ChannelDirection` 同包）：

```java
public enum PointDirection { INPUT, OUTPUT }
```

**DDL 注意事项**：

- `direction` 在 DB 层**允许 NULL**。`ddl-auto: update` 向已有数据的表添加 NOT NULL 列会失败，因此实体不标 `nullable = false`，由迁移回填保证业务完整性。
- `referencePointId` 加**索引**（`idx_point_reference`），传播时按 `referencePointId IN (...)` 批量查询。
- 旧的 `writable` 列会被 `ddl-auto: update` 遗留为孤儿列（Hibernate 不删列）。无害，不在本次处理范围内。

### 2.2 PointSource 绑定表

**无改动。** 绑定语义由所属测点的 `direction` 推导：

- OUTPUT 测点的绑定 → 采集引擎**读取**的地址
- INPUT 测点的绑定 → 传播写出时**写入**的地址

### 2.3 存量数据迁移

新增 `PointDirectionMigration`（`CommandLineRunner`，`@Order 0`）：

- 把所有 `direction IS NULL` 的测点置为 `OUTPUT`（存量测点全部是从外部采集数据的输出测点）
- 幂等：无 NULL 行时不产生 UPDATE

`@Order 0` 使它在 `BindingMigration`（@Order 1）/ `BusinessSystemMigration`（@Order 2）/ `DemoDataInitializer`（@Order 3）**之前**运行，保证后续迁移与播种看到的方向已就绪。若不需要保留存量配置，删除 `data/` 目录重启亦可（会重新播种示例数据）。

---

## 3. 传播机制

### 3.1 新增组件：`InputPointPropagator`

`com.sdncustom.server.service.InputPointPropagator`（`@Service`）

职责单一：接收一批**已通过 ChangeGate** 的输出测点变化值，扇出到所有引用它们的输入测点。

```java
/**
 * 把输出测点的有效变化传播到引用它们的输入测点。
 * 副作用：向输入测点绑定的通道写出值。
 *
 * @param outputChanges 已通过 ChangeGate 的输出测点变化值（非空）
 * @return 输入测点的新值列表，供调用方与输出测点变化一起批量落库/推送
 */
List<PointValue> propagate(List<PointValue> outputChanges);
```

实现步骤：

1. 从 `outputChanges` 提取去重的 `pointId` 集合。
2. 批量查询 `referencePointId IN (...)` 且 `direction = INPUT` 的测点（**一次查询**，不逐个查）。
3. 若结果为空 → 直接返回空列表（**零开销路径**）。
4. 对每个 INPUT 测点：
   - `pointSourceService.allBindingViews(inputPoint)` 生成绑定视图
   - 对每个绑定：跳过 Channel 为 null（已删除）/ 未 CONNECTED / `direction == READ_ONLY` 的（与既有 `writeValue` 的跳过规则一致）；取 `protocolRegistry.getOrCreate(channel)`，调用 `adapter.writePoint(view, value)`
   - 记录成功写出的通道；全部失败则记 WARN + 指标，**不抛异常**（决策：失败不影响值）
5. 构造 INPUT 测点的 `PointValue`（见 §3.3），收集返回。

### 3.2 在 AcquisitionEngine 中接入

在 `ChangeGate` 过滤之后、批量落库/推送之前接入，使传播值与其他变化**共用同一批次**：

```java
List<PointValue> publishable = changeGate.filter(values, pointsById);
// ... onlyLiveSources 过滤 ...

if (!publishable.isEmpty()) {
    List<PointValue> inputValues = inputPointPropagator.propagate(publishable);

    List<PointValue> allValues = new ArrayList<>(publishable);
    allValues.addAll(inputValues);

    pointService.updateBatch(allValues);       // Redis MSET
    historyService.saveBatch(allValues);       // TDengine 多表 INSERT
    distributionService.pushBatch(allValues);  // WebSocket 按通道合帧
}
```

要点：

- **无输出测点变化时传播逻辑零开销**——不查库、不构造对象。
- 传播值不进入 ChangeGate（见 §3.4），避免自我触发。
- 采集时序：传播写出是**同步**的（在采集线程上执行）。见 §7 风险。

### 3.3 输入测点值的构造

| 字段 | 取值 |
|------|------|
| `pointId` | INPUT 测点自身的 ID |
| `value` | 引用的 OUTPUT 测点的值 |
| `quality` | **复制** OUTPUT 测点的质量（写出失败不降级，失败另记指标） |
| `timestamp` | **沿用** OUTPUT 测点的 timestamp（保证链路因果一致、TDengine 写入时间单调） |
| `sourceChannelId` | 写出成功的通道中的第一个；全部失败时取绑定的第一个通道 |

### 3.4 回路防护

INPUT 测点**不参与采集周期**：`AcquisitionEngine` 加载通道测点时过滤 `direction = OUTPUT`，因此 INPUT 的值永远不会作为"采集来源"重新进入 ChangeGate，不存在自我触发回路。

**物理回路不在范围内**：若外部设备把 INPUT 写出的值又回灌到 OUTPUT 的同名地址，那是一个真实的物理反馈环，平台不做检测（与人体工学无关，属于现场接线问题）。

### 3.5 WebSocket 推送

INPUT 测点的推送复用现有 fan-out 逻辑：`DataWebSocketHandler.pushBatch` 通过 `PointBindingRegistry.channelsOf(pointId)` 找到 INPUT 测点绑定的通道，推给订阅了这些通道的客户端。无需改动推送层。

---

## 4. 校验规则

在创建/更新测点时（`PointService` + `PointSourceService.validateBindings` 的同类位置）执行：

| # | 规则 |
|---|------|
| 1 | `direction` 必填（`@NotNull`） |
| 2 | OUTPUT：`referencePointId` 必须为 null |
| 3 | INPUT：`referencePointId` 必填（`@NotBlank`） |
| 4 | INPUT 引用的测点必须**存在** |
| 5 | INPUT 引用的测点必须是 `direction = OUTPUT` |
| 6 | INPUT 与其引用的 OUTPUT 必须在**同一业务**下 |
| 7 | INPUT 的 Channel 绑定必须与自身同业务（现有规则延续） |
| 8 | INPUT 的 `dataType` 必须与引用的 OUTPUT **一致**（见下） |
| 9 | **防循环**：OUTPUT 不持有引用，INPUT 只引用 OUTPUT —— 由于引用方向严格单向（INPUT → OUTPUT），图结构上不可能成环。校验上只需保证规则 5 成立即可 |
| 10 | **删除保护**：删除 OUTPUT 测点前要求名下无 INPUT 引用（否则 400，提示被哪些测点引用）。与"删除业务前名下无通道/测点"的处理方式一致 |

**规则 8 的理由**：INPUT 的值直接复制自 OUTPUT 并原样写出到外部设备。若两者 `dataType` 不同（如 OUTPUT 是 FLOAT64、INPUT 是 INT16），就产生了隐式转换的歧义——截断？四舍五入？溢出怎么办？而这些转换在不同协议适配器里的行为并不一致。要求类型一致，把转换责任留给外部设备或现场配置（如 Modbus 侧按寄存器布局解读），平台不做隐式转换。

> 循环引用在模型层面被规则 5 天然排除：OUTPUT 的 `referencePointId` 恒为 null，因此不存在 OUTPUT → INPUT 的边。

### 4.1 不可变性

- `direction` **创建后不可变更**。`PUT /api/points/{id}` 静默忽略 DTO 中的 `direction`（与现有 `businessId` 的处理方式一致）。
- INPUT 的 `referencePointId` **创建后不可变更**。同样静默忽略。

> 理由：变更方向意味着测点的数据来源/去向语义完全改变，绑定的 Channel 也需要重新设计；让用户删了重建比支持原地改向更清晰。

---

## 5. API 变更

### 5.1 MeasurementPoint

| 端点 | 变更 |
|------|------|
| `POST /api/points` | body 新增 `direction`（必填）、`referencePointId`（INPUT 必填）；移除 `writable` |
| `PUT /api/points/{id}` | 同上；`direction` / `referencePointId` 静默忽略 |
| `GET /api/points` | 新增可选 `?direction=INPUT\|OUTPUT` 过滤参数，可与 `channelId` / `businessId` / 分页叠加 |
| `PUT /api/points/{id}/value` | **删除**——不再有"手动写值"概念 |
| `GET /api/points/{id}/value` | 保留。OUTPUT 读采集值；INPUT 读其缓存中的引用同步副本 |
| `POST /api/points/{id}/bindings` | 保留。OUTPUT 与 INPUT 均可追加绑定 |
| `DELETE /api/points/{id}` | 保留。删除 OUTPUT 时若被 INPUT 引用则返回 400（见 §4 规则 10） |
| `GET /api/points/{id}/history` | 保留 |

### 5.2 DTO

`MeasurementPointDTO`：

```java
@NotNull  private PointDirection direction;
          private String referencePointId;   // INPUT 必填，OUTPUT 必须为 null
// writable 字段删除
```

前端类型同步（`sdncustom-web/src/types/index.ts`）——`npm run check:types` 会比对漂移。

### 5.3 数据导入导出

`GET /api/data/export` / `POST /api/data/import`：

- 导出的测点 JSON 中 `writable` → `direction` + `referencePointId`
- 导入时校验（**整体事务性**，与现有"通道不存在"的处理一致——任何一条不合法则整批回滚，不部分成功）：
  - `direction` 缺失或非法 → 整批失败并点名该测点（**不默认补值**）
  - INPUT 的 `referencePointId` 必须能在**本次导入的测点集 ∪ 库中已有测点**里解析到，且解析结果为 `OUTPUT`
  - `dataType` 与引用测点不一致 → 整批失败并点名
  - 错误信息沿用现有格式：列出所有不合格的测点 ID 与原因，而不是只报第一条

### 5.4 服务层

| 类 | 变更 |
|----|------|
| `PointService` | 删除 `writeValue()` 及其 `WriteResult` 记录；新增 `findByReferencePointIdIn(Collection<String>)`；`updateBatch()` 不变 |
| `PointSourceService` | 无逻辑变更（绑定视图生成对两种方向一致） |
| `ChannelService` | 删除 Channel 时，其绑定的 INPUT 测点不受影响（INPUT 的绑定被删除后，传播时自然跳过——见 §3.1 步骤 4 的跳过规则） |
| `AcquisitionEngine` | 加载通道测点时过滤 `direction = OUTPUT`；批量落库前调用 `InputPointPropagator` |
| `ChangeGate` | `recordManualWrite` 随 `writeValue` 一并删除（不再有手动写值） |

---

## 6. 前端变更

### 6.1 测点列表（PointPage）

- 新增「方向」列：`Tag` 展示，输入测点（蓝色）/ 输出测点（绿色）
- 支持按方向筛选（与现有的 Channel 筛选并列）
- 移除 `writable` 相关展示
- INPUT 测点行内标注其引用的输出测点名称

### 6.2 测点创建/编辑表单（PointPage）

**方向 = 输出测点：**
1. 测点 ID / 名称 / 数据类型 / 单位 / 死区
2. 绑定 Channel（现有流程）
3. `referencePointId` 字段**不显示**

**方向 = 输入测点（顺序敏感）：**
1. 测点 ID / 名称 / 数据类型 / 单位
2. **先选 Channel**（确定写出绑定，同时作为下一步的筛选条件）
3. **再选引用的输出测点**——下拉列表**仅展示绑定了上一步所选 Channel 的 OUTPUT 测点**
4. 选中后展示引用测点的信息（名称、数据类型、当前值）作为确认
5. 未选 Channel 前，「引用测点」下拉处于禁用状态

> 该顺序来自需求方明确要求：先管道、再测点。

### 6.3 实时监控（MonitorPage）

- INPUT 测点展示时标注「引用自 {outputPointName}」
- 订阅与推送时机沿用绑定通道的逻辑，无需特殊处理

### 6.4 仪表盘（DashboardPage）

- 状态卡不变
- 测点统计增加方向维度（输入 N / 输出 M）

### 6.5 Store 与类型

- `usePointStore`：`Point` 类型加 `direction` / `referencePointId`；筛选参数加 `direction`
- `services/api.ts`：移除 `writePointValue` 调用封装

---

## 7. 风险与后续

| 风险 | 说明 | 缓解 |
|------|------|------|
| **传播写出阻塞采集周期** | 传播在采集线程上同步执行写 I/O。慢设备（如 TCP 写超时）会拖长整轮采集，影响所有通道的采集频率 | 适配器自身的 socket 超时是第一道防线。若实测有影响，迁移到专用执行器（同 `DistributionService` 的"单线程队列 + 有界缓冲"模式），代价是引入异步顺序问题 |
| **写失败静默** | 按决策，写出失败不降级值质量，仅记日志与指标 | 新增指标 `sdncustom.propagation.writes` / `propagation.failures`（tag=channel），前端状态卡可选展示 |
| **INPUT 值语义是「意图」而非「实际」** | INPUT 缓存记录的是"我们希望外部设备拥有的值"，不保证外部真的收到了 | 已在 §3.3 明确；文档与 UI 提示需保持一致 |
| **迁移遗留孤儿列** | `writable` 列不会被 `ddl-auto: update` 删除 | 无害；如介意可手工 `ALTER TABLE measurement_point DROP COLUMN writable` |

### 7.1 新增可观测性

- `sdncustom.propagation.writes`（Counter，tag=channel）——传播写出次数
- `sdncustom.propagation.failures`（Counter，tag=channel）——传播写出失败次数

---

## 8. 测试策略

### 8.1 后端（JUnit，`mvn test -pl sdncustom-server`）

新增 `InputPointPropagatorTest`：

| 用例 | 断言 |
|------|------|
| 单个 INPUT 引用 OUTPUT，OUTPUT 变化 | `propagate` 返回 1 条 INPUT 值，值/质量/时间戳复制自 OUTPUT；适配器收到 1 次 write |
| 多个 INPUT 引用同一 OUTPUT | 一次查询命中全部，各自返回 1 条值 |
| 无 INPUT 引用 | 返回空列表，**不触发任何 DB 查询** |
| 绑定的 Channel 未连接 | 跳过写出，但仍返回 INPUT 值（值照常落库） |
| 写出抛异常 | 不向上抛，仍返回 INPUT 值，失败计数 +1 |
| INPUT 的 Channel 绑定为空 | 不抛异常，返回带上绑定信息缺失的值 |

扩展 `PointServiceTest`：

| 用例 | 断言 |
|------|------|
| 创建 INPUT 但 `referencePointId` 为空 | 400 |
| 创建 INPUT 引用一个 INPUT | 400 |
| 创建 INPUT 引用跨业务的 OUTPUT | 400 |
| 创建 INPUT 引用 dataType 不同的 OUTPUT | 400 |
| 创建 OUTPUT 带 `referencePointId` | 400 |
| 更新时尝试变更 `direction` | 静默忽略，方向不变 |
| 删除 INPUT 引用的 OUTPUT 测点 | 400（提示被引用） |

扩展 `AcquisitionEngineTest`（已存在）：

| 用例 | 断言 |
|------|------|
| CONNECTED 通道绑定了 INPUT 与 OUTPUT 测点 | 采集只读 OUTPUT，INPUT 不产生读请求 |
| OUTPUT 变化触发传播 | 同一批次里 Redis/历史/推送都包含 INPUT 的值 |

`DataTransferServiceTest` 扩展：导入含 INPUT 测点的数据，引用可解析 / 不可解析两种路径。

新增 `PointDirectionMigrationTest`（参照既有 `BindingMigrationTest` 的写法）：`direction` 为 NULL 的存量测点被回填为 OUTPUT；重复运行不产生额外 UPDATE。

### 8.2 前端（无测试框架）

- `npm run build`（含 `tsc`）必须通过
- `npm run check:types` 比对 DTO/枚举漂移
- 手工验证：创建 INPUT 的表单顺序（先 Channel 后测点）、列表方向筛选、监控页引用标注

### 8.3 端到端手工验证

1. 启动 Docker 依赖 + `mock/build-and-start.bat`
2. 导入 `mock/mock-channels.json`，连接 TCP 模拟器与 Modbus 模拟器
3. 创建 OUTPUT 测点绑定 TCP 通道（MockTcpServer 会推送变化值）
4. 创建 INPUT 测点绑定 Modbus 通道，引用上一步的 OUTPUT
5. 观察：TCP 侧值变化 → Modbus 侧寄存器被写入 → 监控页两个测点同步刷新
6. 断开 Modbus 通道 → INPUT 的值仍随 OUTPUT 更新（验证决策 §1.3 的失败处理）

---

## 9. 受影响的文件清单

**后端 · common**

- `model/MeasurementPoint.java`（改）
- `enums/PointDirection.java`（新增）
- `dto/MeasurementPointDTO.java`（改）

**后端 · server**

- `config/PointDirectionMigration.java`（新增）
- `service/InputPointPropagator.java`（新增）
- `service/PointService.java`（改：删 `writeValue`，加 `findByReferencePointIdIn`）
- `service/AcquisitionEngine.java`（改：过滤 OUTPUT + 调用传播）
- `service/ChangeGate.java`（改：删 `recordManualWrite`）
- `repository/MeasurementPointRepository.java`（改：加查询方法）
- `controller/PointController.java`（改：删值写入端点，加 direction 参数）
- `service/DataTransferService.java`（改：导入导出方向字段）
- `metrics/`（新增传播指标）

**前端 · web**

- `types/index.ts`
- `stores/pointStore.ts`
- `services/api.ts`
- `pages/PointPage.tsx`
- `pages/MonitorPage.tsx`
- `pages/DashboardPage.tsx`

**文档**

- `CLAUDE.md`（核心概念、关键服务、API 端点、数据流）
- `docs/backlog.md`（如有相关遗留项）
- `mock/mock-data.json`（示例数据补 `direction` 字段）
