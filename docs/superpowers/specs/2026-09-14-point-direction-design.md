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
| 向后兼容 | **不要求**。不写任何启动期迁移；只支持空库（删 `sdncustom-server/data/` 重启） |
| 启动期迁移类 | 借本次一并**全部删除**（详见 §2.3） |

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

- `direction` 在 DB 层**允许 NULL**（实体不标 `nullable = false`）。**没有迁移回填**——业务完整性由**服务层**保证：所有测点创建路径（API 创建、数据导入）都必须提供 `direction`，因此空库上不会产生 NULL 行（DTO 上刻意不加 `@NotNull`，理由见 §5.2）。这也意味着**不支持在旧库上原地升级**，必须删 `sdncustom-server/data/` 重建。
- `referencePointId` 加**索引**（`idx_point_reference`），传播时按 `referencePointId IN (...)` 批量查询。
- 旧的 `writable` 列会被 `ddl-auto: update` 遗留为孤儿列（Hibernate 不删列）。因为只支持空库，重建后该列根本不存在，无需处理。

### 2.2 PointSource 绑定表

**无改动。** 绑定语义由所属测点的 `direction` 推导：

- OUTPUT 测点的绑定 → 采集引擎**读取**的地址
- INPUT 测点的绑定 → 传播写出时**写入**的地址

### 2.3 启动期迁移类清理

既然明确不支持向后兼容，现有的三个 `CommandLineRunner` 迁移/播种类**全部删除**，不再有任何启动期 DDL 或数据回填：

| 类 | 原 `@Order` | 原职责 | 删除理由 |
|----|------------|--------|---------|
| `BindingMigration` | 1 | 旧 `channel_id`/`address` 列回填 `point_source`，然后删旧列 | 纯旧模型（绑定集之前）的兼容迁移 |
| `BusinessSystemMigration` | 2 | 加 `business_id` 列并回填默认业务；库空时建 `default` 业务 | 纯业务隔离迭代的兼容迁移 |
| `DemoDataInitializer` | 3 | 空库播种 2 业务/4 通道/6 测点 | 由 `mock/mock-data.json` + `mock-channels.json` 导入替代 |

**连带改动**：

1. **删除对应测试**：`BindingMigrationTest`、`BusinessSystemMigrationTest`、`DemoDataInitializerTest`。
2. **`ImportFields.resolveBusinessId` 去掉默认回退**：删除对 `BusinessSystemMigration.DEFAULT_BUSINESS_ID` 的引用（常量随类一起消失）。导入文件**必须显式提供 `businessId`**，缺失即整批失败并点名。不再有"落默认业务 `default`"的行为，因此也不存在"`default` 业务必须存在"这一隐含前置。
3. **`mock/mock-data.json` 补 `businesses` 段与测点的 `businessId`**（原来是靠回退落 `default`）。同时补 `direction` 字段。
4. **更新实体注释**：`Channel.java:28` / `MeasurementPoint.java:28` 的 `businessId` 字段注释引用了 `BusinessSystemMigration` 来收紧非空约束，需改写为"业务约束由服务层校验保证"。

**保留不变**：`AppStartupRunner`（`HistoryService.init()` 建 TDengine 库/超级表 + 延迟 `autoConnectAll()`）不是迁移，保持原样。

**升级路径**：删 `sdncustom-server/data/` 目录后重启，`ddl-auto: update` 依实体建出全新表结构；再按 §8.3 的流程导入 mock 数据引导。

### 2.4 导入格式的兼容代码清理

`ImportFields.parseBindings` 目前除了新格式（`bindings` 数组）还解析旧格式（`channelId` + `address` + `additionalSources`）。既然不考虑向后兼容，**一并删除旧格式分支**，只接受 `bindings` 数组。`mock/mock-data.json` 已是 `bindings` 格式，不受影响。

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
| 1 | `direction` 必填。**创建路径**由 `PointDirectionValidator.validate`（API）与 `ImportFields.parsePoint`（导入）把关；**更新**静默忽略（见 §4.1、§5.2）。DTO 上不加 `@NotNull` |
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
| `PUT /api/points/{id}` | body **不必携带** `direction` / `referencePointId`——两者静默忽略（见 §5.2） |
| `GET /api/points` | 新增可选 `?direction=INPUT\|OUTPUT` 过滤参数，可与 `channelId` / `businessId` / 分页叠加 |
| `PUT /api/points/{id}/value` | **删除**——不再有"手动写值"概念 |
| `GET /api/points/{id}/value` | 保留。OUTPUT 读采集值；INPUT 读其缓存中的引用同步副本 |
| `POST /api/points/{id}/bindings` | 保留。OUTPUT 与 INPUT 均可追加绑定 |
| `DELETE /api/points/{id}` | 保留。删除 OUTPUT 时若被 INPUT 引用则返回 400（见 §4 规则 10） |
| `GET /api/points/{id}/history` | 保留 |

### 5.2 DTO

`MeasurementPointDTO`：

```java
          private PointDirection direction;  // 创建必填、更新忽略 —— 因此**不加 @NotNull**
          private String referencePointId;   // INPUT 必填，OUTPUT 必须为 null
// writable 字段删除
```

> `direction` 与 `businessId` 同规：**创建时必填、更新时静默忽略**，所以 DTO 上不能有 `@NotNull`。
> 加了它，`PointController` 的 `@Valid` 会先于 `PointService.update()` 把请求拒成 400——而前端
> 编辑测点时本就（正确地）不重传这个字段，于是"静默忽略"永远走不到、任何编辑都存不下去。
> 必填由创建路径把关：API 走 `PointDirectionValidator.validate`，导入走 `ImportFields.parsePoint`。

前端类型同步（`sdncustom-web/src/types/index.ts`）——`npm run check:types` 会比对漂移。

### 5.3 数据导入导出

`GET /api/data/export` / `POST /api/data/import`：

- 导出的测点 JSON 中 `writable` → `direction` + `referencePointId`
- **`businessId` 必填**：不再回退到默认业务（见 §2.3）。文件里没有 `businesses` 段或测点缺 `businessId` → 整批失败
- **绑定只认新格式**：`bindings` 数组；旧格式（`channelId`+`address`+`additionalSources`）分支已删除（见 §2.4）
- 其余校验（**整体事务性**，与现有"通道不存在"的处理一致——任何一条不合法则整批回滚，不部分成功）：
  - `direction` 缺失或非法 → 整批失败并点名该测点（**不默认补值**）
  - INPUT 的 `referencePointId` 必须能在**本次导入的测点集 ∪ 库中已有测点**里解析到，且解析结果为 `OUTPUT`
  - `dataType` 与引用测点不一致 → 整批失败并点名
  - 错误信息沿用现有格式：列出所有不合格的测点 ID 与原因，而不是只报第一条
    （实现分两层：`ImportFields.parsePoints` 逐条累积解析问题并点名记录，
    `DataTransferService` 的预检把它与引用问题合并成**一条**消息。注意这条路径上
    DTO 的 bean validation **不生效**——`DataTransferController` 绑的是裸 `Map`，
    字段是手工解析的，所以"必填"只能靠解析层与预检）

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
1. 测点 ID / 名称 / 数据类型 / 单位 / 死区
2. 所属通道 + 地址——INPUT **自己的写出绑定**，传播时通过它把值写到外部
3. **来源通道**：被引用的 OUTPUT 测点**所在**的通道
4. **引用的输出测点**——下拉列表**仅展示绑定了「来源通道」的 OUTPUT 测点**
5. 未选来源通道前，「引用测点」下拉处于禁用状态
6. 选中后展示引用测点的信息（名称、数据类型）作为确认

> 计划里的"**当前值**"已去掉：创建弹窗要显示当前值就得先加载候选测点的 Redis 缓存值，
> 而本特性支持的引导路径是**空库 + 导入**（`mock/mock-data.json`），那一刻候选测点还没有任何
> 缓存值可展示；为它在创建弹窗里拉一轮值查询，付出的是每次打开弹窗的额外往返与一套
> 只有首次部署才看得见的空状态。名称 + 数据类型 + 测点 ID 足以确认选对了对象，
> 当前值在测点列表与监控页随时可看。

> 该顺序来自需求方明确要求：先管道、再测点。**此处的「管道」指被引用 OUTPUT 所在的通道（来源通道），不是 INPUT 自己的写出通道**——两者通常是不同的通道，而跨管道路由正是本特性的目的（"不是指的业务系统，指的是不同管道"）。表单因此需要**两个**通道选择器，不可合并。
>
> 后端不校验引用测点是否与来源通道绑定一致（§4 的规则不含此条），来源通道纯属 UI 的检索维度；但 UI 必须提供它，否则跨管道 INPUT 无法创建。

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
| **空库无自动播种** | 删除 `DemoDataInitializer` 后，空库启动不再有示例数据，也没有默认业务。开发/演示需手工或脚本导入 | `mock/mock-channels.json` + `mock/mock-data.json` 是既定引导路径；README/CLAUDE.md 需写清两步顺序（先通道后数据） |
| **旧库无法原地升级** | 三个迁移类删除后，指向旧结构的 `sdncustom-server/data/` 库不会自动补齐（如 `business_id` 非空约束、`direction` 回填） | 明确要求删 `sdncustom-server/data/` 重建；启动文档需突出这一点 |

### 7.1 新增可观测性

- `sdncustom.propagation.writes`（Counter，tag=channel）——传播写出次数
- `sdncustom.propagation.failures`（Counter，tag=channel）——传播写出失败次数
- `sdncustom.propagation.errors`（Counter，无 tag）——`propagate()` 整体抛异常的次数。
  传播在采集线程上同步执行，异常逃出去会让**本轮（含所有通道）**的变化值永久丢失
  （变更基线已在 ChangeGate 里推进过），故必须就地吞掉并记数

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

`DataTransferServiceTest` 扩展：

| 用例 | 断言 |
|------|------|
| 导入含 INPUT 测点的数据，引用可解析 | 成功，INPUT 与 OUTPUT 均落库 |
| 导入 INPUT 引用不存在的测点 | 整批失败，点名该测点 ID |
| 导入测点缺 `businessId` | 整批失败（不再落默认业务） |
| 导入用旧格式（`channelId`+`address`） | 整批失败（旧格式分支已删） |
| 导入 INPUT 与引用测点 dataType 不一致 | 整批失败 |

**删除的测试**：`BindingMigrationTest`、`BusinessSystemMigrationTest`、`DemoDataInitializerTest`（随三个类一并删除）。

### 8.2 前端（无测试框架）

- `npm run build`（含 `tsc`）必须通过
- `npm run check:types` 比对 DTO/枚举漂移
- 手工验证：创建 INPUT 的表单顺序（先 Channel 后测点）、列表方向筛选、监控页引用标注

### 8.3 端到端手工验证

1. **删除 `sdncustom-server/data/` 目录**（本次改动只支持空库，旧库无迁移路径）。
   注意不是仓库根目录的 `data/`——H2 文件库相对于后端的工作目录（`scripts/start-backend.bat`
   会 `cd sdncustom-server`），删错路径不会报错，只会拿旧库跑完整个验证
2. 启动 Docker 依赖 + `mock/build-and-start.bat`
3. 通道管理页「导入通道配置」选 `mock/mock-channels.json`，连接 TCP 模拟器与 Modbus 模拟器
4. 测点管理页「导入数据」选 `mock/mock-data.json`（**顺序不能颠倒**——测点绑定要求通道已存在）
5. 创建 OUTPUT 测点绑定 TCP 通道（MockTcpServer 会推送变化值）
6. 创建 INPUT 测点绑定 Modbus 通道，引用上一步的 OUTPUT
7. 观察：TCP 侧值变化 → Modbus 侧寄存器被写入 → 监控页两个测点同步刷新
8. 断开 Modbus 通道 → INPUT 的值仍随 OUTPUT 更新（验证决策 §1.3 的失败处理）

---

## 9. 受影响的文件清单

**后端 · common**

- `model/MeasurementPoint.java`（改）
- `model/enums/PointDirection.java`（新增）
- `dto/MeasurementPointDTO.java`（改）

**后端 · server**

- `service/InputPointPropagator.java`（新增）
- `service/PointService.java`（改：删 `writeValue`，加 `findByReferencePointIdIn`）
- `service/AcquisitionEngine.java`（改：过滤 OUTPUT + 调用传播）
- `service/ChangeGate.java`（改：删 `recordManualWrite`）
- `repository/MeasurementPointRepository.java`（改：加查询方法）
- `controller/PointController.java`（改：删值写入端点，加 direction 参数）
- `controller/ImportFields.java`（改：删默认业务回退 + 删旧绑定格式分支）
- `service/DataTransferService.java`（改：导入导出方向字段，businessId 必填）
- `metrics/`（新增传播指标）

**后端 · 删除**

- `config/BindingMigration.java` + `test/.../BindingMigrationTest.java`
- `config/BusinessSystemMigration.java` + `test/.../BusinessSystemMigrationTest.java`
- `config/DemoDataInitializer.java` + `test/.../DemoDataInitializerTest.java`

**后端 · 注释更新**

- `common/model/Channel.java:28`、`common/model/MeasurementPoint.java:28`（`businessId` 注释里的 `BusinessSystemMigration` 引用）

**前端 · web**

- `types/index.ts`
- `stores/pointStore.ts`
- `services/api.ts`
- `pages/PointPage.tsx`
- `pages/MonitorPage.tsx`
- `pages/DashboardPage.tsx`

**文档**

- `CLAUDE.md`（核心概念、关键服务、API 端点、数据流、**启动时序**——迁移类已删、「删 data/ 即重置」的说明需重写）
- `docs/backlog.md`（如有相关遗留项）
- `mock/mock-data.json`（补 `businesses` 段、测点 `businessId` 与 `direction`）
