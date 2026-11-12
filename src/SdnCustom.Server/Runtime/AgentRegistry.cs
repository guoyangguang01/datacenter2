using System.Collections.Concurrent;
using Grpc.Core;
using SdnCustom.Contracts.Agent.V1;

namespace SdnCustom.Server.Runtime;

public interface IAgentSession : IAsyncDisposable
{
    string ClientCode { get; }
    string InstanceId { get; }
    DateTimeOffset ConnectedAt { get; }
    TimeSpan IdleTime { get; }
    void Touch();
    Task SendAsync(ServerMessage message, CancellationToken cancellationToken = default);
}

public sealed class AgentSession : IAgentSession, IAsyncDisposable
{
    private readonly IServerStreamWriter<ServerMessage> _writer;
    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private long _lastActivityTimestamp = System.Diagnostics.Stopwatch.GetTimestamp();

    public AgentSession(string clientCode, string instanceId, IServerStreamWriter<ServerMessage> writer)
    {
        ClientCode = clientCode;
        InstanceId = instanceId;
        _writer = writer;
        ConnectedAt = DateTimeOffset.UtcNow;
    }

    public string ClientCode { get; }
    public string InstanceId { get; }
    public DateTimeOffset ConnectedAt { get; }
    public TimeSpan IdleTime => System.Diagnostics.Stopwatch.GetElapsedTime(
        Interlocked.Read(ref _lastActivityTimestamp));

    public void Touch() => Interlocked.Exchange(
        ref _lastActivityTimestamp,
        System.Diagnostics.Stopwatch.GetTimestamp());

    public async Task SendAsync(ServerMessage message, CancellationToken cancellationToken = default)
    {
        await _writeGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await _writer.WriteAsync(message, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _writeGate.Release();
        }
    }

    public ValueTask DisposeAsync()
    {
        _writeGate.Dispose();
        return ValueTask.CompletedTask;
    }
}

public interface IAgentRegistry
{
    bool TryRegister(IAgentSession session, out string reason);
    bool Unregister(string clientCode, string instanceId);
    IAgentSession? Find(string clientCode);
    IReadOnlyList<IAgentSession> List();
    Task<bool> SendAsync(string clientCode, ServerMessage message, CancellationToken cancellationToken = default);
}

public sealed class AgentRegistry : IAgentRegistry
{
    private readonly ConcurrentDictionary<string, IAgentSession> _sessions = new(StringComparer.Ordinal);

    public bool TryRegister(IAgentSession session, out string reason)
    {
        if (!_sessions.TryAdd(session.ClientCode, session))
        {
            reason = $"客户端 {session.ClientCode} 已在线，拒绝重复连接。";
            return false;
        }

        reason = string.Empty;
        return true;
    }

    public bool Unregister(string clientCode, string instanceId)
    {
        return _sessions.TryGetValue(clientCode, out var session) &&
               session.InstanceId == instanceId &&
               _sessions.TryRemove(clientCode, out _);
    }

    public IAgentSession? Find(string clientCode) =>
        _sessions.TryGetValue(clientCode, out var session) ? session : null;

    public IReadOnlyList<IAgentSession> List() => _sessions.Values.ToArray();

    public async Task<bool> SendAsync(
        string clientCode,
        ServerMessage message,
        CancellationToken cancellationToken = default)
    {
        var session = Find(clientCode);
        if (session is null)
        {
            return false;
        }

        await session.SendAsync(message, cancellationToken).ConfigureAwait(false);
        return true;
    }
}
