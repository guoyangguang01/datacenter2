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
    Task HandleWriteResultAsync(
        string clientCode,
        WriteResult result,
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
}

public sealed class TelemetryService : ITelemetryService
{
    private static readonly TimeSpan ManualWriteTimeout = TimeSpan.FromSeconds(30);
    private readonly ILatestValueStore _latestValues;
    private readonly IHistoryStore _history;
    private readonly IPointRouter _router;
    private readonly IAgentRegistry _agents;
    private readonly IServerLogStore _logs;
    private readonly ConcurrentDictionary<string, PendingWrite> _pendingWrites = new(StringComparer.Ordinal);

    public TelemetryService(
        ILatestValueStore latestValues,
        IHistoryStore history,
        IPointRouter router,
        IAgentRegistry agents,
        IServerLogStore logs)
    {
        _latestValues = latestValues;
        _history = history;
        _router = router;
        _agents = agents;
        _logs = logs;
    }

    public async Task HandleBatchAsync(
        string clientCode,
        IReadOnlyList<SharedPointSample> values,
        bool isSnapshot,
        CancellationToken cancellationToken = default)
    {
        await _latestValues.UpdateAsync(values, cancellationToken).ConfigureAwait(false);
        if (!isSnapshot)
        {
            await _history.EnqueueAsync(values, cancellationToken).ConfigureAwait(false);
        }

        foreach (var input in values)
        {
            var targets = _router.ResolveOutputTargets(input);
            foreach (var target in targets)
            {
                var commandId = Guid.NewGuid().ToString("N");
                _pendingWrites[commandId] = new PendingWrite(target.ClientCode, target.Point, input);
                var command = new ServerMessage
                {
                    Sequence = (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    SentUnixNs = UnixTime.NowNanoseconds(),
                    WritePoint = new WritePoint
                    {
                        CommandId = commandId,
                        PointId = target.Point.PointId,
                        Value = ContractMapper.ToContractValue(input.Value.Value),
                        TimestampUnixNs = input.Value.TimestampUnixNanoseconds,
                        SourceClientCode = input.ClientCode,
                        SourcePointId = input.PointId
                    }
                };
                var sent = await _agents.SendAsync(target.ClientCode, command, cancellationToken).ConfigureAwait(false);
                if (!sent)
                {
                    _pendingWrites.TryRemove(commandId, out _);
                    _logs.Add(
                        "WARN",
                        "传播",
                        $"目标客户端 {target.ClientCode} 离线，{input.GlobalKey} -> {target.Point.PointId} 未写出。");
                }
            }
        }
    }

    public async Task HandleWriteResultAsync(
        string clientCode,
        WriteResult result,
        CancellationToken cancellationToken = default)
    {
        if (!_pendingWrites.TryGetValue(result.CommandId, out var pending))
        {
            _logs.Add("WARN", "传播", $"收到未知写入回执 {result.CommandId}（客户端 {clientCode}）。");
            return;
        }

        if (!string.Equals(pending.TargetClientCode, clientCode, StringComparison.Ordinal))
        {
            _logs.Add(
                "WARN",
                "传播",
                $"写入回执客户端不匹配：期望 {pending.TargetClientCode}，实际 {clientCode}。");
            return;
        }

        _pendingWrites.TryRemove(result.CommandId, out _);
        pending.Completion?.TrySetResult(result);
        var intent = new SharedPointSample(
            pending.TargetClientCode,
            pending.TargetPoint.PointId,
            new SharedPointValue(
                pending.Source.Value.Value,
                pending.Source.Value.Quality,
                pending.Source.Value.TimestampUnixNanoseconds),
            pending.Source.ClientCode,
            pending.Source.PointId);
        await _latestValues.UpdateAsync([intent], cancellationToken).ConfigureAwait(false);
        await _history.EnqueueAsync([intent], cancellationToken).ConfigureAwait(false);
        _logs.Add(
            result.Success ? "INFO" : "ERROR",
            "传播",
            result.Success
                ? $"写出成功：{pending.Source.GlobalKey} -> {pending.TargetClientCode}:{pending.TargetPoint.PointId}"
                : $"写出失败：{pending.Source.GlobalKey} -> {pending.TargetClientCode}:{pending.TargetPoint.PointId}，{result.ErrorMessage}");
    }

    public async Task SendManualWriteAsync(
        string clientCode,
        SharedPointDefinition point,
        object? value,
        CancellationToken cancellationToken = default)
    {
        var timestamp = UnixTime.NowNanoseconds();
        var source = new SharedPointSample(
            clientCode,
            point.PointId,
            new SharedPointValue(
                PointTypeConverter.Convert(value, point.DataType),
                SharedPointQuality.Good,
                timestamp));
        var commandId = Guid.NewGuid().ToString("N");
        var completion = new TaskCompletionSource<WriteResult>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        _pendingWrites[commandId] = new PendingWrite(clientCode, point, source, completion);
        var sent = await _agents.SendAsync(clientCode, new ServerMessage
        {
            WritePoint = new WritePoint
            {
                CommandId = commandId,
                PointId = point.PointId,
                Value = ContractMapper.ToContractValue(source.Value.Value),
                TimestampUnixNs = timestamp,
                SourceClientCode = clientCode,
                SourcePointId = point.PointId
            },
            SentUnixNs = timestamp
        }, cancellationToken).ConfigureAwait(false);
        if (!sent)
        {
            _pendingWrites.TryRemove(commandId, out _);
            throw new InvalidOperationException($"客户端 {clientCode} 当前不在线。");
        }

        try
        {
            var result = await completion.Task
                .WaitAsync(ManualWriteTimeout, cancellationToken)
                .ConfigureAwait(false);
            if (!result.Success)
            {
                throw new InvalidOperationException(
                    string.IsNullOrWhiteSpace(result.ErrorMessage)
                        ? $"设备写入失败：{point.PointId}"
                        : $"设备写入失败：{result.ErrorMessage}");
            }
        }
        catch
        {
            _pendingWrites.TryRemove(commandId, out _);
            throw;
        }
    }

    public async Task MarkClientOfflineAsync(
        string clientCode,
        IReadOnlyList<SharedPointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        foreach (var (commandId, pending) in _pendingWrites)
        {
            if (!string.Equals(pending.TargetClientCode, clientCode, StringComparison.Ordinal) ||
                !_pendingWrites.TryRemove(commandId, out var removed))
            {
                continue;
            }

            removed.Completion?.TrySetException(
                new IOException($"客户端 {clientCode} 已离线，写入结果不可用。"));
        }

        await _latestValues.MarkInputsCommLostAsync(clientCode, points, cancellationToken).ConfigureAwait(false);
        _router.RemoveClient(clientCode);
        _logs.Add("WARN", "连接", $"客户端离线：{clientCode}");
    }

    private sealed record PendingWrite(
        string TargetClientCode,
        SharedPointDefinition TargetPoint,
        SharedPointSample Source,
        TaskCompletionSource<WriteResult>? Completion = null);
}
