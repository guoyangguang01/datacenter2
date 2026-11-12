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
    Task MarkOutputsCommLostAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default);
    Task RemovePointsAsync(
        string clientCode,
        IReadOnlyList<string> pointIds,
        CancellationToken cancellationToken = default);
    IReadOnlyList<PointSample> GetAll();
    IReadOnlyList<PointSample> GetClient(string clientCode);
    event EventHandler<IReadOnlyList<PointSample>>? ValuesChanged;
}

public sealed class LatestValueStore(IDbContextFactory<ServerDbContext> contextFactory) : ILatestValueStore
{
    private readonly ConcurrentDictionary<PointKey, PointSample> _values = new();

    public event EventHandler<IReadOnlyList<PointSample>>? ValuesChanged;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entities = await context.LatestValues.AsNoTracking().ToListAsync(cancellationToken).ConfigureAwait(false);
        foreach (var entity in entities)
        {
            var value = string.IsNullOrWhiteSpace(entity.ValueJson)
                ? null
                : JsonSerializer.Deserialize<object>(entity.ValueJson);
            _values[PointKey.Create(entity.ClientCode, entity.PointId)] = new PointSample(
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

        foreach (var sample in samples)
        {
            _values[PointKey.Create(sample.ClientCode, sample.PointId)] = sample;
        }

        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        foreach (var sample in samples)
        {
            var entity = await context.LatestValues.FindAsync(
                [sample.ClientCode, sample.PointId],
                cancellationToken).ConfigureAwait(false);
            if (entity is null)
            {
                entity = new LatestValueEntity
                {
                    ClientCode = sample.ClientCode,
                    PointId = sample.PointId
                };
                context.LatestValues.Add(entity);
            }

            entity.ValueJson = sample.Value.Value is null
                ? null
                : JsonSerializer.Serialize(sample.Value.Value);
            entity.Quality = sample.Value.Quality.ToString();
            entity.TimestampUnixNanoseconds = sample.Value.TimestampUnixNanoseconds;
            entity.SourceClientCode = sample.SourceClientCode ?? string.Empty;
            entity.SourcePointId = sample.SourcePointId ?? string.Empty;
        }

        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        ValuesChanged?.Invoke(this, samples);
    }

    public async Task MarkOutputsCommLostAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        var now = UnixTime.NowNanoseconds();
        var values = points
            .Where(point => point.Direction == PointDirection.Output)
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

        foreach (var pointId in pointIds)
        {
            _values.TryRemove(PointKey.Create(clientCode, pointId), out _);
        }

        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entities = await context.LatestValues
            .Where(entity => entity.ClientCode == clientCode && pointIds.Contains(entity.PointId))
            .ToListAsync(cancellationToken)
            .ConfigureAwait(false);
        context.LatestValues.RemoveRange(entities);
        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
    }

    public IReadOnlyList<PointSample> GetAll() => _values.Values.ToArray();

    public IReadOnlyList<PointSample> GetClient(string clientCode) =>
        _values.Values.Where(value => value.ClientCode == clientCode).ToArray();
}
