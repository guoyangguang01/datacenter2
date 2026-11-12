using System.Collections.Concurrent;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Runtime;

public sealed record ClientCommunicationSnapshot(
    ClientConnectionState State,
    string Message,
    DateTimeOffset UpdatedAt);

public interface IClientCommunicationStateStore
{
    void Set(string clientCode, ClientConnectionState state, string message);
    ClientCommunicationSnapshot? Get(string clientCode);
    void Clear(string clientCode);
}

public sealed class ClientCommunicationStateStore : IClientCommunicationStateStore
{
    private readonly ConcurrentDictionary<string, ClientCommunicationSnapshot> _states =
        new(StringComparer.Ordinal);

    public void Set(string clientCode, ClientConnectionState state, string message) =>
        _states[clientCode] = new ClientCommunicationSnapshot(state, message, DateTimeOffset.UtcNow);

    public ClientCommunicationSnapshot? Get(string clientCode) =>
        _states.TryGetValue(clientCode, out var state) ? state : null;

    public void Clear(string clientCode) => _states.TryRemove(clientCode, out _);
}
