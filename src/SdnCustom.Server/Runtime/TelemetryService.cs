using System.Collections.Concurrent;
using SdnCustom.Contracts;
using SdnCustom.Contracts.Agent.V1;
using SdnCustom.Server.History;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;
using SharedPointDefinition = SdnCustom.Shared.Model.PointDefinition;
using SharedPointQuality = SdnCustom.Shared.Model.PointQuality;
using SharedPointSample = SdnCustom.Shared.Model.PointSample;
using SharedPointValue = SdnCustom.Shared.Model.PointValue;

namespace SdnCustom.Server.Runtime;

public interface ITelemetryService
{
    Task HandleBatchAsync(
        string clientCode,
        IReadOnlyList<SharedPointSample> values,
        bool isSnapshot,
        CancellationToken cancellationToken = default);
    Task HandleWriteResultsAsync(
        string clientCode,
        WriteResults results,
        CancellationToken cancellationToken = default);
    Task SendManualWriteAsync(
        string clientCode,
        SharedPointDefinition point,
        object? value,
        CancellationToken cancellationToken = default);
    Task MarkClientOfflineAsync(
        string clientCode,
        IReadOnlyList<SharedPointDefinition> points,
        CancellationToken cancellationToken = default);
    void CancelPendingBatches();
}

public sealed class TelemetryService : ITelemetryService
{
    private static readonly TimeSpan ManualWriteTimeout = TimeSpan.FromSeconds(30);
    private readonly ILatestValueStore _latestValues;
    private readonly IHistoryStore _history;
    private readonly IPointRouter _router;
    private readonly IAgentRegistry _agents;
    private readonly IServerCommunicationGate _communicationGate;
    private readonly IServerLogStore _logs;
    private readonly ConcurrentDictionary<string, PendingBatch> _pendingBatches = new(StringComparer.Ordinal);

    public TelemetryService(
        ILatestValueStore latestValues,
        IHistoryStore history,
        IPointRouter router,
        IAgentRegistry agents,
        IServerCommunicationGate communicationGate,
        IServerLogStore logs)
    {
        _latestValues = latestValues;
        _history = history;
        _router = router;
        _agents = agents;
        _communicationGate = communicationGate;
        _logs = logs;
    }

    public async Task HandleBatchAsync(
        string clientCode,
        IReadOnlyList<SharedPointSample> values,
        bool isSnapshot,
        CancellationToken cancellationToken = default)
    {
        if (!_communicationGate.IsRunning)
        {
            return;
        }

        await _latestValues.UpdateAsync(values, cancellationToken).ConfigureAwait(false);
        if (!isSnapshot)
        {
            await _history.EnqueueAsync(values, cancellationToken).ConfigureAwait(false);
        }

        foreach (var input in values)
        {
            var targets = _router.ResolveOutputTargets(input);
            foreach (var group in targets.GroupBy(target => target.ClientCode, StringComparer.Ordinal))
            {
                if (!_communicationGate.IsRunning)
                {
                    return;
                }

                var commandId = Guid.NewGuid().ToString("N");
                var routeTargets = group.ToArray();
                var pendingItems = routeTargets
                    .Select(target => new PendingBatchItem(target.Point, input))
                    .ToArray();
                _pendingBatches[commandId] = new PendingBatch(group.Key, pendingItems);
                var command = new ServerMessage
                {
                    Sequence = (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    SentUnixNs = UnixTime.NowNanoseconds(),
                    WritePoints = new WritePoints
                    {
                        CommandId = commandId
                    }
                };
                command.WritePoints.Points.AddRange(routeTargets.Select(target =>
                    new WritePointItem
                    {
                        PointId = target.Point.PointId,
                        Value = ContractMapper.ToContractValue(input.Value.Value),
                        TimestampUnixNs = input.Value.TimestampUnixNanoseconds,
                        SourceClientCode = input.ClientCode,
                        SourcePointId = input.PointId
                    }));
                var sent = await _agents.SendAsync(group.Key, command, cancellationToken).ConfigureAwait(false);
                if (!sent)
                {
                    _pendingBatches.TryRemove(commandId, out _);
                    _logs.Add(
                        "WARN",
                        "传播",
                        $"目标客户端 {group.Key} 离线，{input.GlobalKey} 的 {routeTargets.Length} 条输出未写出。");
                }
            }
        }
    }

    public async Task HandleWriteResultsAsync(
        string clientCode,
        WriteResults results,
        CancellationToken cancellationToken = default)
    {
        if (!_communicationGate.IsRunning)
        {
            return;
        }

        if (!_pendingBatches.TryGetValue(results.CommandId, out var pending))
        {
            _logs.Add("WARN", "传播", $"收到未知批量写入回执 {results.CommandId}（客户端 {clientCode}）。");
            return;
        }

        if (!string.Equals(pending.TargetClientCode, clientCode, StringComparison.Ordinal))
        {
            _logs.Add(
                "WARN",
                "传播",
                $"批量写入回执客户端不匹配：期望 {pending.TargetClientCode}，实际 {clientCode}。");
            return;
        }

        _pendingBatches.TryRemove(results.CommandId, out var completedBatch);
        completedBatch?.Completion?.TrySetResult(results);
        var resultByPoint = results.Results
            .GroupBy(result => result.PointId, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First(), StringComparer.Ordinal);
        var intents = new List<SharedPointSample>(pending.Items.Count);
        foreach (var pendingItem in pending.Items)
        {
            var result = resultByPoint.GetValueOrDefault(pendingItem.TargetPoint.PointId);
            var success = result?.Success == true;
            var error = result?.ErrorMessage ?? "客户端未返回该点写入结果。";
            var intent = new SharedPointSample(
                pending.TargetClientCode,
                pendingItem.TargetPoint.PointId,
                new SharedPointValue(
                    pendingItem.Source.Value.Value,
                    pendingItem.Source.Value.Quality,
                    pendingItem.Source.Value.TimestampUnixNanoseconds),
                pendingItem.Source.ClientCode,
                pendingItem.Source.PointId);
            intents.Add(intent);
            _logs.Add(
                success ? "INFO" : "ERROR",
                "传播",
                success
                    ? $"写出成功：{pendingItem.Source.GlobalKey} -> {pending.TargetClientCode}:{pendingItem.TargetPoint.PointId}"
                    : $"写出失败：{pendingItem.Source.GlobalKey} -> {pending.TargetClientCode}:{pendingItem.TargetPoint.PointId}，{error}");
        }

        if (intents.Count > 0)
        {
            await _latestValues.UpdateAsync(intents, cancellationToken).ConfigureAwait(false);
            await _history.EnqueueAsync(intents, cancellationToken).ConfigureAwait(false);
        }
    }

    public async Task SendManualWriteAsync(
        string clientCode,
        SharedPointDefinition point,
        object? value,
        CancellationToken cancellationToken = default)
    {
        if (!_communicationGate.IsRunning)
        {
            throw new InvalidOperationException("服务端通讯未启动，禁止发送写入命令。");
        }

        var timestamp = UnixTime.NowNanoseconds();
        var source = new SharedPointSample(
            clientCode,
            point.PointId,
            new SharedPointValue(
                PointTypeConverter.Convert(value, point.DataType),
                SharedPointQuality.Good,
                timestamp));
        var commandId = Guid.NewGuid().ToString("N");
        var completion = new TaskCompletionSource<WriteResults>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        _pendingBatches[commandId] = new PendingBatch(
            clientCode,
            [new PendingBatchItem(point, source)],
            completion);
        var sent = await _agents.SendAsync(clientCode, new ServerMessage
        {
            WritePoints = new WritePoints
            {
                CommandId = commandId,
                Points =
                {
                    new WritePointItem
                    {
                        PointId = point.PointId,
                        Value = ContractMapper.ToContractValue(source.Value.Value),
                        TimestampUnixNs = timestamp,
                        SourceClientCode = clientCode,
                        SourcePointId = point.PointId
                    }
                }
            },
            SentUnixNs = timestamp
        }, cancellationToken).ConfigureAwait(false);
        if (!sent)
        {
            _pendingBatches.TryRemove(commandId, out _);
            throw new InvalidOperationException($"客户端 {clientCode} 当前不在线。");
        }

        try
        {
            var result = await completion.Task
                .WaitAsync(ManualWriteTimeout, cancellationToken)
                .ConfigureAwait(false);
            var itemResult = result.Results.FirstOrDefault(item => item.PointId == point.PointId);
            if (itemResult?.Success != true)
            {
                throw new InvalidOperationException(
                    string.IsNullOrWhiteSpace(itemResult?.ErrorMessage)
                        ? $"设备写入失败：{point.PointId}"
                        : $"设备写入失败：{itemResult.ErrorMessage}");
            }
        }
        catch
        {
            _pendingBatches.TryRemove(commandId, out _);
            throw;
        }
    }

    public async Task MarkClientOfflineAsync(
        string clientCode,
        IReadOnlyList<SharedPointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        foreach (var item in _pendingBatches
                     .Where(item => item.Value.TargetClientCode == clientCode)
                     .ToArray())
        {
            if (_pendingBatches.TryRemove(item.Key, out var removed))
            {
                removed.Completion?.TrySetException(
                    new IOException($"客户端 {clientCode} 已离线，写入结果不可用。"));
            }
        }

        await _latestValues.MarkInputsCommLostAsync(clientCode, points, cancellationToken).ConfigureAwait(false);
        _router.RemoveClient(clientCode);
        _logs.Add("WARN", "连接", $"客户端离线：{clientCode}");
    }

    public void CancelPendingBatches()
    {
        foreach (var item in _pendingBatches.ToArray())
        {
            if (_pendingBatches.TryRemove(item.Key, out var removed))
            {
                removed.Completion?.TrySetException(
                    new IOException("服务端通讯已停止，写入结果不可用。"));
            }
        }
    }

    private sealed record PendingBatch(
        string TargetClientCode,
        IReadOnlyList<PendingBatchItem> Items,
        TaskCompletionSource<WriteResults>? Completion = null);

    private sealed record PendingBatchItem(
        SharedPointDefinition TargetPoint,
        SharedPointSample Source);
}
