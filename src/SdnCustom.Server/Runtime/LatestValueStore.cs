using System.Collections.Concurrent;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using SdnCustom.Server.Persistence;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Runtime;

public interface ILatestValueStore
{
    Task InitializeAsync(CancellationToken cancellationToken = default);
    Task UpdateAsync(IReadOnlyList<PointSample> samples, CancellationToken cancellationToken = default);
    Task MarkInputsCommLostAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default);
    Task RemovePointsAsync(
        string clientCode,
        IReadOnlyList<string> pointIds,
        CancellationToken cancellationToken = default);
    Task RemoveEndpointsAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> endpoints,
        CancellationToken cancellationToken = default);
    IReadOnlyList<PointSample> GetAll();
    IReadOnlyList<PointSample> GetClient(string clientCode);
    event EventHandler<IReadOnlyList<PointSample>>? ValuesChanged;
}

public sealed class LatestValueStore(IDbContextFactory<ServerDbContext> contextFactory) : ILatestValueStore
{
    private readonly ConcurrentDictionary<(string ClientCode, string PointId, PointDirection Direction), PointSample> _values = new();
    private readonly SemaphoreSlim _persistenceGate = new(1, 1);

    public event EventHandler<IReadOnlyList<PointSample>>? ValuesChanged;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entities = await context.LatestValues.AsNoTracking().ToListAsync(cancellationToken).ConfigureAwait(false);
        foreach (var entity in entities)
        {
            var value = DeserializeValue(entity.ValueJson);
            var direction = Enum.TryParse<PointDirection>(entity.Direction, true, out var parsedDirection)
                ? parsedDirection
                : string.IsNullOrWhiteSpace(entity.SourcePointId)
                    ? PointDirection.Input
                    : PointDirection.Output;
            _values[(entity.ClientCode, entity.PointId, direction)] = new PointSample(
                entity.ClientCode,
                entity.PointId,
                new PointValue(
                    value,
                    Enum.TryParse<PointQuality>(entity.Quality, true, out var quality)
                        ? quality
                        : PointQuality.Bad,
                    entity.TimestampUnixNanoseconds),
                string.IsNullOrWhiteSpace(entity.SourceClientCode) ? null : entity.SourceClientCode,
                string.IsNullOrWhiteSpace(entity.SourcePointId) ? null : entity.SourcePointId);
        }
    }

    public async Task UpdateAsync(
        IReadOnlyList<PointSample> samples,
        CancellationToken cancellationToken = default)
    {
        if (samples.Count == 0)
        {
            return;
        }

        await _persistenceGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            foreach (var sample in samples)
            {
                _values[(sample.ClientCode, sample.PointId, ResolveDirection(sample))] = sample;
            }

            await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
            foreach (var sample in samples)
            {
                var direction = ResolveDirection(sample);
                var entity = await context.LatestValues.FindAsync(
                    [sample.ClientCode, sample.PointId, direction.ToString()],
                    cancellationToken).ConfigureAwait(false);
                if (entity is null)
                {
                    entity = new LatestValueEntity
                    {
                        ClientCode = sample.ClientCode,
                        PointId = sample.PointId,
                        Direction = direction.ToString()
                    };
                    context.LatestValues.Add(entity);
                }

                entity.ValueJson = sample.Value.Value is null
                    ? null
                    : JsonSerializer.Serialize(sample.Value.Value);
                entity.Quality = sample.Value.Quality.ToString();
                entity.Direction = direction.ToString();
                entity.TimestampUnixNanoseconds = sample.Value.TimestampUnixNanoseconds;
                entity.SourceClientCode = sample.SourceClientCode ?? string.Empty;
                entity.SourcePointId = sample.SourcePointId ?? string.Empty;
            }

            await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _persistenceGate.Release();
        }

        ValuesChanged?.Invoke(this, samples);
    }

    public async Task MarkInputsCommLostAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        var now = UnixTime.NowNanoseconds();
        var values = points
            .Where(point => point.Direction == PointDirection.Input)
            .Select(point => new PointSample(
                clientCode,
                point.PointId,
                PointValue.CommLost(now)))
            .ToArray();
        await UpdateAsync(values, cancellationToken).ConfigureAwait(false);
    }

    public async Task RemovePointsAsync(
        string clientCode,
        IReadOnlyList<string> pointIds,
        CancellationToken cancellationToken = default)
    {
        if (pointIds.Count == 0)
        {
            return;
        }

        await _persistenceGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            foreach (var pointId in pointIds)
            {
                foreach (var role in Enum.GetValues<PointDirection>())
                {
                    _values.TryRemove((clientCode, pointId, role), out _);
                }
            }

            await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
            var entities = await context.LatestValues
                .Where(entity => entity.ClientCode == clientCode && pointIds.Contains(entity.PointId))
                .ToListAsync(cancellationToken)
                .ConfigureAwait(false);
            context.LatestValues.RemoveRange(entities);
            await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _persistenceGate.Release();
        }
    }

    public async Task RemoveEndpointsAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> endpoints,
        CancellationToken cancellationToken = default)
    {
        if (endpoints.Count == 0)
        {
            return;
        }

        var keys = endpoints
            .Select(endpoint => (endpoint.PointId, endpoint.Direction))
            .ToHashSet();
        var pointIds = keys.Select(key => key.PointId).ToArray();
        await _persistenceGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            foreach (var key in keys)
            {
                _values.TryRemove((clientCode, key.PointId, key.Direction), out _);
            }

            await using var context = await contextFactory.CreateDbContextAsync(cancellationToken)
                .ConfigureAwait(false);
            var entities = await context.LatestValues
                .Where(entity => entity.ClientCode == clientCode && pointIds.Contains(entity.PointId))
                .ToListAsync(cancellationToken)
                .ConfigureAwait(false);
            var removed = entities.Where(entity =>
                Enum.TryParse<PointDirection>(entity.Direction, true, out var direction) &&
                keys.Contains((entity.PointId, direction))).ToArray();
            context.LatestValues.RemoveRange(removed);
            await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _persistenceGate.Release();
        }
    }

    public IReadOnlyList<PointSample> GetAll() => _values.Values.ToArray();

    public IReadOnlyList<PointSample> GetClient(string clientCode) =>
        _values.Values.Where(value => value.ClientCode == clientCode).ToArray();

    private static PointDirection ResolveDirection(PointSample sample) =>
        string.IsNullOrWhiteSpace(sample.SourcePointId)
            ? PointDirection.Input
            : PointDirection.Output;

    private static object? DeserializeValue(string? json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return null;
        }

        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            return root.ValueKind switch
            {
                JsonValueKind.Null or JsonValueKind.Undefined => null,
                JsonValueKind.True => true,
                JsonValueKind.False => false,
                JsonValueKind.String => root.GetString(),
                JsonValueKind.Number when root.TryGetInt64(out var integer) => integer,
                JsonValueKind.Number when root.TryGetDouble(out var number) => number,
                _ => root.GetRawText()
            };
        }
        catch (JsonException)
        {
            return json;
        }
    }
}
