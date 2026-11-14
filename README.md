# SDNCustom - IoT Data Hub

SDNCustom 是面向测点数据的集中分发平台。当前版本采用 .NET 10 + Avalonia 11：

- `SdnCustom.Server.App`：服务端数据中枢和管理界面。
- `SdnCustom.Client.App`：单连接客户端，一个进程只负责一个设备连接。
- 客户端通过双向流式 gRPC 接入服务端。
- SQLite 保存客户端目录、权威点表配置和最新值，InfluxDB 保存历史。
- 自定义 TCP 和 OPC UA 由客户端本地执行；服务端不直接访问设备。
- OPC UA 分为客户端模式和服务端模式：客户端连接外部 OPC UA Server；服务端模式在客户端进程内启动 OPC UA Server。

## 项目结构

```text
src/
  SdnCustom.Contracts/    Protobuf 契约和类型映射
  SdnCustom.Shared/       测点、值、死区和时间模型
  SdnCustom.Protocols/    TCP / OPC UA 设备连接
  SdnCustom.Client.Core/  本地配置、采集循环、gRPC 监督器
  SdnCustom.Server/       注册、路由、SQLite、InfluxDB 和 gRPC 服务
  SdnCustom.Client.App/   Avalonia 单连接客户端
  SdnCustom.Server.App/   Avalonia 服务端和 headless 服务
tests/
  SdnCustom.Tests/        单元、集成和 Avalonia Headless 测试
native/
  open62541-bridge/       open62541 C ABI 桥
```

## 环境

- .NET 10 SDK。仓库内可使用隔离安装的 `.dotnet/dotnet.exe`。
- Docker，用于 InfluxDB。
- Windows 和 Linux 运行 Avalonia 桌面端；服务器也可通过 `--headless` 无界面运行。

本机安装的隔离 SDK：

```powershell
.\.dotnet\dotnet.exe --info
```

若使用系统 SDK，将以下命令中的 `.\.dotnet\dotnet.exe` 替换为 `dotnet`。
构建脚本会设置 `AVALONIA_TELEMETRY_OPTOUT=1`，避免 Avalonia 构建任务写入受限的用户目录。

## 启动 InfluxDB

```bash
docker compose up -d
```

开发配置默认使用：

- URL：`http://127.0.0.1:8086`
- Org：`sdncustom`
- Bucket：`sdncustom`
- Token：`sdncustom-dev-token`

生产环境必须通过 `SdnCustom__Influx__Token` 或配置文件替换开发 token。

## 构建与测试

Windows：

```powershell
.\scripts\build.bat
.\scripts\test.bat
```

跨平台：

```bash
./scripts/build.sh
./scripts/test.sh
```

## 启动

服务端桌面模式：

```powershell
.\scripts\start-server.bat
```

服务端无界面模式：

```powershell
.\scripts\start-headless.bat
```

单连接客户端：

```powershell
.\scripts\start-client.bat
```

Linux/macOS 对应脚本为 `start-server.sh`、`start-headless.sh` 和 `start-client.sh`。

跨平台发布：

```powershell
.\scripts\publish.ps1
```

发布目录为 `artifacts/publish/{server,client}-{win,linux}-x64`。

## 首次使用

服务端默认开启内置样例种子。空数据库首次启动时，会自动创建“燃油系统”样例，
包含总表中的 5 个客户端、200 个 INPUT 端点、192 个 OUTPUT 端点和 366 条 OUTPUT 路由。客户端连接参数
仍需在各客户端本地配置。可通过 `SdnCustom__SeedFuelSystemSample=false` 关闭。

1. 在服务端右侧“系统监视器”登记客户端代码和名称。
2. 在客户端填写相同的客户端代码、服务端地址和设备连接参数。
3. 在服务端“用户管理”中选择系统和客户端，可导入/导出内部点表 CSV，也可按系统
   导入/导出原始燃气总表。燃气总表涉及的所有客户端必须提前创建并配置连接参数。
4. 客户端应用服务端下发的点表并展示实时数据，不显示点表配置，也不提供 CSV/JSON 导入导出。
5. 客户端连接服务端，在线后由服务端发送“启动通讯”。
6. 服务端离线时客户端立即停止设备通讯，不产生本地补传数据。

## 通讯与数据语义

- 方向以燃气总表为准：INPUT 表示第三方程序向软件侧发送数据；OUTPUT 表示软件侧向一个或多个集成方发送数据。
- 客户端读取 INPUT 点，200ms 为一个采集周期，并通过死区过滤有效变化。
- 服务端把一个 INPUT 点的变化路由到所有引用它的 OUTPUT 点，并逐一向下游集成方下发。
- OUTPUT 必须引用存在且同数据类型的 INPUT；悬空引用、OUTPUT 引用 OUTPUT 会在保存点表时被拒绝。
- 点表 CSV 的 `direction` 只接受 `输入` / `输出`，OUTPUT 行通过 `sourceInputPointId` 指定来源 INPUT。
- 客户端会回传设备通讯状态；管理界面显示实际通讯状态，而不是仅显示期望运行状态。
- 服务端同时记录 INPUT 源值和 OUTPUT 意图值、质量和源时间戳。
- 客户端代码是唯一身份依据，不使用用户名、密码或客户端证书。该模型只适合可信内网。
- 服务端和客户端默认按可信内网部署，使用非 TLS 连接。需要加密链路时可在两端配置中启用 TLS 并指定证书。

> 当前方向语义不兼容旧版本。升级时必须同时更新服务端与客户端，并重新生成点表和
> `client.json`；服务端旧 SQLite 数据库需删除后重建。

## 自定义 TCP

当前占位实现保持原有帧格式：

```text
[Length:4B big-endian][Command:1B][JSON Body]
```

命令码为 `0x01/0x02/0x03/0x04/0x05/0x10/0x11`。codec 位于
`SdnCustom.Protocols/Tcp/TcpFrame.cs`，取得参考程序协议文档后只替换该层。

TCP 使用批量 JSON 语义：

```json
// 0x01 读请求
{"pointIds":["P1","P2"]}

// 0x02 读响应
{"values":[{"pointId":"P1","value":1.5,"quality":"GOOD","timestamp":1700000000000}]}

// 0x03 写请求
{"values":[{"pointId":"P1","value":1.5,"timestamp":1700000000000}]}

// 0x04 写响应
{"results":[{"pointId":"P1","success":true}]}
```

TCP 不使用逐节点地址；地址参数仅用于 OPC UA。

## OPC UA

- `OPC UA 客户端`：使用 open62541 连接外部 OPC UA Server。原生构建说明见
  `native/open62541-bridge/README.md`；缺少原生库时会明确报告所需 DLL/SO。
- `OPC UA 服务端`：使用 OPC UA Foundation .NET Standard `2.0.2` 在客户端进程内启动内置服务器。
  Endpoint 是监听地址，当前客户端点表会映射为 `ns=1` 节点：INPUT 节点允许外部客户端写入，
  OUTPUT 节点由软件更新供外部客户端读取。

## 服务模式

- Windows Service：使用 `SdnCustom.Server.App.exe --headless` 注册服务。
- Linux systemd：ExecStart 指向发布后的 `SdnCustom.Server.App --headless`。
- Windows 注册脚本：以管理员 PowerShell 运行 `.\scripts\install-windows-service.ps1`。
- Linux 单元模板：`deploy/sdncustom-server.service`。
- 健康检查：`GET http://127.0.0.1:7444/health`。
- 状态接口：`GET http://127.0.0.1:7444/api/status`。
- 系统设置接口：`GET http://127.0.0.1:7444/api/system/settings`。

## 开源组件

主要运行时与 UI 依赖均为开放源码：.NET、Avalonia、Dock.Avalonia、CommunityToolkit.Mvvm、
ScottPlot、gRPC、Entity Framework Core、SQLite、InfluxDB OSS 和 open62541。项目不使用 Qt
或商业桌面控件，也不复制参考程序的专有图标和二进制资源。
