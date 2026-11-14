using System.Collections.Concurrent;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Runtime;

public sealed record RouteTarget(string ClientCode, PointDefinition Point);

public interface IPointRouter
{
    void RegisterClient(string clientCode, IReadOnlyList<PointDefinition> points);
    void RegisterRoutes(IReadOnlyList<RouteTarget> routes);
    void RemoveClient(string clientCode);
    IReadOnlyList<PointDefinition> GetClientPoints(string clientCode);
    IReadOnlyList<RouteTarget> ResolveOutputTargets(PointSample input);
}

public sealed class PointRouter : IPointRouter
{
    private readonly ConcurrentDictionary<string, IReadOnlyList<PointDefinition>> _catalogs =
        new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<PointKey, IReadOnlyList<RouteTarget>> _routes = new();

    public void RegisterClient(string clientCode, IReadOnlyList<PointDefinition> points) =>
        _catalogs[clientCode] = points.ToArray();

    public void RegisterRoutes(IReadOnlyList<RouteTarget> routes)
    {
        _routes.Clear();
        foreach (var group in routes.GroupBy(
                     route => PointKey.Create(
                         route.Point.SourceInputPointId?.Split(':', 2)[0] ?? string.Empty,
                         route.Point.SourceInputPointId?.Split(':', 2).ElementAtOrDefault(1) ?? string.Empty)))
        {
            if (string.IsNullOrWhiteSpace(group.Key.ClientCode) ||
                string.IsNullOrWhiteSpace(group.Key.PointId))
            {
                continue;
            }

            _routes[group.Key] = group.ToArray();
        }
    }

    public void RemoveClient(string clientCode) => _catalogs.TryRemove(clientCode, out _);

    public IReadOnlyList<PointDefinition> GetClientPoints(string clientCode) =>
        _catalogs.TryGetValue(clientCode, out var points) ? points : [];

    public IReadOnlyList<RouteTarget> ResolveOutputTargets(PointSample input)
    {
        var key = new PointKey(input.ClientCode, input.PointId);
        return _routes.TryGetValue(key, out var registeredRoutes)
            ? registeredRoutes.ToList()
            : [];
    }
}
