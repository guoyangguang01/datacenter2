# 测点方向（输入/输出测点）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入测点方向（INPUT/OUTPUT），使输出测点从外部采集、输入测点把所引用输出测点的变化事件驱动地写出到别的管道，并删除 `writable` 与全部启动期迁移类。

**Architecture:** 在 `MeasurementPoint` 上加 `direction` + `referencePointId`。输出测点走既有采集链路（AcquisitionEngine → ChangeGate → 落库/推送）；输入测点不参与采集，由新增的 `InputPointPropagator` 在采集周期的批量落库前接到输出测点的有效变化，扇出到引用它的输入测点，通过其绑定通道写出，并把值一并并入本轮的 Redis/历史/推送批次。

**Tech Stack:** Java 23 / Spring Boot 3.2.5 / Spring Data JPA / Micrometer / JUnit 5 + Mockito / React 18 + TypeScript + Ant Design 5 + Zustand

**Spec:** `docs/superpowers/specs/2026-09-14-point-direction-design.md`

## Global Constraints

- **JDK 23**。构建/启动一律走 `scripts/*.bat`（内含 `JAVA_HOME` 设置）。Git Bash 里 `export JAVA_HOME` 对后台进程不生效，会导致 Maven 回落到 Java 8 并报 `String.isBlank() is undefined`。
- 后端测试：`mvn test -pl sdncustom-server -am`（`-am` 连带依赖模块）。单类：`mvn test -Dtest=ClassName`。
- 前端无测试框架。验证方式只有 `npm run build`（含 `tsc`）与 `npm run check:types`（比对后端 DTO/枚举漂移）。
- 前端类型是**手写**的，改后端 DTO/枚举后必须同步 `sdncustom-web/src/types/index.ts`，否则 `check:types` 失败。
- Lombok：`@Slf4j` / `@RequiredArgsConstructor` / `@Data`。统一返回 `ApiResponse<T>`。
- **`@Transactional` 里不做远端 I/O**：订阅/退订/写设备/WS 推送一律用 `TransactionHooks.afterCommit(...)` 挪到提交之后。
- 本次**只支持空库**。验证前删掉 `data/` 目录；不写任何启动期迁移。

## ⚠️ 实现前必读：`findPointsForChannel` 有 5 个调用方

`PointSourceService.findPointsForChannel(channelId)` 语义是「该通道绑定的全部测点（任意方向）」，**绝不能在方法内部按方向过滤**。各调用方需求不同：

| 调用方 | 位置 | 需要 |
|--------|------|------|
| `AcquisitionEngine.acquireChannel` | `AcquisitionEngine.java:137` | **仅 OUTPUT**（INPUT 不采集） |
| `ChannelService.connect` → `adapter.onConnected` | `ChannelService.java:262` | **仅 OUTPUT**（订阅是读路径；INPUT 是被 publish 的目标） |
| `ChannelService.markPointsCommLost` | `ChannelService.java:344` | **仅 OUTPUT**（INPUT 的值来自传播，不该被目标通道状态标 COMM_LOST） |
| `ChannelService.delete` | `ChannelService.java:196` | **全部**（删通道要清掉所有绑定行） |
| `DataWebSocketHandler` refresh | `DataWebSocketHandler.java:139` | **全部**（INPUT 的值也要能被前端 refresh 到） |

**做法**：新增 `findOutputPointsForChannel(channelId)`，前三个调用方改用它；`findPointsForChannel` 保持原样给后两个用。

---

## Task 1: 删除启动期迁移类，导入改为强制 businessId

**Files:**
- Delete: `sdncustom-server/src/main/java/com/sdncustom/server/config/BindingMigration.java`
- Delete: `sdncustom-server/src/main/java/com/sdncustom/server/config/BusinessSystemMigration.java`
- Delete: `sdncustom-server/src/main/java/com/sdncustom/server/config/DemoDataInitializer.java`
- Delete: `sdncustom-server/src/test/java/com/sdncustom/server/config/BindingMigrationTest.java`
- Delete: `sdncustom-server/src/test/java/com/sdncustom/server/config/BusinessSystemMigrationTest.java`
- Delete: `sdncustom-server/src/test/java/com/sdncustom/server/config/DemoDataInitializerTest.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/controller/ImportFields.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/controller/DataTransferController.java:113`
- Modify: `sdncustom-common/src/main/java/com/sdncustom/common/model/Channel.java:28`
- Modify: `sdncustom-common/src/main/java/com/sdncustom/common/model/MeasurementPoint.java:28`
- Test: `sdncustom-server/src/test/java/com/sdncustom/server/controller/ImportFieldsTest.java` (新建)

**Interfaces:**
- Consumes: 无
- Produces:
  - `ImportFields.resolveBusinessId(Map)` **删除**
  - `ImportFields.optionalBoolean(Map<String,Object>, String, boolean)` **保留**（`ChannelController` 解析 `autoConnect` 仍在用，不要删）
  - `ImportFields.parseBindings(Map)` 只接受 `bindings` 数组
  - `ImportFields.requireString/optionalString/optionalDouble/parseEnum` 签名不变

- [ ] **Step 1: 写失败测试**

新建 `sdncustom-server/src/test/java/com/sdncustom/server/controller/ImportFieldsTest.java`：

```java
package com.sdncustom.server.controller;

import com.sdncustom.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportFields 导入字段解析 测试")
class ImportFieldsTest {

    @Test
    @DisplayName("测点缺 businessId：不再回退默认业务，直接报错")
    void businessIdRequired() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "pointName", "P1",
                "dataType", "FLOAT32",
                "bindings", List.of(Map.of("channelId", "ch_1", "address", "40001")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ImportFields.parsePoint(pt));

        assertTrue(ex.getMessage().contains("businessId"), ex.getMessage());
    }

    @Test
    @DisplayName("旧绑定格式（channelId+address）不再被接受")
    void legacyBindingFormatRejected() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "channelId", "ch_1",
                "address", "40001");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ImportFields.parseBindings(pt));

        assertTrue(ex.getMessage().contains("绑定"), ex.getMessage());
    }

    @Test
    @DisplayName("additionalSources 旧字段不再被接受")
    void legacyAdditionalSourcesRejected() {
        Map<String, Object> pt = Map.of(
                "channelId", "ch_1",
                "address", "40001",
                "additionalSources", List.of(Map.of("channelId", "ch_2", "address", "reg2")));

        assertThrows(BusinessException.class, () -> ImportFields.parseBindings(pt));
    }

    @Test
    @DisplayName("新格式 bindings 数组正常解析")
    void newBindingFormatParsed() {
        Map<String, Object> pt = Map.of(
                "bindings", List.of(Map.of("channelId", "ch_1", "address", "40001")));

        var bindings = ImportFields.parseBindings(pt);

        assertEquals(1, bindings.size());
        assertEquals("ch_1", bindings.get(0).getChannelId());
        assertEquals("40001", bindings.get(0).getAddress());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl sdncustom-server -am -Dtest=ImportFieldsTest`
Expected: 编译失败 — `ImportFields.parsePoint` 尚未存在（`cannot find symbol`）。这是本任务预期的红：`parsePoint` 就是为让「businessId 必填」与「旧格式拒绝」变得可测才抽出来的。

- [ ] **Step 3: 删除迁移类与测试**

```bash
git rm sdncustom-server/src/main/java/com/sdncustom/server/config/BindingMigration.java \
       sdncustom-server/src/main/java/com/sdncustom/server/config/BusinessSystemMigration.java \
       sdncustom-server/src/main/java/com/sdncustom/server/config/DemoDataInitializer.java \
       sdncustom-server/src/test/java/com/sdncustom/server/config/BindingMigrationTest.java \
       sdncustom-server/src/test/java/com/sdncustom/server/config/BusinessSystemMigrationTest.java \
       sdncustom-server/src/test/java/com/sdncustom/server/config/DemoDataInitializerTest.java
```

- [ ] **Step 4: 改 `ImportFields`**

删掉 `import com.sdncustom.server.config.BusinessSystemMigration;` 与 `resolveBusinessId` 整个方法。

`parseBindings` 改为只认新格式（同时更新 javadoc）：

```java
    /** 测点绑定：只接受 bindings=[{channelId,address},...] */
    static List<PointSourceDTO> parseBindings(Map<String, Object> pt) {
        List<PointSourceDTO> bindings = parseBindingList(pt.get("bindings"));
        if (bindings == null || bindings.isEmpty()) {
            throw new BusinessException(400, "测点缺少绑定");
        }
        return bindings;
    }
```

**把测点解析从 `DataTransferController` 搬进来**（否则「businessId 必填」这条行为没有可测的入口——控制器是私有方法 + 需要 Spring 上下文）。新增：

```java
    /**
     * 解析单个测点对象。businessId 必填——不再回退默认业务。
     */
    static MeasurementPointDTO parsePoint(Map<String, Object> pt) {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId(requireString(pt, "pointId"));
        dto.setBusinessId(requireString(pt, "businessId"));
        dto.setPointName(requireString(pt, "pointName"));
        dto.setDataType(parseEnum(PointDataType.class, pt.get("dataType"), "dataType"));
        dto.setUnit(optionalString(pt, "unit"));
        dto.setWritable(optionalBoolean(pt, "writable", false));
        dto.setDeadband(optionalDouble(pt, "deadband", null));
        dto.setBindings(parseBindings(pt));
        return dto;
    }
```

补 import：`com.sdncustom.common.dto.MeasurementPointDTO`、`com.sdncustom.common.model.enums.PointDataType`

- [ ] **Step 5: 改 `DataTransferController.parsePoints`**

把循环体里逐字段的解析整段换成：

```java
            Map<String, Object> pt = (Map<String, Object>) item;
            dtos.add(ImportFields.parsePoint(pt));
```

删掉因此不再使用的 import：`com.sdncustom.common.model.enums.PointDataType`（`@SuppressWarnings("unchecked")` 保留）。

- [ ] **Step 6: 改两处实体 javadoc**

`Channel.java:28` 与 `MeasurementPoint.java:28`，把

```java
    /** 归属业务；实体层声明可空以便 Hibernate 对存量表安全加列，非空约束由 BusinessSystemMigration 收紧 */
```

改为

```java
    /** 归属业务；业务约束由服务层校验（BusinessSystemService.requireExists），DB 层不加非空约束 */
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn test -pl sdncustom-server -am -Dtest=ImportFieldsTest,DataTransferServiceTest`
Expected: PASS

- [ ] **Step 8: 全量后端测试**

Run: `mvn test`
Expected: PASS（迁移类测试已删，不应再有其引用）

- [ ] **Step 9: 提交**

```bash
git add -A
git commit -m "refactor: drop startup migrations, require businessId on import"
```

---

## Task 2: 删除手动写值能力

**Files:**
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/controller/PointController.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/PointService.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/ChangeGate.java`
- Delete: `sdncustom-common/src/main/java/com/sdncustom/common/dto/WriteValueRequest.java`
- Modify: `sdncustom-server/src/test/java/com/sdncustom/server/service/PointServiceTest.java`
- Modify: `sdncustom-web/src/services/api.ts`
- Modify: `sdncustom-web/src/types/index.ts`

**Interfaces:**
- Consumes: 无
- Produces:
  - `PointService.WriteResult` **record 删除**
  - `PointService.writeValue(String, Object)` **删除**
  - `ChangeGate.recordManualWrite(String, Object, PointQuality, String)` **删除**；`ChangeGate` 对外只剩 `filter` / `removePoints` / `syncPointBindings`
  - `pointApi.writeValue` **删除**；前端 `WriteResult` 类型 **删除**

- [ ] **Step 1: 删后端**

`PointController.java`：删掉 `import com.sdncustom.common.dto.WriteValueRequest;`、`import com.sdncustom.common.model.PointValue;` 中仅被写值用到的部分（`PointValue` 仍被 `getValue` 用，保留）、`import org.springframework.security.core.Authentication;`（若 `getHistory` 等不再用则删），以及整个 `PUT /{id}/value` 方法。

`PointService.java`：
- 删 `writeValue(...)` 方法
- 删 `WriteResult` record
- 删 `import com.sdncustom.common.model.enums.ChannelDirection;`、`import com.sdncustom.protocol.ProtocolAdapter;`（若仅 `writeValue` 用）、`import java.util.ArrayList;`（若仅 `writeValue` 用）

`ChangeGate.java`：删 `recordManualWrite(...)` 方法。

```bash
git rm sdncustom-common/src/main/java/com/sdncustom/common/dto/WriteValueRequest.java
```

- [ ] **Step 2: 删受影响的测试**

`PointServiceTest.java` 删掉这三个测试方法（它们都调用了 `writeValue`）：
- `writeValueRecordsChangeGate`
- `writeValueBroadcastsToAllBindings`
- `writeValueAllBindingsFailThrows`

同时删掉因此不再使用的 import：`ChannelDirection`、`ProtocolAdapter`（若其它测试不再用）。

- [ ] **Step 3: 删前端调用与类型**

`sdncustom-web/src/services/api.ts`：
- 从 `import type {...}` 中移除 `WriteResult`
- 删掉 `pointApi` 里的 `writeValue:` 行

`sdncustom-web/src/types/index.ts`：删掉整个 `WriteResult` 接口及其上方注释（`// 手动写值结果：...`）。

- [ ] **Step 4: 运行后端测试**

Run: `mvn test`
Expected: PASS

- [ ] **Step 5: 运行前端类型检查与构建**

Run: `cd sdncustom-web && npm run build`
Expected: PASS（`PointPage.tsx` 尚未引用 `writeValue`，不受影响）

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "refactor: remove manual point value writing"
```

---

## Task 3: 引入 PointDirection，替换 writable

**Files:**
- Create: `sdncustom-common/src/main/java/com/sdncustom/common/model/enums/PointDirection.java`
- Modify: `sdncustom-common/src/main/java/com/sdncustom/common/model/MeasurementPoint.java`
- Modify: `sdncustom-common/src/main/java/com/sdncustom/common/dto/MeasurementPointDTO.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/PointSourceService.java` (`viewForBinding`)
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/PointService.java` (`create`/`update`)
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/controller/ImportFields.java` (`parsePoint`)
- Modify: `sdncustom-server/src/test/java/com/sdncustom/server/service/PointServiceTest.java`
- Modify: `sdncustom-server/src/test/java/com/sdncustom/server/service/AcquisitionEngineTest.java`
- Modify: `sdncustom-web/src/types/index.ts`
- Modify: `sdncustom-web/src/pages/PointPage.tsx`

**Interfaces:**
- Consumes: Task 1 的 `ImportFields`
- Produces:
  - `enum PointDirection { INPUT, OUTPUT }`（`com.sdncustom.common.model.enums`）
  - `MeasurementPoint.getDirection()/setDirection(PointDirection)`、`getReferencePointId()/setReferencePointId(String)`
  - `MeasurementPoint.isWritable()/setWritable(boolean)` **删除**
  - `MeasurementPointDTO.getDirection()/setDirection(...)`、`getReferencePointId()/setReferencePointId(...)`；`isWritable/setWritable` **删除**
  - 前端 `export type PointDirection = 'INPUT' | 'OUTPUT'`

- [ ] **Step 1: 建枚举**

`sdncustom-common/src/main/java/com/sdncustom/common/model/enums/PointDirection.java`：

```java
package com.sdncustom.common.model.enums;

/**
 * 测点数据流向：
 * OUTPUT 输出测点——数据从外部经绑定通道采集进来（原语义）；
 * INPUT  输入测点——值由所引用的输出测点驱动，经绑定通道写出到外部。
 */
public enum PointDirection {
    INPUT,
    OUTPUT
}
```

- [ ] **Step 2: 改实体**

`MeasurementPoint.java`：
- `@Table` 的 `indexes` 数组加一项：

```java
@Table(name = "measurement_point", indexes = {
        @Index(name = "idx_point_business", columnList = "business_id"),
        @Index(name = "idx_point_reference", columnList = "reference_point_id")
})
```

- 删 `writable` 字段，加两个新字段：

```java
    /** 数据流向：OUTPUT 从外部采集，INPUT 写出到外部。DB 层可空（无迁移回填），业务必填由 DTO @NotNull 保证 */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 16)
    private PointDirection direction;

    /** 仅 INPUT 使用：所引用的 OUTPUT 测点 ID；OUTPUT 时必须为 null */
    @Column(name = "reference_point_id", length = 64)
    private String referencePointId;
```

- 补 import `com.sdncustom.common.model.enums.PointDirection;`

- [ ] **Step 3: 改 DTO**

`MeasurementPointDTO.java`：删 `private boolean writable = false;`，加

```java
    @NotNull(message = "direction 不能为空")
    private PointDirection direction;

    /** 仅 INPUT 必填：所引用的 OUTPUT 测点 ID */
    private String referencePointId;
```

补 import `com.sdncustom.common.model.enums.PointDirection;`

- [ ] **Step 4: 改 `PointSourceService.viewForBinding`**

把 `view.setWritable(point.isWritable());` 换成：

```java
        view.setDirection(point.getDirection());
        view.setReferencePointId(point.getReferencePointId());
```

- [ ] **Step 5: 改 `PointService` 的 create/update**

两处的 `point.setWritable(dto.isWritable());` 都换成：

```java
        point.setDirection(dto.getDirection());
        point.setReferencePointId(dto.getReferencePointId());
```

> `update()` 里这一句在 Task 4 会被改成「忽略 DTO 的 direction/referencePointId」。本步先保持能编译。

- [ ] **Step 6: 改 `ImportFields.parsePoint`**

把 `dto.setWritable(optionalBoolean(pt, "writable", false));` 换成：

```java
        dto.setDirection(parseEnum(PointDirection.class, pt.get("direction"), "direction"));
        dto.setReferencePointId(optionalString(pt, "referencePointId"));
```

补 import `com.sdncustom.common.model.enums.PointDirection;`。

**注意**：`optionalBoolean` 仍被 `ChannelController` 解析 `autoConnect` 使用，**不要删**。

在 `ImportFieldsTest` 补一条：

```java
    @Test
    @DisplayName("测点缺 direction：整条解析失败")
    void directionRequired() {
        Map<String, Object> pt = Map.of(
                "pointId", "p1",
                "businessId", "default",
                "pointName", "P1",
                "dataType", "FLOAT32",
                "bindings", List.of(Map.of("channelId", "ch_1", "address", "40001")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ImportFields.parsePoint(pt));

        assertTrue(ex.getMessage().contains("direction"), ex.getMessage());
    }

    @Test
    @DisplayName("INPUT 测点的 referencePointId 被解析")
    void referencePointIdParsed() {
        Map<String, Object> pt = Map.of(
                "pointId", "in_1",
                "businessId", "default",
                "pointName", "IN1",
                "dataType", "FLOAT32",
                "direction", "INPUT",
                "referencePointId", "out_1",
                "bindings", List.of(Map.of("channelId", "ch_1", "address", "40001")));

        var dto = ImportFields.parsePoint(pt);

        assertEquals(PointDirection.INPUT, dto.getDirection());
        assertEquals("out_1", dto.getReferencePointId());
    }
```

补 import `com.sdncustom.common.model.enums.PointDirection;`。

- [ ] **Step 7: 修测试编译**

`PointServiceTest.java`：
- `testPoint.setWritable(false);` → `testPoint.setDirection(PointDirection.OUTPUT);`
- `testDto.setWritable(false);` → `testDto.setDirection(PointDirection.OUTPUT);`
- 补 import `com.sdncustom.common.model.enums.PointDirection;`

`AcquisitionEngineTest.java`：`point` 无 writable 赋值，仅需补 import（若编译报错则加）。

- [ ] **Step 8: 运行后端测试**

Run: `mvn test`
Expected: PASS

- [ ] **Step 9: 改前端类型**

`sdncustom-web/src/types/index.ts`：
- 加 `export type PointDirection = 'INPUT' | 'OUTPUT';`
- `MeasurementPoint` 接口：`writable: boolean;` → `direction: PointDirection;` 并加 `referencePointId?: string;`

- [ ] **Step 10: 改 `PointPage.tsx` 的字段映射**

三处 `writable` 都换掉：
- `showEditModal` 中 `writable: record.writable,` → `direction: record.direction, referencePointId: record.referencePointId,`
- `handleSubmit` 的 update/delete 两个分支中 `writable: values.writable,` → `direction: values.direction,`
- `showCreateModal` 中 `form.setFieldsValue({ channelId: filterChannel, writable: false });` → `form.setFieldsValue({ channelId: filterChannel, direction: 'OUTPUT' });`
- 表格列 `{ title: '可写', dataIndex: 'writable', ... }` → `{ title: '方向', dataIndex: 'direction', key: 'direction', render: (v: string) => <Tag color={v === 'INPUT' ? 'blue' : 'green'}>{v === 'INPUT' ? '输入' : '输出'}</Tag> }`
- 表单里的 `可写` Switch 临时改成方向下拉（Task 10 会重做整个流程）：

```tsx
              <Form.Item name="direction" label="方向" rules={[{ required: true }]}>
                <Select
                  disabled={!!editing}
                  options={[
                    { label: '输出测点（从外部采集）', value: 'OUTPUT' },
                    { label: '输入测点（写出到外部）', value: 'INPUT' },
                  ]}
                />
              </Form.Item>
```

补 `Tag` 到 antd import。

- [ ] **Step 11: 前端构建**

Run: `cd sdncustom-web && npm run build`
Expected: PASS

- [ ] **Step 12: 提交**

```bash
git add -A
git commit -m "feat: add point direction replacing writable flag"
```

---

## Task 4: 方向校验规则与删除保护

**Files:**
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/repository/MeasurementPointRepository.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/PointService.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/controller/PointController.java`
- Test: `sdncustom-server/src/test/java/com/sdncustom/server/service/PointDirectionValidationTest.java` (新建)

**Interfaces:**
- Consumes: Task 3 的 `PointDirection`、`MeasurementPoint.direction/referencePointId`
- Produces:
  - `MeasurementPointRepository.existsByReferencePointId(String)` → `boolean`
  - `PointDirectionValidator.validate(MeasurementPointDTO)` / `.validate(MeasurementPointDTO, String businessId)`
  - `GET /api/points?direction=INPUT|OUTPUT`

- [ ] **Step 1: 写失败测试**

新建 `sdncustom-server/src/test/java/com/sdncustom/server/service/PointDirectionValidationTest.java`：

```java
package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 方向校验规则的纯单元测试：只断言校验结论，不碰持久化。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("测点方向校验规则 测试")
class PointDirectionValidationTest {

    @Mock
    private MeasurementPointRepository pointRepository;

    private MeasurementPoint outputPoint;
    private PointDirectionValidator validator;

    @BeforeEach
    void setUp() {
        outputPoint = new MeasurementPoint();
        outputPoint.setPointId("out_1");
        outputPoint.setBusinessId("biz_a");
        outputPoint.setPointName("输出测点");
        outputPoint.setDataType(PointDataType.FLOAT32);
        outputPoint.setDirection(PointDirection.OUTPUT);
    }

    private MeasurementPointDTO dto(PointDirection direction, String referencePointId) {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId("in_1");
        dto.setBusinessId("biz_a");
        dto.setPointName("输入测点");
        dto.setDataType(PointDataType.FLOAT32);
        dto.setDirection(direction);
        dto.setReferencePointId(referencePointId);
        PointSourceDTO binding = new PointSourceDTO();
        binding.setChannelId("ch_1");
        binding.setAddress("40001");
        dto.setBindings(List.of(binding));
        return dto;
    }

    @Test
    @DisplayName("INPUT 引用不存在的测点 -> 400")
    void inputReferenceMissing() {
        when(pointRepository.findById("nope")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.INPUT, "nope")));
        assertTrue(ex.getMessage().contains("nope"), ex.getMessage());
    }

    @Test
    @DisplayName("INPUT 引用另一个 INPUT -> 400")
    void inputReferenceNotNull() {
        MeasurementPoint other = new MeasurementPoint();
        other.setPointId("in_2");
        other.setBusinessId("biz_a");
        other.setDataType(PointDataType.FLOAT32);
        other.setDirection(PointDirection.INPUT);
        when(pointRepository.findById("in_2")).thenReturn(Optional.of(other));

        assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.INPUT, "in_2")));
    }

    @Test
    @DisplayName("INPUT 引用跨业务 OUTPUT -> 400")
    void inputReferenceCrossBusiness() {
        outputPoint.setBusinessId("biz_b");
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(outputPoint));

        assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.INPUT, "out_1")));
    }

    @Test
    @DisplayName("INPUT 引用 dataType 不一致的 OUTPUT -> 400")
    void inputReferenceTypeMismatch() {
        outputPoint.setDataType(PointDataType.INT16);
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(outputPoint));

        assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.INPUT, "out_1")));
    }

    @Test
    @DisplayName("OUTPUT 带 referencePointId -> 400")
    void outputWithReference() {
        assertThrows(BusinessException.class,
                () -> validator.validate(dto(PointDirection.OUTPUT, "out_1")));
    }

    @Test
    @DisplayName("合法 INPUT 引用同业务同类型 OUTPUT -> 通过")
    void validInput() {
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(outputPoint));

        assertDoesNotThrow(() -> validator.validate(dto(PointDirection.INPUT, "out_1")));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -pl sdncustom-server -am -Dtest=PointDirectionValidationTest`
Expected: 编译失败 — `PointDirectionValidator` 不存在

- [ ] **Step 3: 抽校验器**

优先做**可独立测试**的单元。新建 `sdncustom-server/src/main/java/com/sdncustom/server/service/PointDirectionValidator.java`：

```java
package com.sdncustom.server.service;

import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.server.repository.MeasurementPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 测点方向校验：INPUT 必须引用一个存在、同业务、同 dataType 的 OUTPUT 测点；
 * OUTPUT 不得带引用。引用方向严格单向（INPUT -> OUTPUT），因此不可能成环。
 */
@Component
@RequiredArgsConstructor
public class PointDirectionValidator {

    private final MeasurementPointRepository pointRepository;

    /** @param businessId 测点的归属业务（update 时取库中现值，不取 DTO） */
    public void validate(MeasurementPointDTO dto) {
        validate(dto, dto.getBusinessId());
    }

    public void validate(MeasurementPointDTO dto, String businessId) {
        PointDirection direction = dto.getDirection();
        if (direction == null) {
            throw new BusinessException(400, "direction 不能为空");
        }
        if (direction == PointDirection.OUTPUT) {
            if (dto.getReferencePointId() != null && !dto.getReferencePointId().isBlank()) {
                throw new BusinessException(400, "输出测点不能引用其它测点: " + dto.getReferencePointId());
            }
            return;
        }
        // INPUT
        String referencePointId = dto.getReferencePointId();
        if (referencePointId == null || referencePointId.isBlank()) {
            throw new BusinessException(400, "输入测点必须引用一个输出测点");
        }
        MeasurementPoint target = pointRepository.findById(referencePointId).orElse(null);
        if (target == null) {
            throw new BusinessException(400, "引用的测点不存在: " + referencePointId);
        }
        if (target.getDirection() != PointDirection.OUTPUT) {
            throw new BusinessException(400, "只能引用输出测点: " + referencePointId
                    + "（当前方向为 " + target.getDirection() + "）");
        }
        if (!Objects.equals(target.getBusinessId(), businessId)) {
            throw new BusinessException(400, "引用的测点不属于当前业务: " + referencePointId);
        }
        if (target.getDataType() != dto.getDataType()) {
            throw new BusinessException(400, "引用的测点数据类型不一致: " + referencePointId
                    + "（" + target.getDataType() + " != " + dto.getDataType() + "）");
        }
    }
}
```

- [ ] **Step 4: 测试改为走真实校验器**

点 6 个测试里的 `validator` 初始化换成：

```java
    private PointDirectionValidator validator;

    @BeforeEach
    void setUp() {
        // ... outputPoint 构造保持不变 ...
        validator = new PointDirectionValidator(pointRepository);
    }
```

删掉文件里未使用的 `Channel` / `ChannelRepository` / `anyString` import。

- [ ] **Step 5: 运行确认通过**

Run: `mvn test -pl sdncustom-server -am -Dtest=PointDirectionValidationTest`
Expected: PASS（6 个用例全绿）

- [ ] **Step 6: 在 `PointService` 接入校验**

- 注入 `private final PointDirectionValidator pointDirectionValidator;`
- `create()`：在 `businessSystemService.requireExists(dto.getBusinessId());` 之后加

```java
        pointDirectionValidator.validate(dto, dto.getBusinessId());
```

- `update()`：改为**忽略** DTO 的 direction/referencePointId（方向创建后不可变），把 Task 3 加的两行 setter 从 `update()` 中删掉，改为不动这两个字段。

- [ ] **Step 7: 加删除保护**

`MeasurementPointRepository` 加：

```java
    boolean existsByReferencePointId(String referencePointId);
```

`PointService.delete()` 在 `pointRepository.findById(...)` 之后加：

```java
        if (pointRepository.existsByReferencePointId(pointId)) {
            throw new BusinessException(400, "该测点被输入测点引用，请先删除引用它的测点: " + pointId);
        }
```

- [ ] **Step 8: 加 `GET /api/points?direction=` 过滤**

`PointController.findAll` 签名加一个参数，并在返回前过滤（与既有的 `channelId` 过滤同层）：

```java
    @GetMapping
    public ApiResponse<?> findAll(@RequestParam(required = false) String channelId,
                                  @RequestParam(required = false) String businessId,
                                  @RequestParam(required = false) PointDirection direction,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        // ...现有 points 取值逻辑不变...
        if (direction != null) {
            points = points.stream().filter(p -> p.getDirection() == direction).toList();
        }
        return ApiResponse.success(PageResult.of(points, page, size));
    }
```

补 import `com.sdncustom.common.model.enums.PointDirection;`。

> Spring 会按枚举名自动转换查询参数（`?direction=INPUT`）。非法值由 `GlobalExceptionHandler` 兜底成 400。

- [ ] **Step 9: 补删除保护与不可变性的测试**

在 `PointServiceTest.java` 加：

```java
    @Test
    @DisplayName("删除被输入测点引用的输出测点 -> 400")
    void deleteReferencedPointRejected() {
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointRepository.existsByReferencePointId("test_point_001")).thenReturn(true);

        assertThrows(com.sdncustom.common.exception.BusinessException.class,
                () -> pointService.delete("test_point_001"));
        verify(pointRepository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("更新时尝试改方向：静默忽略，保持库中方向")
    void updateIgnoresDirection() {
        testPoint.setDirection(PointDirection.OUTPUT);
        when(pointRepository.findById("test_point_001")).thenReturn(Optional.of(testPoint));
        when(pointRepository.save(any(MeasurementPoint.class))).thenReturn(testPoint);
        when(pointSourceService.viewForBinding(any(), any(), any())).thenReturn(testPoint);

        testDto.setDirection(PointDirection.INPUT);
        testDto.setReferencePointId("out_1");
        pointService.update("test_point_001", testDto);

        assertEquals(PointDirection.OUTPUT, testPoint.getDirection());
        assertNull(testPoint.getReferencePointId(),
                "update 不应把 DTO 的 referencePointId 写进实体");
    }
```

- [ ] **Step 10: 运行测试**

Run: `mvn test`
Expected: PASS

- [ ] **Step 11: 提交**

```bash
git add -A
git commit -m "feat: validate point direction, guard references, filter by direction"
```

---

## Task 5: InputPointPropagator

**Files:**
- Create: `sdncustom-server/src/main/java/com/sdncustom/server/service/InputPointPropagator.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/repository/MeasurementPointRepository.java`
- Test: `sdncustom-server/src/test/java/com/sdncustom/server/service/InputPointPropagatorTest.java` (新建)

**Interfaces:**
- Consumes: Task 3 的 `PointDirection`；Task 4 的 `existsByReferencePointId`
- Produces:
  - `MeasurementPointRepository.findByReferencePointIdIn(Collection<String>)` → `List<MeasurementPoint>`
  - `InputPointPropagator.propagate(List<PointValue> outputChanges)` → `List<PointValue>`

- [ ] **Step 1: 加 repository 查询**

```java
    List<MeasurementPoint> findByReferencePointIdIn(Collection<String> referencePointIds);
```

补 import `java.util.Collection;`

- [ ] **Step 2: 写失败测试**

新建 `sdncustom-server/src/test/java/com/sdncustom/server/service/InputPointPropagatorTest.java`：

```java
package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointDirection;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.MeasurementPointRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InputPointPropagator 传播测试")
class InputPointPropagatorTest {

    @Mock
    private MeasurementPointRepository pointRepository;

    @Mock
    private PointSourceService pointSourceService;

    @Mock
    private ChannelService channelService;

    @Mock
    private ProtocolRegistry protocolRegistry;

    private SimpleMeterRegistry meterRegistry;

    private InputPointPropagator propagator;

    private MeasurementPoint inputPoint;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        propagator = new InputPointPropagator(pointRepository, pointSourceService,
                channelService, protocolRegistry, meterRegistry);

        inputPoint = new MeasurementPoint();
        inputPoint.setPointId("in_1");
        inputPoint.setBusinessId("default");
        inputPoint.setDirection(PointDirection.INPUT);
        inputPoint.setReferencePointId("out_1");
        inputPoint.setDataType(PointDataType.FLOAT32);
    }

    private PointValue outputChange(Object v) {
        PointValue pv = new PointValue();
        pv.setPointId("out_1");
        pv.setValue(v);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId("ch_src");
        pv.setTimestamp(1700000000000L);
        return pv;
    }

    private Channel connectedChannel(String id) {
        Channel ch = new Channel();
        ch.setChannelId(id);
        ch.setStatus(ChannelStatus.CONNECTED);
        ch.setDirection(ChannelDirection.READ_WRITE);
        return ch;
    }

    @Test
    @DisplayName("无输入测点引用：返回空且不查库")
    void noInputPointsMeansNoQuery() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of());

        List<PointValue> result = propagator.propagate(List.of(outputChange(25.0)));

        assertTrue(result.isEmpty());
        verifyNoInteractions(pointSourceService, protocolRegistry);
    }

    @Test
    @DisplayName("单个输入测点：写出并返回复制自输出测点的值")
    void propagatesSingleInputPoint() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of(inputPoint));
        when(pointSourceService.allBindingViews(inputPoint)).thenReturn(List.of(inputPoint));
        Channel ch = connectedChannel("ch_dst");
        when(channelService.findByIdOrNull("ch_dst")).thenReturn(ch);
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(ch)).thenReturn(adapter);

        List<PointValue> result = propagator.propagate(List.of(outputChange(25.0)));

        assertEquals(1, result.size());
        PointValue pv = result.get(0);
        assertEquals("in_1", pv.getPointId());
        assertEquals(25.0, pv.getValue());
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertEquals("ch_dst", pv.getSourceChannelId());
        assertEquals(1700000000000L, pv.getTimestamp(), "时间戳应沿用输出测点");
        verify(adapter).writePoint(inputPoint, 25.0);
        assertEquals(1.0, meterRegistry.get("sdncustom.propagation.writes")
                .tag("channel", "ch_dst").counter().count());
    }

    @Test
    @DisplayName("多个输入测点引用同一输出测点：一次查询命中全部")
    void multipleInputPointsResolvedInOneQuery() {
        MeasurementPoint second = new MeasurementPoint();
        second.setPointId("in_2");
        second.setDirection(PointDirection.INPUT);
        second.setReferencePointId("out_1");
        second.setDataType(PointDataType.FLOAT32);
        when(pointRepository.findByReferencePointIdIn(List.of("out_1")))
                .thenReturn(List.of(inputPoint, second));
        when(pointSourceService.allBindingViews(any(MeasurementPoint.class)))
                .thenReturn(List.of());  // 无绑定 -> 跳过写出
        when(pointSourceService.bindingChannelIds(anyString())).thenReturn(java.util.Set.of());

        List<PointValue> result = propagator.propagate(List.of(outputChange(1.0)));

        assertEquals(2, result.size());
        assertEquals(List.of("in_1", "in_2"), result.stream().map(PointValue::getPointId).toList());
        verify(pointRepository, times(1)).findByReferencePointIdIn(anyList());
    }

    @Test
    @DisplayName("绑定通道未连接：跳过写出但仍返回值")
    void writesSkippedButValueReturned() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of(inputPoint));
        when(pointSourceService.allBindingViews(inputPoint)).thenReturn(List.of(inputPoint));
        Channel ch = connectedChannel("ch_dst");
        ch.setStatus(ChannelStatus.DISCONNECTED);
        when(channelService.findByIdOrNull("ch_dst")).thenReturn(ch);
        when(pointSourceService.bindingChannelIds("in_1")).thenReturn(java.util.Set.of("ch_dst"));

        List<PointValue> result = propagator.propagate(List.of(outputChange(7.0)));

        assertEquals(1, result.size());
        assertEquals(7.0, result.get(0).getValue());
        assertEquals("ch_dst", result.get(0).getSourceChannelId());
        verifyNoInteractions(protocolRegistry);
    }

    @Test
    @DisplayName("只读通道：跳过写出但仍返回值")
    void readOnlyChannelSkipped() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of(inputPoint));
        when(pointSourceService.allBindingViews(inputPoint)).thenReturn(List.of(inputPoint));
        Channel ch = connectedChannel("ch_dst");
        ch.setDirection(ChannelDirection.READ_ONLY);
        when(channelService.findByIdOrNull("ch_dst")).thenReturn(ch);
        when(pointSourceService.bindingChannelIds("in_1")).thenReturn(java.util.Set.of("ch_dst"));

        List<PointValue> result = propagator.propagate(List.of(outputChange(7.0)));

        assertEquals(1, result.size());
        verifyNoInteractions(protocolRegistry);
    }

    @Test
    @DisplayName("写出抛异常：不向上抛，仍返回值且失败计数 +1")
    void writeFailureIsContained() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of(inputPoint));
        when(pointSourceService.allBindingViews(inputPoint)).thenReturn(List.of(inputPoint));
        Channel ch = connectedChannel("ch_dst");
        when(channelService.findByIdOrNull("ch_dst")).thenReturn(ch);
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(ch)).thenReturn(adapter);
        doThrow(new RuntimeException("boom")).when(adapter).writePoint(any(), any());

        List<PointValue> result = propagator.propagate(List.of(outputChange(3.0)));

        assertEquals(1, result.size());
        assertEquals(3.0, result.get(0).getValue());
        assertEquals(1.0, meterRegistry.get("sdncustom.propagation.failures")
                .tag("channel", "ch_dst").counter().count());
    }

    @Test
    @DisplayName("去重：多个输出测点变化合并成一次查询")
    void outputChangePointIdsDeduplicated() {
        when(pointRepository.findByReferencePointIdIn(List.of("out_1"))).thenReturn(List.of());

        propagator.propagate(List.of(outputChange(1.0), outputChange(2.0)));

        verify(pointRepository, times(1)).findByReferencePointIdIn(List.of("out_1"));
    }

    @Test
    @DisplayName("空输入：零开销")
    void emptyInput() {
        assertTrue(propagator.propagate(List.of()).isEmpty());
        verifyNoInteractions(pointRepository);
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn test -pl sdncustom-server -am -Dtest=InputPointPropagatorTest`
Expected: 编译失败 — `InputPointPropagator` 不存在

- [ ] **Step 4: 实现**

`sdncustom-server/src/main/java/com/sdncustom/server/service/InputPointPropagator.java`：

```java
package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.MeasurementPointRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 输入测点传播：把已通过 ChangeGate 的输出测点变化扇出到引用它们的输入测点。
 *
 * 值语义是「意图」而非「实际」——不论写出成功与否，都用输出测点的值更新输入测点，
 * 写出失败只记日志与指标，不降级值质量（见设计文档 §3.3）。
 * 输入测点不参与采集周期，因此传播值不会回流到 ChangeGate，无自我触发回路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InputPointPropagator {

    private final MeasurementPointRepository pointRepository;
    private final PointSourceService pointSourceService;
    private final ChannelService channelService;
    private final ProtocolRegistry protocolRegistry;
    private final MeterRegistry meterRegistry;

    /**
     * @param outputChanges 已通过 ChangeGate 的输出测点变化值
     * @return 输入测点的新值，供调用方与输出测点变化一起批量落库/推送
     */
    public List<PointValue> propagate(List<PointValue> outputChanges) {
        if (outputChanges == null || outputChanges.isEmpty()) {
            return List.of();
        }

        // 去重后一次查出所有引用者；无引用者时零开销返回
        Set<String> outputPointIds = new LinkedHashSet<>();
        for (PointValue pv : outputChanges) {
            outputPointIds.add(pv.getPointId());
        }
        List<MeasurementPoint> inputPoints =
                pointRepository.findByReferencePointIdIn(new ArrayList<>(outputPointIds));
        if (inputPoints.isEmpty()) {
            return List.of();
        }

        List<PointValue> result = new ArrayList<>(inputPoints.size());
        for (MeasurementPoint inputPoint : inputPoints) {
            PointValue source = findChange(outputChanges, inputPoint.getReferencePointId());
            if (source == null) {
                continue;
            }
            String writtenChannel = writeToBindings(inputPoint, source.getValue());
            result.add(toInputValue(inputPoint, source, writtenChannel));
        }
        return result;
    }

    private PointValue findChange(List<PointValue> changes, String outputPointId) {
        for (PointValue pv : changes) {
            if (pv.getPointId().equals(outputPointId)) {
                return pv;
            }
        }
        return null;
    }

    /** 广播到所有绑定通道，跳过已删除/未连接/只读的通道；返回首个写出成功的通道 */
    private String writeToBindings(MeasurementPoint inputPoint, Object value) {
        String writtenChannel = null;
        for (MeasurementPoint view : pointSourceService.allBindingViews(inputPoint)) {
            Channel channel = channelService.findByIdOrNull(view.getChannelId());
            if (channel == null) {
                log.warn("Propagation skipped: channel not found {} for point {}",
                        view.getChannelId(), inputPoint.getPointId());
                continue;
            }
            if (channel.getStatus() != ChannelStatus.CONNECTED) {
                log.warn("Propagation skipped: channel not connected {} for point {}",
                        channel.getChannelId(), inputPoint.getPointId());
                continue;
            }
            if (channel.getDirection() == ChannelDirection.READ_ONLY) {
                log.warn("Propagation skipped: channel read-only {} for point {}",
                        channel.getChannelId(), inputPoint.getPointId());
                continue;
            }
            try {
                ProtocolAdapter adapter = protocolRegistry.getOrCreate(channel);
                adapter.writePoint(view, value);
                if (writtenChannel == null) {
                    writtenChannel = channel.getChannelId();
                }
                meterRegistry.counter("sdncustom.propagation.writes",
                        "channel", channel.getChannelId()).increment();
            } catch (Exception e) {
                meterRegistry.counter("sdncustom.propagation.failures",
                        "channel", channel.getChannelId()).increment();
                log.error("Propagation write failed to channel {} for point {}: {}",
                        channel.getChannelId(), inputPoint.getPointId(), e.getMessage());
            }
        }
        return writtenChannel;
    }

    /**
     * 输入测点的新值：值/质量/时间戳复制自输出测点；来源通道取首个写出成功的通道，
     * 全部失败时退回首个绑定通道（值仍要落库，只是没能送达）。
     */
    private PointValue toInputValue(MeasurementPoint inputPoint, PointValue source, String writtenChannel) {
        PointValue pv = new PointValue();
        pv.setPointId(inputPoint.getPointId());
        pv.setValue(source.getValue());
        pv.setQuality(source.getQuality());
        pv.setTimestamp(source.getTimestamp());
        pv.setSourceChannelId(writtenChannel != null
                ? writtenChannel
                : pointSourceService.bindingChannelIds(inputPoint.getPointId()).stream().findFirst().orElse(null));
        return pv;
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn test -pl sdncustom-server -am -Dtest=InputPointPropagatorTest`
Expected: PASS（8 个用例全绿）

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat: add InputPointPropagator for input point fan-out"
```

---

## Task 6: 采集引擎接入传播，INPUT 不参与采集

**Files:**
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/PointSourceService.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/AcquisitionEngine.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/ChannelService.java:262,344`
- Test: `sdncustom-server/src/test/java/com/sdncustom/server/service/AcquisitionEngineTest.java`

**Interfaces:**
- Consumes: Task 5 的 `InputPointPropagator.propagate`
- Produces: `PointSourceService.findOutputPointsForChannel(String)` → `List<MeasurementPoint>`

- [ ] **Step 1: 写失败测试**

在 `AcquisitionEngineTest.java` 加：

```java
    @Mock
    private InputPointPropagator inputPointPropagator;
```

加测试：

```java
    @Test
    @DisplayName("输出测点变化触发传播，输入测点值并入同一批次")
    void propagationValuesJoinTheSameBatch() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointSourceService.findOutputPointsForChannel("ch_001")).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);
        List<PointValue> read = List.of(value("p1", 25.0));
        when(adapter.readPoints(anyList())).thenReturn(read);
        when(changeGate.filter(read, java.util.Map.of("p1", point))).thenReturn(read);

        PointValue inputValue = new PointValue();
        inputValue.setPointId("in_1");
        inputValue.setValue(25.0);
        inputValue.setQuality(PointQuality.GOOD);
        inputValue.setSourceChannelId("ch_002");
        inputValue.setTimestamp(System.currentTimeMillis());
        when(inputPointPropagator.propagate(read)).thenReturn(List.of(inputValue));

        engine.acquire();

        List<PointValue> expected = List.of(read.get(0), inputValue);
        verify(pointService).updateBatch(expected);
        verify(historyService).saveBatch(expected);
        verify(distributionService).pushBatch(expected);
    }

    @Test
    @DisplayName("无有效变化时不触发传播")
    void noChangesMeansNoPropagation() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointSourceService.findOutputPointsForChannel("ch_001")).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);
        List<PointValue> read = List.of(value("p1", 25.0));
        when(adapter.readPoints(anyList())).thenReturn(read);
        when(changeGate.filter(read, java.util.Map.of("p1", point))).thenReturn(List.of());

        engine.acquire();

        verifyNoInteractions(inputPointPropagator);
    }

    @Test
    @DisplayName("输入测点的来源通道掉线不影响传播值推送")
    void inputValuesBypassLivenessFilter() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointSourceService.findOutputPointsForChannel("ch_001")).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);
        List<PointValue> read = List.of(value("p1", 25.0));
        when(adapter.readPoints(anyList())).thenReturn(read);
        when(changeGate.filter(read, java.util.Map.of("p1", point))).thenReturn(read);

        // 输入测点写出目标通道 ch_dead 不在 CONNECTED 集合里
        PointValue inputValue = new PointValue();
        inputValue.setPointId("in_1");
        inputValue.setValue(25.0);
        inputValue.setQuality(PointQuality.GOOD);
        inputValue.setSourceChannelId("ch_dead");
        inputValue.setTimestamp(System.currentTimeMillis());
        when(inputPointPropagator.propagate(read)).thenReturn(List.of(inputValue));

        engine.acquire();

        ArgumentCaptor<List<PointValue>> captor = ArgumentCaptor.forClass(List.class);
        verify(distributionService).pushBatch(captor.capture());
        assertTrue(captor.getValue().stream().anyMatch(pv -> pv.getPointId().equals("in_1")),
                "输入测点值不应被来源存活过滤丢掉");
    }
```

补 import `org.mockito.ArgumentCaptor`。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -pl sdncustom-server -am -Dtest=AcquisitionEngineTest`
Expected: 编译失败 — `findOutputPointsForChannel` / `inputPointPropagator` 未定义

- [ ] **Step 3: `PointSourceService` 加输出测点查询**

```java
    /**
     * 该通道应**读取/订阅**的测点视图：只含 OUTPUT 测点。
     * INPUT 测点的值是传播写出的，不是从通道读来的，不能进采集与订阅路径。
     */
    public List<MeasurementPoint> findOutputPointsForChannel(String channelId) {
        return findPointsForChannel(channelId).stream()
                .filter(p -> p.getDirection() == PointDirection.OUTPUT)
                .toList();
    }
```

补 import `com.sdncustom.common.model.enums.PointDirection;`

在 `findPointsForChannel` 的 javadoc 上补一句警示：

```java
    /**
     * 该通道绑定的**全部**测点视图（不分方向）。删通道、WS refresh 需要全量；
     * 采集与订阅路径请改用 {@link #findOutputPointsForChannel(String)}。
     * ...
     */
```

- [ ] **Step 4: 改 `ChannelService` 的两个读路径**

`ChannelService.java:262`：

```java
                adapter.onConnected(pointSourceService.findOutputPointsForChannel(channelId));
```

`ChannelService.java:344`（`markPointsCommLost`）：

```java
            List<MeasurementPoint> boundPoints = pointSourceService.findOutputPointsForChannel(channelId);
```

> **决策点（需 review）**：`markPointsCommLost` 只标 OUTPUT。理由：INPUT 的值由传播驱动、与目标通道能否送达无关（设计文档 §1.3 决策 A），若一并标 COMM_LOST 会在下一轮传播中被 GOOD 覆盖，产生无意义的抖动。`ChannelService.delete`（第 196 行）**保持 `findPointsForChannel`**，删通道必须清掉所有方向的绑定行。

- [ ] **Step 5: 改 `AcquisitionEngine`**

- 注入 `private final InputPointPropagator inputPointPropagator;`
- 删掉**死字段** `private final MeasurementPointRepository pointRepository;` 及其 import（全类无引用）
- `acquireChannel` 第 137 行改用 `findOutputPointsForChannel`
- `acquire()` 中「无有效变化则零写入」分支改为：

```java
            if (!changedValues.isEmpty()) {
                // 读取期间用户可能已断开通道：断开来源的迟到值不写缓存/历史
                List<PointValue> publishable = onlyLiveSources(changedValues);
                if (!publishable.isEmpty()) {
                    meterRegistry.counter("sdncustom.acquisition.changed.values")
                            .increment(publishable.size());

                    // 输入测点传播：同步写出到各自绑定通道，值并入本轮批次
                    List<PointValue> inputValues = inputPointPropagator.propagate(publishable);

                    List<PointValue> allValues = new ArrayList<>(publishable);
                    allValues.addAll(inputValues);

                    pointService.updateBatch(allValues);
                    historyService.saveBatch(allValues);

                    // 推送前再复核输出测点的来源存活（落库可能很慢，期间用户可能断开）。
                    // 输入测点值不过这道滤网：它们的来源通道是**写出目标**，
                    // 目标掉线只代表没送达，不代表这个值本身失效（决策 A）。
                    List<PointValue> pushable = new ArrayList<>(onlyLiveSources(publishable));
                    pushable.addAll(inputValues);
                    if (!pushable.isEmpty()) {
                        distributionService.pushBatch(pushable);
                    }
                }
            }
```

补 import `java.util.ArrayList;`

- [ ] **Step 6: 运行测试**

Run: `mvn test`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat: propagate output changes to input points in acquisition cycle"
```

---

## Task 7: 数据导入导出适配方向字段

**Files:**
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/DataTransferService.java`
- Test: `sdncustom-server/src/test/java/com/sdncustom/server/service/DataTransferServiceTest.java`

**Interfaces:**
- Consumes: Task 4 的 `PointDirectionValidator`；Task 5 的 `findByReferencePointIdIn`
- Produces: `DataTransferService.validateReferencesExist(List<MeasurementPointDTO>, List<MeasurementPointDTO>)`（private）

- [ ] **Step 1: 写失败测试**

在 `DataTransferServiceTest.java` 的 `point(...)` 辅助方法里补 `dto.setDirection(PointDirection.OUTPUT);`，并加：

```java
    @Test
    @DisplayName("导入 INPUT 引用不存在的测点：整批失败并点名")
    void failsWhenReferenceMissing() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        when(pointRepository.findById("out_missing")).thenReturn(Optional.empty());

        MeasurementPointDTO input = point("in_1", "biz", "ch_1");
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_missing");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                dataTransferService.importData(List.of(business("biz")), List.of(input)));

        assertTrue(ex.getMessage().contains("out_missing"), ex.getMessage());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("导入 INPUT 引用本次导入内的 OUTPUT：解析成功")
    void resolvesReferenceWithinSamePayload() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        when(businessSystemService.exists("biz")).thenReturn(false);

        MeasurementPointDTO output = point("out_1", "biz", "ch_1");
        MeasurementPointDTO input = point("in_1", "biz", "ch_1");
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_1");

        DataTransferService.ImportResult result = dataTransferService.importData(
                List.of(business("biz")), List.of(output, input));

        assertEquals(2, result.pointCount());
        verify(pointService).importPoints(anyList());
    }

    @Test
    @DisplayName("导入 INPUT 引用 dataType 不一致的 OUTPUT：整批失败")
    void failsOnDataTypeMismatch() {
        when(channelRepository.findById("ch_1")).thenReturn(Optional.of(new Channel()));
        MeasurementPoint out = new MeasurementPoint();
        out.setPointId("out_1");
        out.setBusinessId("biz");
        out.setDataType(PointDataType.INT16);
        out.setDirection(PointDirection.OUTPUT);
        when(pointRepository.findById("out_1")).thenReturn(Optional.of(out));

        MeasurementPointDTO input = point("in_1", "biz", "ch_1");   // FLOAT32
        input.setDirection(PointDirection.INPUT);
        input.setReferencePointId("out_1");

        assertThrows(BusinessException.class, () ->
                dataTransferService.importData(List.of(business("biz")), List.of(input)));
        verifyNoInteractions(pointService);
    }
```

补 `@Mock private MeasurementPointRepository pointRepository;` 及相应 import。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -pl sdncustom-server -am -Dtest=DataTransferServiceTest`
Expected: 编译失败 — 构造器缺 `pointRepository` 参数 / 断言失败

- [ ] **Step 3: 实现引用预检**

`DataTransferService`：
- 注入 `private final MeasurementPointRepository pointRepository;` 与 `private final PointDirectionValidator pointDirectionValidator;`
- `importData` 在 `validateChannelsExist(points);` 之后加一行：

```java
        validateReferencesExist(points);
```

- 新方法：

```java
    /**
     * 引用预检：INPUT 的 referencePointId 必须能在「本次导入的测点集 ∪ 库中已有测点」里
     * 解析到且为 OUTPUT，并且 dataType 一致。与通道预检一样在任何写库之前完成。
     */
    private void validateReferencesExist(List<MeasurementPointDTO> points) {
        java.util.Set<String> inPayload = new java.util.HashSet<>();
        for (MeasurementPointDTO dto : points) {
            inPayload.add(dto.getPointId());
        }
        List<String> problems = new ArrayList<>();
        for (MeasurementPointDTO dto : points) {
            if (dto.getDirection() != PointDirection.INPUT) {
                if (dto.getReferencePointId() != null && !dto.getReferencePointId().isBlank()) {
                    problems.add(dto.getPointId() + " 是输出测点却带了 referencePointId");
                }
                continue;
            }
            String refId = dto.getReferencePointId();
            if (refId == null || refId.isBlank()) {
                problems.add(dto.getPointId() + " 缺少 referencePointId");
                continue;
            }
            // 本次 payload 内的引用：先按 payload 里的方向判定，避免与库中旧状态混淆
            MeasurementPointDTO inPayloadTarget = points.stream()
                    .filter(p -> p.getPointId().equals(refId))
                    .findFirst().orElse(null);
            if (inPayloadTarget != null) {
                if (inPayloadTarget.getDirection() != PointDirection.OUTPUT) {
                    problems.add(dto.getPointId() + " 引用的 " + refId + " 不是输出测点");
                }
                continue;
            }
            MeasurementPoint target = pointRepository.findById(refId).orElse(null);
            if (target == null) {
                problems.add(dto.getPointId() + " 引用的 " + refId + " 不存在");
            } else if (target.getDirection() != PointDirection.OUTPUT) {
                problems.add(dto.getPointId() + " 引用的 " + refId + " 不是输出测点");
            } else if (target.getDataType() != dto.getDataType()) {
                problems.add(dto.getPointId() + " 与 " + refId + " 数据类型不一致");
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(400, "导入失败：测点引用不合法 - " + String.join("；", problems));
        }
    }
```

补 import `com.sdncustom.common.model.MeasurementPoint;`、`com.sdncustom.common.model.enums.PointDirection;`、`com.sdncustom.server.repository.MeasurementPointRepository;`

- [ ] **Step 4: 运行测试**

Run: `mvn test -pl sdncustom-server -am -Dtest=DataTransferServiceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat: validate point references on data import"
```

---

## Task 8: mock 数据文件改造

**Files:**
- Modify: `mock/mock-data.json`

**Interfaces:**
- Consumes: Task 1（businessId 必填、只认 bindings 数组）、Task 3（direction 必填）、Task 7（引用预检）
- Produces: 可直接导入的 `mock-data.json`

- [ ] **Step 1: 用 Node 脚本改造**

在项目根执行（Node v24 已具备；`jq` 不可用）：

```bash
node -e "
const fs=require('fs');
const p='mock/mock-data.json';
const d=JSON.parse(fs.readFileSync(p,'utf8'));
d.businesses=[{businessId:'default',businessName:'默认业务',description:'从 mock-data.json 导入的示例业务'}];
for(const pt of d.points){
  pt.businessId='default';
  if(!pt.direction) pt.direction='OUTPUT';
}
// 保持可读格式：2 空格缩进
fs.writeFileSync(p,JSON.stringify(d,null,2)+'\n');
console.log('points:',d.points.length,'businesses:',d.businesses.length);
"
```

- [ ] **Step 2: 校验结果**

```bash
node -e "
const d=require('./mock/mock-data.json');
const bad=d.points.filter(p=>!p.businessId||!p.direction||p.writable!==undefined);
console.log('缺字段或残留 writable 的测点:',bad.length);
const dirs={};d.points.forEach(p=>dirs[p.direction]=(dirs[p.direction]||0)+1);
console.log('方向分布:',dirs);
console.log('bindings 全部为数组:',d.points.every(p=>Array.isArray(p.bindings)&&p.bindings.length>0));
"
```

Expected: `缺字段或残留 writable 的测点: 0`，方向分布为 106 个 OUTPUT。（`writable` 字段由上文脚本的 JSON 重写自然丢弃 —— 若校验发现残留，手工删掉该 key 再跑一次。）

- [ ] **Step 3: 提交**

```bash
git add mock/mock-data.json
git commit -m "chore: add business and direction fields to mock data"
```

---

## Task 9: 前端类型、API 与 store 适配方向

**Files:**
- Modify: `sdncustom-web/src/types/index.ts`
- Modify: `sdncustom-web/src/services/api.ts`
- Modify: `sdncustom-web/src/stores/pointStore.ts`

**Interfaces:**
- Consumes: Task 3 的前端类型改动
- Produces:
  - `pointApi.getAll(channelId?: string, businessId?: string, direction?: PointDirection)`
  - `PointStore.fetchPoints(channelId?: string, businessId?: string | null, direction?: PointDirection)`

- [ ] **Step 1: 改 `api.ts`**

```ts
  getAll: (channelId?: string, businessId?: string, direction?: PointDirection) =>
    api.get<ApiResponse<MeasurementPoint[]>>('/points', { params: { channelId, businessId, direction } }),
```

`import type {...}` 补 `PointDirection`。

- [ ] **Step 2: 改 `pointStore.ts`**

- `fetchPoints` 签名加 `direction?: PointDirection`，透传给 `pointApi.getAll(channelId, biz, direction)`
- 因为 `direction` 是新的筛选维度，必须在切换时清空/重载，故 `clearIfBusinessChanged` 的判定要同时考虑方向。最简做法：把方向纳入序列号重置条件——在 `fetchPoints` 里把 `seq` 逻辑保留（已存在），仅透传参数：

```ts
  fetchPoints: async (channelId, businessId, direction) => {
    const biz = businessId === undefined ? useBusinessStore.getState().currentBusinessId : businessId;
    if (biz === null) return;
    clearIfBusinessChanged(biz, set);
    const seq = ++fetchPointsSeq;
    set({ loading: true, error: null });
    try {
      const res = await pointApi.getAll(channelId, biz, direction);
      // ...（其余不变）
```

- `PointStore` 接口里 `fetchPoints` 的签名同步更新。

- [ ] **Step 3: 类型检查**

Run: `cd sdncustom-web && npm run check:types && npm run build`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat(web): add direction to point types, api and store"
```

---

## Task 10: PointPage 方向列、筛选与创建流程

**Files:**
- Modify: `sdncustom-web/src/pages/PointPage.tsx`

**Interfaces:**
- Consumes: Task 9 的 `fetchPoints(channelId, businessId, direction)` 与 `PointDirection` 类型
- Produces: 无（终端 UI）

- [ ] **Step 1: 加方向筛选状态**

```tsx
  const [filterDirection, setFilterDirection] = useState<PointDirection | undefined>();

  useEffect(() => {
    fetchPoints(filterChannel, undefined, filterDirection);
  }, [filterChannel, filterDirection, fetchPoints, currentBusinessId]);
```

工具栏加：

```tsx
          <Select
            placeholder="按方向筛选"
            allowClear
            style={{ width: 160 }}
            onChange={(v) => setFilterDirection(v)}
            options={[
              { label: '输入测点', value: 'INPUT' },
              { label: '输出测点', value: 'OUTPUT' },
            ]}
          />
```

- [ ] **Step 2: 重做创建/编辑表单的方向流程**

把顶部监听扩成两个：

```tsx
  const mainChannelId = Form.useWatch('channelId', form);
  const watchedDirection = Form.useWatch('direction', form);
```

**创建流程（顺序敏感：先管道、再测点）** — 在 `address` 之后插入：

```tsx
          {watchedDirection === 'INPUT' && !editing && (
            <Form.Item
              name="referencePointId"
              label="引用的输出测点"
              tooltip="仅列出绑定到你上面所选通道的输出测点"
              rules={[{ required: true, message: '请选择引用的输出测点' }]}
            >
              <Select
                disabled={!mainChannelId}
                placeholder={mainChannelId ? '选择输出测点' : '请先选择所属通道'}
                showSearch
                optionFilterProp="label"
                options={referenceOptions}
              />
            </Form.Item>
          )}
```

`referenceOptions` 定义（放在 `linkOptions` 附近）：

```tsx
  // 输入测点只能引用「绑定到所选通道」的输出测点
  const referenceOptions = allPoints
    .filter((p) => p.direction === 'OUTPUT')
    .filter((p) => (p.bindings ?? []).some((b) => b.channelId === mainChannelId))
    .map((p) => ({
      label: `${p.pointName} (${p.pointId}) · ${p.dataType}`,
      value: p.pointId,
    }));
```

- [ ] **Step 3: `handleSubmit` 带上方向与引用**

创建分支：

```tsx
        await createPoint({
          pointId: values.pointId,
          businessId: currentBusinessId ?? undefined,
          pointName: values.pointName,
          dataType: values.dataType,
          unit: values.unit,
          deadband: values.deadband,
          direction: values.direction,
          referencePointId: values.direction === 'INPUT' ? values.referencePointId : undefined,
          bindings: [{ channelId: values.channelId, address: values.address }],
        });
```

更新分支：**不加 direction / referencePointId**（后端静默忽略），仅提交可变字段：

```tsx
        await updatePoint(editing.pointId, {
          pointId: editing.pointId,
          pointName: values.pointName,
          dataType: values.dataType,
          unit: values.unit,
          deadband: values.deadband,
          bindings,
        });
```

- [ ] **Step 4: 编辑态锁定方向并展示引用**

在方向 `Form.Item` 上保留 `disabled={!!editing}`，并在编辑态显示只读提示：

```tsx
          {editing && editing.direction === 'INPUT' && (
            <div style={{ marginBottom: 16, color: '#8c8c8c' }}>
              输入测点，引用自 <b>{allPoints.find((p) => p.pointId === editing.referencePointId)?.pointName ?? editing.referencePointId}</b>（创建后不可变更）
            </div>
          )}
```

- [ ] **Step 5: `showCreateModal` 预置值**

```tsx
    form.setFieldsValue({ channelId: filterChannel, direction: 'OUTPUT' });
```

- [ ] **Step 6: 构建**

Run: `cd sdncustom-web && npm run build`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(web): input/output point creation flow in PointPage"
```

---

## Task 11: MonitorPage 与 DashboardPage 方向展示

**Files:**
- Modify: `sdncustom-web/src/pages/MonitorPage.tsx`
- Modify: `sdncustom-web/src/pages/DashboardPage.tsx`

**Interfaces:**
- Consumes: Task 3 的前端 `MeasurementPoint.direction/referencePointId`
- Produces: 无（终端 UI）

- [ ] **Step 1: MonitorPage 加方向列与引用标注**

在「类型」列前插入：

```tsx
    {
      title: '方向',
      key: 'direction',
      width: 140,
      render: (_: unknown, record: MeasurementPoint) => {
        if (record.direction !== 'INPUT') {
          return <Tag color="green">输出</Tag>;
        }
        const refName = points.find((p) => p.pointId === record.referencePointId)?.pointName
          ?? record.referencePointId;
        return (
          <Space size={4}>
            <Tag color="blue">输入</Tag>
            <Tooltip title={`引用自 ${refName}`}>
              <span style={{ fontSize: 11, color: '#8c8c8c' }}>← {refName}</span>
            </Tooltip>
          </Space>
        );
      },
    },
```

（`Tooltip` / `Space` / `Tag` 已在文件顶部 import，无需新增。）

- [ ] **Step 2: DashboardPage 加方向统计**

在测点表上方加一行统计。先算：

```tsx
  const inputCount = points.filter((p) => p.direction === 'INPUT').length;
  const outputCount = points.filter((p) => p.direction === 'OUTPUT').length;
```

紧接状态卡那个 `<Row>` 的 `</Row>` 之后（即通道筛选 Row 之前）插入：

```tsx
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={12}>
          <Card size="small">
            <Statistic title="输出测点（从外部采集）" value={outputCount} valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="输入测点（写出到外部）" value={inputCount} valueStyle={{ color: '#1677ff' }} />
          </Card>
        </Col>
      </Row>
```

（`Row` / `Col` / `Card` / `Statistic` 已在文件顶部 import。）

- [ ] **Step 3: 构建**

Run: `cd sdncustom-web && npm run build`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat(web): show point direction in monitor and dashboard"
```

---

## Task 12: 端到端手工验证与文档更新

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`（如涉及默认凭据/引导流程）

**Interfaces:**
- Consumes: 全部前置任务
- Produces: 无

- [ ] **Step 1: 重置库并构建**

```bash
rm -rf data/
cmd.exe /c "cd /d E:\dev\workspace\learnProject\SDNCustom && scripts\build.bat"
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: 启动依赖与服务**

```bash
docker-compose up -d
cmd.exe /c "cd /d E:\dev\workspace\learnProject\SDNCustom && start /b scripts\start-backend.bat"
cd sdncustom-web && npm run dev
```

等待 `curl -s http://localhost:8080/actuator/health` 返回 `{"status":"UP"}`，前端 3000 返回 200。

- [ ] **Step 3: 按设计文档 §8.3 走一遍**

1. 登录 `admin` / `changeme`
2. 通道管理页「导入通道配置」选 `mock/mock-channels.json`，连接 TCP 与 Modbus 模拟器
3. 测点管理页「导入数据」选 `mock/mock-data.json`（**必须先通道后数据**）
4. 记录 `tcp_bool_001` 的当前值
5. 添加测点：方向=输入测点 → 所属通道选 Modbus 通道 → 引用测点选 `tcp_bool_001`（**验证下拉此时才可用且只列出 TCP 通道的输出测点**）
6. 观察：TCP 侧值变化 → Modbus 侧寄存器被写入 → 监控页两个测点同步刷新，输入测点带「← 设备1运行状态」标注
7. 断开 Modbus 通道 → 输入测点的值**仍随输出测点更新**（验证决策 A：写出失败不影响值）
8. 尝试删除 `tcp_bool_001` → 应返回 400 提示被输入测点引用
9. 尝试给输入测点改方向 → 表单中方向选择器应为禁用

- [ ] **Step 4: 更新 `CLAUDE.md`**

需要改动的章节：

1. **核心概念** — 「测点」条目补方向说明：

```
- **测点 (MeasurementPoint)**：数据的最小单元，平台的一等公民。分方向：`OUTPUT` 输出测点（绑定通道用于**采集读取**，数据从外部流入平台，即旧语义）；`INPUT` 输入测点（`referencePointId` 引用一个**同业务、同 dataType** 的 OUTPUT 测点，其绑定通道用于**写出**——输出测点经 ChangeGate 的有效变化由 `InputPointPropagator` 事件驱动扇出，通过输入测点的绑定通道写到外部）。一个 INPUT 只引用一个 OUTPUT，多个 INPUT 可引用同一 OUTPUT。方向创建后不可变更，删除被引用的 OUTPUT 返回 400
```

2. **启动时序** — 整节替换为：

```markdown
## 启动时序

**没有任何启动期迁移或数据播种**——早期用于兼容旧表结构的 `BindingMigration` /
`BusinessSystemMigration` / `DemoDataInitializer` 已全部删除。表结构完全由 JPA
`ddl-auto: update` 依实体建出。

启动期唯一的 `CommandLineRunner` 是 `AppStartupRunner`：① `HistoryService.init()`
建 TDengine 库/超级表（失败仅告警，不阻断启动）→ ② 守护线程 sleep 2s 后
`ChannelService.autoConnectAll()`（并行连接 `autoConnect=true` 通道）。

运行时产物：H2 配置库是文件库 `./data/sdncustom.mv.db`（`data/` 与 `logs/` 均被 gitignore）。

**只支持空库**：本项目不做旧库原地升级。结构变更后请删掉 `data/` 重启，让它按当前实体重建。

**空库引导**（顺序不能颠倒——测点绑定要求通道已存在）：

1. 通道管理页「导入通道配置」选 `mock/mock-channels.json`，逐个"连接"
2. 测点管理页「导入数据」选 `mock/mock-data.json`（文件自带 `businesses` 段与每个测点的
   `businessId`、`direction`）
```

3. **关键服务** — 新增 `InputPointPropagator` 条目；`AcquisitionEngine` 条目补"只采 OUTPUT + 调用传播 + 输入测点值不过 `onlyLiveSources`"；`PointSourceService` 条目补 `findOutputPointsForChannel` 与"`findPointsForChannel` 返回全部方向，采集/订阅必须用前者"；`ChangeGate` 条目删 `recordManualWrite`。

4. **API 端点** — MeasurementPoint 段：删 `PUT /api/points/{id}/value`；`GET /api/points` 补 `?direction=`；`DELETE` 补被引用时 400。Data 段补 businessId 必填、只认 `bindings` 数组。

5. **数据流** — 图里加输入测点传播支路。

6. **可观测性** — 指标列表加 `propagation.writes` / `propagation.failures`。

7. **环境要求/构建** — 测试类数量更新（删了 3 个迁移测试、加了 4 个新测试类）。

- [ ] **Step 5: 校对 `README.md`**

确认「默认登录凭据」小节仍在、引导流程（先通道后数据）与本计划一致。若 README 仍描述"空库自动播种示例数据"，一并删除。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "docs: update CLAUDE.md and README for point direction"
```

---

## 验收清单

| 设计文档要求 | 覆盖任务 |
|--------------|---------|
| 删 `writable`，加 `direction` + `referencePointId` | Task 3 |
| `PointSource` 不加方向字段，由 direction 推导 | Task 3（未改 PointSource）+ Task 6（调用点区分） |
| 事件驱动传播 `InputPointPropagator` | Task 5 |
| 传播值并入同一批次落库/推送 | Task 6 |
| 写出失败仍更新值 | Task 5（Step 4）+ Task 6（Step 5） |
| INPUT 不参与采集周期 | Task 6（`findOutputPointsForChannel`） |
| 校验规则 1-10 | Task 4（1-9）、Task 7（导入路径） |
| 删除被引用 OUTPUT → 400 | Task 4（Step 7） |
| `GET /api/points?direction=` 过滤 | Task 4（Step 8） |
| 删除 3 个迁移类 + 测试 | Task 1 |
| 导入强制 businessId、删旧格式 | Task 1 |
| 删除手动写值端点 | Task 2 |
| mock-data.json 补字段 | Task 8 |
| 前端方向列/筛选/表单流程 | Task 9、10 |
| Monitor/Dashboard 方向展示 | Task 11 |
| 文档更新 | Task 12 |
