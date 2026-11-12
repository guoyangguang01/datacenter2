using System.Collections.Concurrent;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Runtime;

public sealed record RouteTarget(string ClientCode, PointDefinition Point);

public interface IPointRouter
{
    void RegisterClient(string clientCode, IReadOnlyList<PointDefinition> points);
    void RemoveClient(string clientCode);
    IReadOnlyList<PointDefinition> GetClientPoints(string clientCode);
    IReadOnlyList<RouteTarget> ResolveOutputTargets(PointSample input);
}

public sealed class PointRouter : IPointRouter
{
    private readonly ConcurrentDictionary<string, IReadOnlyList<PointDefinition>> _catalogs =
        new(StringComparer.Ordinal);

    public void RegisterClient(string clientCode, IReadOnlyList<PointDefinition> points) =>
        _catalogs[clientCode] = points.ToArray();

    public void RemoveClient(string clientCode) => _catalogs.TryRemove(clientCode, out _);

    public IReadOnlyList<PointDefinition> GetClientPoints(string clientCode) =>
        _catalogs.TryGetValue(clientCode, out var points) ? points : [];

    public IReadOnlyList<RouteTarget> ResolveOutputTargets(PointSample input)
    {
        var result = new List<RouteTarget>();
        foreach (var (targetClientCode, points) in _catalogs)
        {
            foreach (var point in points.Where(point =>
                         point.Direction == PointDirection.Output &&
                         !string.IsNullOrWhiteSpace(point.SourceInputPointId)))
            {
                var sourceKey = ResolveSourceKey(targetClientCode, point.SourceInputPointId!);
                if (sourceKey == PointKey.Create(input.ClientCode, input.PointId))
                {
                    result.Add(new RouteTarget(targetClientCode, point));
                }
            }
        }

        return result;
    }

    private static PointKey ResolveSourceKey(string targetClientCode, string source)
    {
        var separator = source.IndexOf(':');
        return separator > 0
            ? PointKey.Create(source[..separator], source[(separator + 1)..])
            : PointKey.Create(targetClientCode, source);
    }
}
