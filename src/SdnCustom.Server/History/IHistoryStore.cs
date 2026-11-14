using SdnCustom.Shared.Model;

namespace SdnCustom.Server.History;

public sealed record HistoryPoint(DateTimeOffset Timestamp, object? Value, PointQuality Quality);

public interface IHistoryStore : IAsyncDisposable
{
    ValueTask EnqueueAsync(IReadOnlyList<PointSample> samples, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<HistoryPoint>> QueryAsync(
        string clientCode,
        string pointId,
        PointDirection direction,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default);
}
