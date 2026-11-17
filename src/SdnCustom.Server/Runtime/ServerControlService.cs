using SdnCustom.Contracts.Agent.V1;
using SdnCustom.Contracts;
using SdnCustom.Server.History;
using SdnCustom.Server.Persistence;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;
using SharedClientConnectionState = SdnCustom.Shared.Model.ClientConnectionState;
using SharedPointDirection = SdnCustom.Shared.Model.PointDirection;
using SharedPointDefinition = SdnCustom.Shared.Model.PointDefinition;

namespace SdnCustom.Server.Runtime;

public interface IServerControlService
{
    Task CreateClientAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default,
        string? systemCode = null,
        ClientProtocol protocol = ClientProtocol.Tcp);
    Task UpdateClientAsync(
        string clientCode,
        string clientName,
        ClientProtocol protocol,
        CancellationToken cancellationToken = default);
    Task DeleteClientAsync(string clientCode, CancellationToken cancellationToken = default);
    Task StartCommunicationAsync(CancellationToken cancellationToken = default);
    Task StopCommunicationAsync(CancellationToken cancellationToken = default);
    Task SaveClientPointsAsync(
        string clientCode,
        IReadOnlyList<SharedPointDefinition> points,
        CancellationToken cancellationToken = default);
    Task WritePointAsync(
        string clientCode,
        string pointId,
        object? value,
        CancellationToken cancellationToken = default);
    Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
        string clientCode,
        string pointId,
        SharedPointDirection direction,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default);
}

public sealed class ServerControlService : IServerControlService
{
    private readonly IClientSystemStore _clients;
    private readonly IAgentRegistry _agents;
    private readonly IServerCommunicationGate _communicationGate;
    private readonly IClientCommunicationStateStore _communicationStates;
    private readonly IHistoryStore _history;
    private readonly IServerLogStore _logs;
    private readonly ITelemetryService _telemetry;
    private readonly IPointRouter _router;
    private readonly ILatestValueStore _latestValues;

    public ServerControlService(
        IClientSystemStore clients,
        IAgentRegistry agents,
        IServerCommunicationGate communicationGate,
        IClientCommunicationStateStore communicationStates,
        IHistoryStore history,
        IServerLogStore logs,
        ITelemetryService telemetry,
        IPointRouter router,
        ILatestValueStore latestValues)
    {
        _clients = clients;
        _agents = agents;
        _communicationGate = communicationGate;
        _communicationStates = communicationStates;
        _history = history;
        _logs = logs;
        _telemetry = telemetry;
        _router = router;
        _latestValues = latestValues;
    }

    public async Task CreateClientAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default,
        string? systemCode = null,
        ClientProtocol protocol = ClientProtocol.Tcp)
    {
        var client = await _clients.CreateAuthorizedAsync(
            clientCode,
            clientName,
            cancellationToken,
            string.IsNullOrWhiteSpace(systemCode) ? "DEFAULT" : systemCode.Trim(),
            protocol).ConfigureAwait(false);
        _logs.Add(
            "INFO",
            "客户端管理",
            $"已在系统 {client.SystemCode} 登记客户端 {clientCode}（{clientName}，{protocol}）。");
    }

    public async Task UpdateClientAsync(
        string clientCode,
        string clientName,
        ClientProtocol protocol,
        CancellationToken cancellationToken = default)
    {
        var client = await _clients.UpdateAsync(
            clientCode,
            clientName,
            protocol,
            cancellationToken).ConfigureAwait(false);
        _logs.Add(
            "INFO",
            "客户端管理",
            $"客户端 {clientCode} 已更新为 {client.ClientName}（{client.Protocol}）。");
    }

    public async Task DeleteClientAsync(string clientCode, CancellationToken cancellationToken = default)
    {
        var client = await _clients.GetAsync(clientCode, cancellationToken).ConfigureAwait(false)
                     ?? throw new KeyNotFoundException($"客户端未登记：{clientCode}");
        if (_agents.Find(clientCode) is not null)
        {
            throw new InvalidOperationException($"客户端 {clientCode} 当前在线，请先停止通讯并断开客户端连接。");
        }

        var crossClientReferences = (await _clients.ListAsync(cancellationToken).ConfigureAwait(false))
            .Where(candidate => candidate.ClientCode != clientCode)
            .SelectMany(candidate => candidate.Points
                .Where(point =>
                    point.Direction == SharedPointDirection.Output &&
                    point.SourceInputPointId?.StartsWith($"{clientCode}:", StringComparison.Ordinal) == true)
                .Select(point => $"{candidate.ClientCode}:{point.PointId}"))
            .ToArray();
        if (crossClientReferences.Length > 0)
        {
            throw new InvalidOperationException(
                $"客户端 {clientCode} 仍被其他 OUTPUT 测点引用：" +
                string.Join("、", crossClientReferences.Take(5)));
        }

        await _latestValues.RemovePointsAsync(
            clientCode,
            client.Points.Select(point => point.PointId).ToArray(),
            cancellationToken).ConfigureAwait(false);
        _router.RemoveClient(clientCode);
        await _clients.DeleteAsync(clientCode, cancellationToken).ConfigureAwait(false);
        _communicationStates.Clear(clientCode);
        _logs.Add("INFO", "客户端管理", $"已删除客户端 {clientCode}（{client.ClientName}）。");
    }

    public async Task StartCommunicationAsync(CancellationToken cancellationToken = default)
    {
        _communicationGate.Start();
        _logs.Add("INFO", "通讯控制", "服务端通讯已启动，允许客户端连接。");
    }

    public async Task StopCommunicationAsync(CancellationToken cancellationToken = default)
    {
        _communicationGate.Stop();
        _telemetry.CancelPendingBatches();
        var clients = await _clients.ListAsync(cancellationToken).ConfigureAwait(false);
        foreach (var client in clients)
        {
            await _clients.SetDesiredRunningAsync(
                client.ClientCode,
                false,
                cancellationToken).ConfigureAwait(false);
        }

        foreach (var session in _agents.List())
        {
            try
            {
                await session.SendAsync(new ServerMessage
                {
                    StopCommunication = new StopCommunication { CommandId = Guid.NewGuid().ToString("N") },
                    SentUnixNs = UnixTime.NowNanoseconds()
                }, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                _logs.Add("WARN", "通讯控制", $"停止客户端 {session.ClientCode} 时发送失败：{exception.Message}");
            }
            finally
            {
                await session.DisconnectAsync(cancellationToken).ConfigureAwait(false);
            }
        }

        _logs.Add("INFO", "通讯控制", "服务端通讯已停止，现有客户端已断开，新连接将被拒绝。");
    }

    public async Task SaveClientPointsAsync(
        string clientCode,
        IReadOnlyList<SharedPointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        var previous = await _clients.GetAsync(clientCode, cancellationToken).ConfigureAwait(false)
                       ?? throw new KeyNotFoundException($"客户端未登记：{clientCode}");
        var communicationState = _communicationStates.Get(clientCode)?.State;
        if (_agents.Find(clientCode) is not null &&
            (previous.DesiredRunning ||
             communicationState is SharedClientConnectionState.Connected or SharedClientConnectionState.Connecting))
        {
            throw new InvalidOperationException("客户端正在运行通讯，必须先停止通讯才能修改点表。");
        }

        var normalizedPoints = points.Select(NormalizePoint).ToArray();
        var stored = await _clients.SavePointsAsync(clientCode, normalizedPoints, cancellationToken).ConfigureAwait(false);
        var remainingKeys = stored.Points
            .Select(point => (point.PointId, point.Direction))
            .ToHashSet();
        var removedEndpoints = previous.Points
            .Where(point => !remainingKeys.Contains((point.PointId, point.Direction)))
            .ToArray();
        await _latestValues.RemoveEndpointsAsync(
            clientCode,
            removedEndpoints,
            cancellationToken).ConfigureAwait(false);
        _router.RegisterClient(clientCode, stored.Points);
        var command = new ConfigurePoints
        {
            CatalogRevision = stored.CatalogRevision,
            Message = "服务端已更新点表配置。"
        };
        command.Points.AddRange(stored.Points.Select(ContractMapper.ToContract));
        var sent = await _agents.SendAsync(clientCode, new ServerMessage
        {
            ConfigurePoints = command,
            SentUnixNs = UnixTime.NowNanoseconds()
        }, cancellationToken).ConfigureAwait(false);
        _logs.Add(
            "INFO",
            "客户端管理",
            $"已保存客户端 {clientCode} 的点表，共 {normalizedPoints.Length} 点；在线下发：{(sent ? "是" : "否")}。");
    }

    public async Task WritePointAsync(
        string clientCode,
        string pointId,
        object? value,
        CancellationToken cancellationToken = default)
    {
        var client = await _clients.GetAsync(clientCode, cancellationToken).ConfigureAwait(false)
                     ?? throw new KeyNotFoundException($"客户端未登记：{clientCode}");
        var point = client.Points.FirstOrDefault(candidate =>
                        candidate.PointId == pointId &&
                        candidate.Direction == SharedPointDirection.Output)
                    ?? throw new KeyNotFoundException($"测点未登记：{pointId}");

        await _telemetry.SendManualWriteAsync(clientCode, point, value, cancellationToken).ConfigureAwait(false);
    }

    public Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
        string clientCode,
        string pointId,
        SharedPointDirection direction,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default) =>
        _history.QueryAsync(clientCode, pointId, direction, from, to, limit, cancellationToken);

    private async Task ValidatePointReferencesAsync(
        string clientCode,
        IReadOnlyList<SharedPointDefinition> points,
        CancellationToken cancellationToken)
    {
        var catalogs = (await _clients.ListAsync(cancellationToken).ConfigureAwait(false))
            .ToDictionary(
                client => client.ClientCode,
                client => (IReadOnlyList<SharedPointDefinition>)client.Points,
                StringComparer.Ordinal);
        catalogs[clientCode] = points;

        foreach (var (targetClientCode, catalog) in catalogs)
        {
            foreach (var output in catalog.Where(point => point.Direction == SharedPointDirection.Output))
            {
                var source = output.SourceInputPointId;
                if (string.IsNullOrWhiteSpace(source))
                {
                    throw new InvalidOperationException($"OUTPUT 测点 {output.PointId} 缺少源 INPUT。");
                }

                source = source.Trim();
                var separator = source.IndexOf(':');
                var sourceClientCode = separator > 0 ? source[..separator] : targetClientCode;
                var sourcePointId = separator > 0 ? source[(separator + 1)..] : source;
                if (string.IsNullOrWhiteSpace(sourceClientCode) ||
                    string.IsNullOrWhiteSpace(sourcePointId))
                {
                    throw new InvalidOperationException(
                        $"OUTPUT 测点 {targetClientCode}:{output.PointId} 的源 INPUT 引用格式无效：{source}");
                }

                if (!catalogs.TryGetValue(sourceClientCode, out var sourceCatalog))
                {
                    throw new InvalidOperationException(
                        $"OUTPUT 测点 {targetClientCode}:{output.PointId} 引用的客户端不存在：{sourceClientCode}");
                }

                var sourcePoint = sourceCatalog.FirstOrDefault(point =>
                    string.Equals(point.PointId, sourcePointId, StringComparison.Ordinal));
                if (sourcePoint is null)
                {
                    throw new InvalidOperationException(
                        $"OUTPUT 测点 {targetClientCode}:{output.PointId} 引用的 INPUT 不存在：" +
                        $"{sourceClientCode}:{sourcePointId}");
                }

                if (sourcePoint.Direction != SharedPointDirection.Input)
                {
                    throw new InvalidOperationException(
                        $"OUTPUT 测点 {targetClientCode}:{output.PointId} 必须引用 INPUT，" +
                        $"{sourceClientCode}:{sourcePointId} 当前为 {sourcePoint.Direction}。");
                }

                if (sourcePoint.DataType != output.DataType)
                {
                    throw new InvalidOperationException(
                        $"OUTPUT 测点 {targetClientCode}:{output.PointId} 与源 INPUT " +
                        $"{sourceClientCode}:{sourcePointId} 的数据类型不一致。");
                }
            }
        }
    }

    private static SharedPointDefinition NormalizePoint(SharedPointDefinition point)
    {
        var source = string.IsNullOrWhiteSpace(point.SourceInputPointId)
            ? null
            : point.SourceInputPointId.Trim();
        if (source?.IndexOf(':') > 0)
        {
            var separator = source.IndexOf(':');
            source = $"{source[..separator].Trim()}:{source[(separator + 1)..].Trim()}";
        }

        return new SharedPointDefinition
        {
            PointId = point.PointId?.Trim() ?? string.Empty,
            PointName = point.PointName?.Trim() ?? string.Empty,
            Address = point.Address?.Trim() ?? string.Empty,
            DataType = point.DataType,
            Unit = point.Unit?.Trim() ?? string.Empty,
            Direction = point.Direction,
            SourceInputPointId = source,
            Deadband = Math.Max(0, point.Deadband),
            Group = string.IsNullOrWhiteSpace(point.Group) ? "默认" : point.Group.Trim()
        };
    }
}
