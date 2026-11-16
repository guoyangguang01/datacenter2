using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Persistence;

public sealed record GasMasterImportResult(
    string SystemCode,
    int ClientCount,
    int InputCount,
    int OutputCount,
    int RouteCount,
    IReadOnlyList<string> Warnings);

public interface IGasMasterTableService
{
    Task<GasMasterImportResult> ImportAsync(
        string systemCode,
        string systemName,
        string csv,
        CancellationToken cancellationToken = default);
    Task<string> ExportAsync(
        string systemCode,
        CancellationToken cancellationToken = default);
}

public sealed class GasMasterTableService(
    IDbContextFactory<ServerDbContext> contextFactory,
    IAgentRegistry agents,
    ILatestValueStore latestValues,
    PointLinkCatalog linkCatalog) : IGasMasterTableService
{
    private const int ColumnCount = 22;
    private static readonly string[] Headers =
    [
        "测点id", "测点名称", "数值类型", "单位", "用户", "用户代码", "通讯协议", "测点类型",
        "参数1", "参数2", "参数3", "数据来源用户", "数据来源用户代码",
        "数据来源用户通讯协议", "数据来源测点", "数据去向用户", "数据去向用户代码",
        "数据去向用户通讯协议", "数据去向测点", "参数1", "参数2", "参数3"
    ];

    public async Task<GasMasterImportResult> ImportAsync(
        string systemCode,
        string systemName,
        string csv,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(systemCode);
        ArgumentException.ThrowIfNullOrWhiteSpace(systemName);

        var parsed = Parse(csv);
        var warnings = parsed.Warnings.ToList();
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken)
            .ConfigureAwait(false);
        var clients = await context.ClientSystems
            .ToListAsync(cancellationToken)
            .ConfigureAwait(false);
        var clientsByCode = clients.ToDictionary(client => client.ClientCode, StringComparer.Ordinal);
        var requiredClientCodes = parsed.Rows
            .SelectMany(row => row.PointType == "输入"
                ? new[] { row.UserCode, row.SourceUserCode }
                : new[] { row.UserCode, row.TargetUserCode })
            .Where(code => !string.IsNullOrWhiteSpace(code))
            .ToHashSet(StringComparer.Ordinal);
        var missingClients = requiredClientCodes
            .Where(code => !clientsByCode.ContainsKey(code))
            .OrderBy(code => code, StringComparer.Ordinal)
            .ToArray();
        if (missingClients.Length > 0)
        {
            throw new InvalidOperationException(
                "导入前必须先创建并配置客户端：" + string.Join("、", missingClients));
        }

        var runningClients = requiredClientCodes
            .Where(code => agents.Find(code) is not null || clientsByCode[code].DesiredRunning)
            .OrderBy(code => code, StringComparer.Ordinal)
            .ToArray();
        if (runningClients.Length > 0)
        {
            throw new InvalidOperationException(
                "以下客户端在线，必须先停止通讯：" + string.Join("、", runningClients));
        }

        var expectedProtocols = new Dictionary<string, ClientProtocol>(StringComparer.Ordinal);
        foreach (var row in parsed.Rows)
        {
            AddExpectedProtocol(expectedProtocols, row.UserCode, row.Protocol);
            if (row.PointType == "输出")
            {
                AddExpectedProtocol(expectedProtocols, row.TargetUserCode, row.TargetProtocol);
            }
            else
            {
                AddExpectedProtocol(expectedProtocols, row.SourceUserCode, row.SourceProtocol);
            }
        }

        var invalidProtocolClients = expectedProtocols
            .Where(pair =>
                !Enum.TryParse<ClientProtocol>(clientsByCode[pair.Key].Protocol, true, out var current) ||
                current != pair.Value)
            .Select(pair =>
                $"{pair.Key}(配置={clientsByCode[pair.Key].Protocol}, 总表={pair.Value})")
            .ToArray();
        if (invalidProtocolClients.Length > 0)
        {
            throw new InvalidOperationException(
                "客户端协议未配置或与总表不一致：" + string.Join("、", invalidProtocolClients));
        }

        var wrongSystemClients = requiredClientCodes
            .Select(code => clientsByCode[code])
            .Where(client => !string.Equals(client.SystemCode, systemCode, StringComparison.Ordinal))
            .Select(client => $"{client.ClientCode}({client.SystemCode})")
            .ToArray();
        if (wrongSystemClients.Length > 0)
        {
            throw new InvalidOperationException(
                $"以下客户端不属于系统 {systemCode}：" + string.Join("、", wrongSystemClients));
        }

        var inputGroups = parsed.Rows
            .Where(row => row.PointType == "输入")
            .GroupBy(row => (row.UserCode, row.PointId))
            .ToArray();
        var inputAvailability = new Dictionary<string, bool>(StringComparer.Ordinal);
        var pointEntities = new List<PointCatalogEntity>();
        foreach (var group in inputGroups)
        {
            var rows = group.ToArray();
            var endpointRow = rows.FirstOrDefault(row =>
                                  !string.IsNullOrWhiteSpace(row.Parameter1) ||
                                  !string.IsNullOrWhiteSpace(row.Parameter2) ||
                                  !string.IsNullOrWhiteSpace(row.Parameter3))
                              ?? rows[0];
            var addressProtocol = string.IsNullOrWhiteSpace(endpointRow.SourceProtocol)
                ? endpointRow.Protocol
                : endpointRow.SourceProtocol;
            var address = BuildNodeId(
                addressProtocol,
                endpointRow.Parameter1,
                endpointRow.Parameter2,
                endpointRow.Parameter3);
            var available = address.Status is "supported" or "not-applicable";
            inputAvailability[$"{group.Key.UserCode}:{group.Key.PointId}"] = available;
            pointEntities.Add(new PointCatalogEntity
            {
                ClientCode = group.Key.UserCode,
                PointId = group.Key.PointId,
                PointName = endpointRow.PointName,
                Address = address.NodeId ?? string.Empty,
                Protocol = addressProtocol,
                DataType = MapDataType(endpointRow.DataType).ToString(),
                Unit = endpointRow.Unit,
                Direction = PointDirection.Input.ToString(),
                OpcUaParameter1 = endpointRow.Parameter1,
                OpcUaParameter2 = endpointRow.Parameter2,
                OpcUaParameter3 = endpointRow.Parameter3,
                Group = systemName
            });
        }

        var pointIdsByName = inputGroups.ToDictionary(
            group => $"{group.Key.UserCode}|{group.First().PointName}",
            group => group.Key.PointId,
            StringComparer.Ordinal);
        var linkEntities = new Dictionary<string, PointLinkEntity>(StringComparer.Ordinal);
        var outputEndpoints = new Dictionary<string, PointCatalogEntity>(StringComparer.Ordinal);
        foreach (var row in parsed.Rows.Where(row => row.PointType == "输入"))
        {
            var sourcePointId = pointIdsByName.GetValueOrDefault(
                $"{row.SourceUserCode}|{row.SourcePointName}",
                string.Empty);
            var linkId = BuildLinkId(
                systemCode,
                "Input",
                row.SourceUserCode,
                sourcePointId,
                row.SourcePointName,
                row.UserCode,
                row.PointId,
                row.PointName);
            linkEntities[linkId] = new PointLinkEntity
            {
                LinkId = linkId,
                SystemCode = systemCode,
                Declaration = "Input",
                SourceClientCode = row.SourceUserCode,
                SourcePointId = sourcePointId,
                SourcePointName = row.SourcePointName,
                SourceProtocol = row.SourceProtocol,
                TargetClientCode = row.UserCode,
                TargetPointId = row.PointId,
                Enabled = inputAvailability.GetValueOrDefault(
                    $"{row.UserCode}:{row.PointId}",
                    false)
            };
        }

        foreach (var row in parsed.Rows.Where(row => row.PointType == "输出"))
        {
            var suffix = GetPointSuffix(row.PointId, row.UserCode);
            var targetPointId = row.TargetUserCode + suffix;
            var linkId = BuildLinkId(
                systemCode,
                "Output",
                row.UserCode,
                row.PointId,
                row.PointName,
                row.TargetUserCode,
                targetPointId,
                row.TargetPointName);
            if (linkEntities.ContainsKey(linkId))
            {
                warnings.Add($"忽略重复路由：{row.UserCode}:{row.PointId} -> {targetPointId}");
                continue;
            }

            var targetAddress = BuildNodeId(
                row.TargetProtocol,
                row.TargetParameter1,
                row.TargetParameter2,
                row.TargetParameter3);
            var endpointKey = $"{row.TargetUserCode}:{targetPointId}";
            var endpointCompatible = true;
            if (!outputEndpoints.TryGetValue(endpointKey, out var outputEndpoint))
            {
                outputEndpoint = new PointCatalogEntity
                {
                    ClientCode = row.TargetUserCode,
                    PointId = targetPointId,
                    PointName = row.TargetPointName,
                    Address = targetAddress.NodeId ?? string.Empty,
                    Protocol = row.TargetProtocol,
                    DataType = MapDataType(row.DataType).ToString(),
                    Unit = row.Unit,
                    Direction = PointDirection.Output.ToString(),
                    OpcUaParameter1 = row.TargetParameter1,
                    OpcUaParameter2 = row.TargetParameter2,
                    OpcUaParameter3 = row.TargetParameter3,
                    Group = systemName
                };
                outputEndpoints[endpointKey] = outputEndpoint;
            }
            else if (!string.Equals(outputEndpoint.Address, targetAddress.NodeId ?? string.Empty,
                         StringComparison.Ordinal))
            {
                endpointCompatible = false;
                warnings.Add(
                    $"目标 OUTPUT {endpointKey} 在总表中出现多个地址，冲突路由已禁用。");
            }

            var sourceAvailable =
                inputAvailability.GetValueOrDefault($"{row.UserCode}:{row.PointId}", false);
            var targetAvailable =
                endpointCompatible &&
                (targetAddress.Status is "supported" or "not-applicable");
            linkEntities[linkId] = new PointLinkEntity
            {
                LinkId = linkId,
                SystemCode = systemCode,
                Declaration = "Output",
                SourceClientCode = row.UserCode,
                SourcePointId = row.PointId,
                SourcePointName = row.PointName,
                SourceProtocol = row.Protocol,
                TargetClientCode = row.TargetUserCode,
                TargetPointId = targetPointId,
                Enabled = sourceAvailable && targetAvailable
            };
        }

        pointEntities.AddRange(outputEndpoints.Values);

        var unsupportedNumeric = pointEntities.Count(point =>
            point.Protocol is "OPC UA客户端" or "OPC UA服务端" &&
            !string.IsNullOrWhiteSpace(point.OpcUaParameter1) &&
            string.IsNullOrWhiteSpace(point.OpcUaParameter2) &&
            string.IsNullOrWhiteSpace(point.OpcUaParameter3));
        if (unsupportedNumeric > 0)
        {
            warnings.Add($"{unsupportedNumeric} 个数字型 OPC UA NodeId 暂不支持，相关路由已禁用。");
        }

        var system = await context.BusinessSystems
            .SingleOrDefaultAsync(item => item.SystemCode == systemCode, cancellationToken)
            .ConfigureAwait(false);
        if (system is null)
        {
            system = new BusinessSystemEntity
            {
                SystemCode = systemCode,
                SystemName = systemName,
                Description = "由燃气总表导入。",
                CreatedAt = DateTimeOffset.UtcNow
            };
            context.BusinessSystems.Add(system);
        }
        else
        {
            system.SystemName = systemName;
        }

        await using var transaction = await context.Database.BeginTransactionAsync(cancellationToken)
            .ConfigureAwait(false);
        var affectedClientCodes = requiredClientCodes.ToArray();
        await context.PointLinks
            .Where(link => link.SystemCode == systemCode)
            .ExecuteDeleteAsync(cancellationToken)
            .ConfigureAwait(false);
        await context.PointCatalog
            .Where(point => affectedClientCodes.Contains(point.ClientCode))
            .ExecuteDeleteAsync(cancellationToken)
            .ConfigureAwait(false);
        context.PointCatalog.AddRange(pointEntities);
        context.PointLinks.AddRange(linkEntities.Values);

        foreach (var code in affectedClientCodes)
        {
            var client = clientsByCode[code];
            client.PointsConfigured = true;
            client.CatalogRevision = ComputeRevision(
                pointEntities
                    .Where(point => point.ClientCode == code)
                    .ToArray());
            client.LastSeen = DateTimeOffset.UtcNow;
        }

        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
        await linkCatalog.ReloadAsync(cancellationToken).ConfigureAwait(false);

        foreach (var code in affectedClientCodes)
        {
            var pointIds = pointEntities
                .Where(point => point.ClientCode == code)
                .Select(point => point.PointId)
                .ToArray();
            await latestValues.RemovePointsAsync(code, pointIds, cancellationToken).ConfigureAwait(false);
        }

        return new GasMasterImportResult(
            systemCode,
            affectedClientCodes.Length,
            pointEntities.Count(point => point.Direction == nameof(PointDirection.Input)),
            pointEntities.Count(point => point.Direction == nameof(PointDirection.Output)),
            linkEntities.Values.Count(link => link.Declaration == "Output"),
            warnings);
    }

    public async Task<string> ExportAsync(
        string systemCode,
        CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken)
            .ConfigureAwait(false);
        var system = await context.BusinessSystems
            .AsNoTracking()
            .SingleOrDefaultAsync(item => item.SystemCode == systemCode, cancellationToken)
            .ConfigureAwait(false)
            ?? throw new KeyNotFoundException($"系统不存在：{systemCode}");
        var clients = await context.ClientSystems
            .AsNoTracking()
            .Where(client => client.SystemCode == systemCode)
            .ToDictionaryAsync(client => client.ClientCode, StringComparer.Ordinal, cancellationToken)
            .ConfigureAwait(false);
        var clientCodes = clients.Keys.ToArray();
        var points = await context.PointCatalog
            .AsNoTracking()
            .Where(point => clientCodes.Contains(point.ClientCode))
            .ToDictionaryAsync(
                point => $"{point.ClientCode}:{point.PointId}:{point.Direction}",
                StringComparer.Ordinal,
                cancellationToken)
            .ConfigureAwait(false);
        var outputLinks = await context.PointLinks
            .AsNoTracking()
            .Where(link => link.SystemCode == systemCode && link.Declaration == "Output")
            .OrderBy(link => link.SourceClientCode)
            .ThenBy(link => link.SourcePointId)
            .ThenBy(link => link.TargetClientCode)
            .ThenBy(link => link.TargetPointId)
            .ToArrayAsync(cancellationToken)
            .ConfigureAwait(false);
        var inputLinks = await context.PointLinks
            .AsNoTracking()
            .Where(link => link.SystemCode == systemCode && link.Declaration == "Input")
            .OrderBy(link => link.TargetClientCode)
            .ThenBy(link => link.TargetPointId)
            .ThenBy(link => link.SourceClientCode)
            .ThenBy(link => link.SourcePointName)
            .ToArrayAsync(cancellationToken)
            .ConfigureAwait(false);

        var output = new StringBuilder();
        output.AppendLine(string.Join(',', Headers.Select(Escape)));
        foreach (var link in outputLinks)
        {
            var sourcePoint = points.GetValueOrDefault(
                $"{link.SourceClientCode}:{link.SourcePointId}:Input");
            var targetPoint = points.GetValueOrDefault(
                $"{link.TargetClientCode}:{link.TargetPointId}:Output");
            if (targetPoint is null)
            {
                continue;
            }
            var sourceClient = clients[link.SourceClientCode];
            var targetClient = clients[link.TargetClientCode];
            var dataType = ToGasDataType(targetPoint.DataType);
            var unit = targetPoint.Unit;

            WriteCsvRow(output,
            [
                link.SourcePointId,
                link.SourcePointName,
                dataType,
                unit,
                sourceClient.ClientName,
                link.SourceClientCode,
                ToGasProtocol(sourceClient.Protocol),
                "输出",
                sourcePoint?.OpcUaParameter1 ?? string.Empty,
                sourcePoint?.OpcUaParameter2 ?? string.Empty,
                sourcePoint?.OpcUaParameter3 ?? string.Empty,
                string.Empty,
                string.Empty,
                string.Empty,
                string.Empty,
                targetClient.ClientName,
                link.TargetClientCode,
                targetPoint.Protocol,
                targetPoint.PointName,
                targetPoint.OpcUaParameter1,
                targetPoint.OpcUaParameter2,
                targetPoint.OpcUaParameter3
            ]);

        }

        foreach (var link in inputLinks)
        {
            var sourceClient = clients[link.SourceClientCode];
            var targetClient = clients[link.TargetClientCode];
            var targetPoint = points.GetValueOrDefault(
                $"{link.TargetClientCode}:{link.TargetPointId}:Input");
            if (targetPoint is null)
            {
                continue;
            }
            WriteCsvRow(output,
            [
                link.TargetPointId,
                targetPoint.PointName,
                ToGasDataType(targetPoint.DataType),
                targetPoint.Unit,
                targetClient.ClientName,
                link.TargetClientCode,
                ToGasProtocol(targetClient.Protocol),
                "输入",
                targetPoint.OpcUaParameter1,
                targetPoint.OpcUaParameter2,
                targetPoint.OpcUaParameter3,
                sourceClient.ClientName,
                link.SourceClientCode,
                link.SourceProtocol,
                link.SourcePointName,
                string.Empty,
                string.Empty,
                string.Empty,
                string.Empty,
                string.Empty,
                string.Empty,
                string.Empty
            ]);
        }

        return output.ToString();
    }

    private static ParsedTable Parse(string csv)
    {
        var records = SplitCsvRecords(csv);
        if (records.Count == 0)
        {
            throw new InvalidDataException("燃气总表为空。");
        }

        var header = records[0]
            .Select(value => value.Trim().TrimStart('\uFEFF'))
            .ToArray();
        if (header.Length < ColumnCount ||
            header[0] != "测点id" ||
            header[7] != "测点类型" ||
            header[18] != "数据去向测点")
        {
            throw new InvalidDataException("燃气总表列格式不正确，需使用 22 列原始模板。");
        }

        var rows = new List<GasRow>();
        for (var index = 1; index < records.Count; index++)
        {
            var values = records[index];
            if (values.All(string.IsNullOrWhiteSpace))
            {
                continue;
            }

            string Value(int column) => column < values.Length ? values[column].Trim() : string.Empty;
            var pointType = Value(7);
            if (pointType is not ("输入" or "输出"))
            {
                throw new InvalidDataException(
                    $"第 {index + 1} 行测点类型无效：{pointType}");
            }

            var pointId = Value(0);
            var userCode = Value(5);
            if (string.IsNullOrWhiteSpace(pointId) || string.IsNullOrWhiteSpace(userCode))
            {
                throw new InvalidDataException($"第 {index + 1} 行缺少测点 ID 或用户代码。");
            }

            GetPointSuffix(pointId, userCode);
            if (pointType == "输出" &&
                (string.IsNullOrWhiteSpace(Value(16)) ||
                 string.IsNullOrWhiteSpace(Value(18))))
            {
                throw new InvalidDataException(
                    $"第 {index + 1} 行输出关系缺少目标用户或目标测点。");
            }

            rows.Add(new GasRow(
                pointId,
                Value(1),
                Value(2),
                Value(3),
                Value(4),
                userCode,
                Value(6),
                pointType,
                Value(8),
                Value(9),
                Value(10),
                Value(11),
                Value(12),
                Value(13),
                Value(14),
                Value(15),
                Value(16),
                Value(17),
                Value(18),
                Value(19),
                Value(20),
                Value(21)));
        }

        return new ParsedTable(rows, []);
    }

    private static string GetPointSuffix(string pointId, string clientCode)
    {
        if (!pointId.StartsWith(clientCode, StringComparison.Ordinal))
        {
            throw new InvalidDataException(
                $"测点 {pointId} 不符合“客户端代码 + 五位数字 + -b/-d”规则。");
        }

        var suffix = pointId[clientCode.Length..];
        if (suffix.Length != 7 ||
            !suffix[..5].All(char.IsDigit) ||
            suffix[5] != '-' ||
            suffix[6] is not ('b' or 'd'))
        {
            throw new InvalidDataException(
                $"测点 {pointId} 不符合“客户端代码 + 五位数字 + -b/-d”规则。");
        }

        return suffix;
    }

    private static AddressResult BuildNodeId(
        string protocol,
        string parameter1,
        string parameter2,
        string parameter3)
    {
        if (protocol is not ("OPC UA客户端" or "OPC UA服务端"))
        {
            return new AddressResult("not-applicable", null);
        }

        if (parameter2 == "S" && !string.IsNullOrWhiteSpace(parameter3))
        {
            return new AddressResult("supported", $"ns={parameter1};s={parameter3}");
        }

        if (!string.IsNullOrWhiteSpace(parameter1) &&
            string.IsNullOrWhiteSpace(parameter2) &&
            string.IsNullOrWhiteSpace(parameter3))
        {
            return new AddressResult("unsupported-numeric", null);
        }

        return new AddressResult("missing", null);
    }

    private static PointDataType MapDataType(string value) =>
        value.Trim().ToLowerInvariant() switch
        {
            "bool" => PointDataType.Bool,
            "int16" => PointDataType.Int16,
            "int32" => PointDataType.Int32,
            "float32" => PointDataType.Float32,
            "float64" or "double" => PointDataType.Float64,
            "string" => PointDataType.String,
            _ => throw new InvalidDataException($"不支持的数据类型：{value}")
        };

    private static void AddExpectedProtocol(
        IDictionary<string, ClientProtocol> protocols,
        string clientCode,
        string gasProtocol)
    {
        var protocol = gasProtocol switch
        {
            "TCP" => ClientProtocol.CustomTcp,
            "OPC UA客户端" => ClientProtocol.OpcUaClient,
            "OPC UA服务端" => ClientProtocol.OpcUaServer,
            _ => throw new InvalidDataException(
                $"客户端 {clientCode} 的协议不支持：{gasProtocol}")
        };
        if (protocols.TryGetValue(clientCode, out var existing) && existing != protocol)
        {
            throw new InvalidDataException(
                $"客户端 {clientCode} 在总表中出现冲突协议：{existing}/{protocol}");
        }

        protocols[clientCode] = protocol;
    }

    private static string ToGasDataType(string? value) =>
        value switch
        {
            "bool" => "bool",
            nameof(PointDataType.Bool) => "bool",
            "int16" => "int16",
            nameof(PointDataType.Int16) => "int16",
            "int32" => "int32",
            nameof(PointDataType.Int32) => "int32",
            "float32" => "float32",
            nameof(PointDataType.Float32) => "float32",
            "double" => "double",
            "float64" => "double",
            nameof(PointDataType.Float64) => "double",
            "string" => "string",
            nameof(PointDataType.String) => "string",
            _ => "double"
        };

    private static string ToGasProtocol(string value) =>
        value switch
        {
            nameof(ClientProtocol.OpcUaClient) => "OPC UA客户端",
            nameof(ClientProtocol.OpcUaServer) => "OPC UA服务端",
            _ => "TCP"
        };

    private static string BuildLinkId(
        string systemCode,
        string declaration,
        string sourceClientCode,
        string sourcePointId,
        string sourcePointName,
        string targetClientCode,
        string targetPointId,
        string targetPointName)
    {
        var identity =
            $"{systemCode}|{declaration}|{sourceClientCode}|{sourcePointId}|{sourcePointName}|" +
            $"{targetClientCode}|{targetPointId}|{targetPointName}";
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity)))[..32];
    }

    private static string ComputeRevision(IReadOnlyList<PointCatalogEntity> points)
    {
        var value = string.Join(
            "|",
            points.OrderBy(point => point.PointId, StringComparer.Ordinal)
                .ThenBy(point => point.Direction)
                .Select(point =>
                    $"{point.PointId}:{point.PointName}:{point.Address}:{point.DataType}:" +
                    $"{point.OpcUaParameter1}:{point.OpcUaParameter2}:{point.OpcUaParameter3}"));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    }

    private static void WriteCsvRow(StringBuilder output, IReadOnlyList<string> values)
    {
        output.AppendLine(string.Join(',', values.Select(Escape)));
    }

    private static string Escape(string value) =>
        value.Contains(',') ||
        value.Contains('"') ||
        value.Contains('\r') ||
        value.Contains('\n')
            ? '"' + value.Replace("\"", "\"\"") + '"'
            : value;

    private static IReadOnlyList<string[]> SplitCsvRecords(string csv)
    {
        var records = new List<string[]>();
        var fields = new List<string>();
        var field = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < csv.Length; index++)
        {
            var character = csv[index];
            if (character == '"')
            {
                if (quoted && index + 1 < csv.Length && csv[index + 1] == '"')
                {
                    field.Append('"');
                    index++;
                }
                else
                {
                    quoted = !quoted;
                }
                continue;
            }

            if (!quoted && character == ',')
            {
                fields.Add(field.ToString());
                field.Clear();
                continue;
            }

            if (!quoted && character is '\r' or '\n')
            {
                if (character == '\r' && index + 1 < csv.Length && csv[index + 1] == '\n')
                {
                    index++;
                }

                fields.Add(field.ToString());
                field.Clear();
                if (fields.Any(value => value.Length > 0))
                {
                    records.Add(fields.ToArray());
                }
                fields.Clear();
                continue;
            }

            field.Append(character);
        }

        if (field.Length > 0 || fields.Count > 0)
        {
            fields.Add(field.ToString());
            if (fields.Any(value => value.Length > 0))
            {
                records.Add(fields.ToArray());
            }
        }

        return records;
    }

    private sealed record ParsedTable(
        IReadOnlyList<GasRow> Rows,
        IReadOnlyList<string> Warnings);

    private sealed record AddressResult(string Status, string? NodeId);

    private sealed record GasRow(
        string PointId,
        string PointName,
        string DataType,
        string Unit,
        string UserName,
        string UserCode,
        string Protocol,
        string PointType,
        string Parameter1,
        string Parameter2,
        string Parameter3,
        string SourceUserName,
        string SourceUserCode,
        string SourceProtocol,
        string SourcePointName,
        string TargetUserName,
        string TargetUserCode,
        string TargetProtocol,
        string TargetPointName,
        string TargetParameter1,
        string TargetParameter2,
        string TargetParameter3);
}
