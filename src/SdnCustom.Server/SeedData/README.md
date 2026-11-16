# 燃油系统内置样例

燃油样例直接内嵌仓库根目录的 `fuel-csv-utf8.csv`，不维护单独的转换后 JSON。

它不是供用户手工导入的外部样例文件，而是服务端的内嵌资源。空数据库首次启动且
`SdnCustom__SeedFuelSystemSample=true` 时，`FuelSystemSampleSeeder` 会创建客户端并调用
正式燃气总表导入服务创建一个名为
“燃油系统”的业务系统，并写入：

- 5 个预创建客户端：FZXT、SWGZ、SWSC、SWXT、XNCZ；
- 200 个 INPUT 端点；
- 192 个 OUTPUT 端点；
- 732 条统一 PointLink 声明：366 条输出关系、366 条输入来源关系；
- 250 条当前可启用的输出路由。

客户端连接地址、端口和凭据不在总表中，仍需在客户端本地提前配置。纯数字参数属于
TCP 的来源或去向配置，不是数字型 OPC UA NodeId；导入时按参数所属协议解析。
