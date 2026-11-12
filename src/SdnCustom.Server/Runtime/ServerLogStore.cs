using System.Collections.Concurrent;

namespace SdnCustom.Server.Runtime;

public sealed record ServerLogEntry(DateTimeOffset Timestamp, string Level, string Category, string Message);

public interface IServerLogStore
{
    void Add(string level, string category, string message);
    IReadOnlyList<ServerLogEntry> GetRecent(int count = 500);
}

public sealed class ServerLogStore : IServerLogStore
{
    private const int Capacity = 2000;
    private readonly ConcurrentQueue<ServerLogEntry> _entries = new();

    public void Add(string level, string category, string message)
    {
        _entries.Enqueue(new ServerLogEntry(DateTimeOffset.Now, level, category, message));
        while (_entries.Count > Capacity && _entries.TryDequeue(out _))
        {
        }
    }

    public IReadOnlyList<ServerLogEntry> GetRecent(int count = 500) =>
        _entries.TakeLast(Math.Clamp(count, 1, Capacity)).ToArray();
}
