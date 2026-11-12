using SdnCustom.Contracts.Agent.V1;
using SdnCustom.Contracts;
using SdnCustom.Server.History;
using SdnCustom.Server.Persistence;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;
using SharedPointDirection = SdnCustom.Shared.Model.PointDirection;
using SharedPointDefinition = SdnCustom.Shared.Model.PointDefinition;

namespace SdnCustom.Server.Runtime;

public interface IServerControlService
{
    Task CreateClientAsync(string clientCode, string clientName, CancellationToken cancellationToken = default);
    Task RenameClientAsync(string clientCode, string clientName, CancellationToken cancellationToken = default);
    Task DeleteClientAsync(string clientCode, CancellationToken cancellationToken = default);
    Task StartClientAsync(string clientCode, CancellationToken cancellationToken = default);
    Task StopClientAsync(string clientCode, CancellationToken cancellationToken = default);
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
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default);
}

public sealed class ServerControlService : IServerControlService
{
    private readonly IClientSystemStore _clients;
    private readonly IAgentRegistry _agents;
    private readonly IHistoryStore _history;
    private readonly IServerLogStore _logs;
    private readonly ITelemetryService _telemetry;
    private readonly IPointRouter _router;
    private readonly ILatestValueStore _latestValues;

    public ServerControlService(
        IClientSystemStore clients,
        IAgentRegistry agents,
        IHistoryStore history,
        IServerLogStore logs,
        ITelemetryService telemetry,
        IPointRouter router,
        ILatestValueStore latestValues)
    {
        _clients = clients;
        _agents = agents;
        _history = history;
        _logs = logs;
        _telemetry = telemetry;
        _router = router;
        _latestValues = latestValues;
    }

    public async Task CreateClientAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default)
    {
        await _clients.CreateAuthorizedAsync(clientCode, clientName, cancellationToken).ConfigureAwait(false);
        _logs.Add("INFO", "客户端管理", $"已登记客户端 {clientCode}（{clientName}）。");
    }

    public async Task RenameClientAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default)
    {
        var client = await _clients.RenameAsync(clientCode, clientName, cancellationToken).ConfigureAwait(false);
        _logs.Add("INFO", "客户端管理", $"客户端 {clientCode} 已改名为 {client.ClientName}。");
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
                    point.Direction == SharedPointDirection.Input &&
                    point.SourceOutputPointId?.StartsWith($"{clientCode}:", StringComparison.Ordinal) == true)
                .Select(point => $"{candidate.ClientCode}:{point.PointId}"))
            .ToArray();
        if (crossClientReferences.Length > 0)
        {
            throw new InvalidOperationException(
                $"客户端 {clientCode} 仍被其他 INPUT 测点引用：" +
                string.Join("、", crossClientReferences.Take(5)));
        }

        await _latestValues.RemovePointsAsync(
            clientCode,
            client.Points.Select(point => point.PointId).ToArray(),
            cancellationToken).ConfigureAwait(false);
        _router.RemoveClient(clientCode);
        await _clients.DeleteAsync(clientCode, cancellationToken).ConfigureAwait(false);
        _logs.Add("INFO", "客户端管理", $"已删除客户端 {clientCode}（{client.ClientName}）。");
    }

    public async Task StartClientAsync(string clientCode, CancellationToken cancellationToken = default)
    {
        if (_agents.Find(clientCode) is null)
        {
            throw new InvalidOperationException($"客户端 {clientCode} 当前不在线。");
        }

        await _clients.SetDesiredRunningAsync(clientCode, true, cancellationToken).ConfigureAwait(false);
        var sent = await _agents.SendAsync(clientCode, new ServerMessage
        {
            StartCommunication = new StartCommunication { CommandId = Guid.NewGuid().ToString("N") },
            SentUnixNs = UnixTime.NowNanoseconds()
        }, cancellationToken).ConfigureAwait(false);
        if (!sent)
        {
            throw new InvalidOperationException($"客户端 {clientCode} 已断开，启动命令未送达。");
        }

        _logs.Add("INFO", "通讯控制", $"已请求启动客户端 {clientCode}。");
    }

    public async Task StopClientAsync(string clientCode, CancellationToken cancellationToken = default)
    {
        await _clients.SetDesiredRunningAsync(clientCode, false, cancellationToken).ConfigureAwait(false);
        var sent = await _agents.SendAsync(clientCode, new ServerMessage
        {
            StopCommunication = new StopCommunication { CommandId = Guid.NewGuid().ToString("N") },
            SentUnixNs = UnixTime.NowNanoseconds()
        }, cancellationToken).ConfigureAwait(false);
        if (sent)
        {
            _logs.Add("INFO", "通讯控制", $"已请求停止客户端 {clientCode}。");
        }
    }

    public async Task SaveClientPointsAsync(
        string clientCode,
        IReadOnlyList<SharedPointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        var previous = await _clients.GetAsync(clientCode, cancellationToken).ConfigureAwait(false)
                       ?? throw new KeyNotFoundException($"客户端未登记：{clientCode}");
        if (previous.DesiredRunning && _agents.Find(clientCode) is not null)
        {
            throw new InvalidOperationException("客户端正在运行通讯，必须先停止通讯才能修改点表。");
        }

        ValidatePointReferences(clientCode, points);
        var stored = await _clients.SavePointsAsync(clientCode, points, cancellationToken).ConfigureAwait(false);
        var remainingIds = stored.Points.Select(point => point.PointId).ToHashSet(StringComparer.Ordinal);
        var removedIds = previous.Points
            .Select(point => point.PointId)
            .Where(pointId => !remainingIds.Contains(pointId))
            .ToArray();
        await _latestValues.RemovePointsAsync(clientCode, removedIds, cancellationToken).ConfigureAwait(false);
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
            $"已保存客户端 {clientCode} 的点表，共 {points.Count} 点；在线下发：{(sent ? "是" : "否")}。");
    }

    public async Task WritePointAsync(
        string clientCode,
        string pointId,
        object? value,
        CancellationToken cancellationToken = default)
    {
        var client = await _clients.GetAsync(clientCode, cancellationToken).ConfigureAwait(false)
                     ?? throw new KeyNotFoundException($"客户端未登记：{clientCode}");
        var point = client.Points.FirstOrDefault(candidate => candidate.PointId == pointId)
                    ?? throw new KeyNotFoundException($"测点未登记：{pointId}");
        if (point.Direction != SharedPointDirection.Input)
        {
            throw new InvalidOperationException($"测点 {pointId} 不是 INPUT 测点。");
        }

        await _telemetry.SendManualWriteAsync(clientCode, point, value, cancellationToken).ConfigureAwait(false);
    }

    public Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
        string clientCode,
        string pointId,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default) =>
        _history.QueryAsync(clientCode, pointId, from, to, limit, cancellationToken);

    private static void ValidatePointReferences(string clientCode, IReadOnlyList<SharedPointDefinition> points)
    {
        var ids = points.Select(point => point.PointId).ToHashSet(StringComparer.Ordinal);
        foreach (var point in points.Where(point => point.Direction == SharedPointDirection.Input))
        {
            var source = point.SourceOutputPointId;
            if (string.IsNullOrWhiteSpace(source))
            {
                throw new InvalidOperationException($"INPUT 测点 {point.PointId} 缺少源 OUTPUT。");
            }

            var localSource = source.Contains(':', StringComparison.Ordinal)
                ? source[(source.IndexOf(':') + 1)..]
                : source;
            var sourceClient = source.Contains(':', StringComparison.Ordinal)
                ? source[..source.IndexOf(':')]
                : clientCode;
            if (sourceClient == clientCode && !ids.Contains(localSource))
            {
                throw new InvalidOperationException(
                    $"INPUT 测点 {point.PointId} 引用的本客户端 OUTPUT 不存在：{localSource}");
            }
        }
    }
}
