using Microsoft.EntityFrameworkCore;
using SdnCustom.Server.Persistence;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Runtime;

public sealed class PointLinkCatalog(
    IPointRouter router,
    IDbContextFactory<ServerDbContext> contextFactory)
{
    public async Task ReloadAsync(CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken)
            .ConfigureAwait(false);
        var routes = await context.PointLinks
            .AsNoTracking()
            .Where(route => route.Enabled && route.Declaration == "Output")
            .Select(route => new
            {
                route.SourceClientCode,
                route.SourcePointId,
                route.TargetClientCode,
                route.TargetPointId
            })
            .ToArrayAsync(cancellationToken)
            .ConfigureAwait(false);
        var endpoints = await context.PointCatalog
            .AsNoTracking()
            .Where(point => point.Direction == "Output")
            .ToDictionaryAsync(
                point => $"{point.ClientCode}:{point.PointId}",
                StringComparer.Ordinal,
                cancellationToken)
            .ConfigureAwait(false);

        var targets = new List<RouteTarget>(routes.Length);
        foreach (var route in routes)
        {
            if (!endpoints.TryGetValue(
                    $"{route.TargetClientCode}:{route.TargetPointId}",
                    out var endpoint))
            {
                continue;
            }

            targets.Add(new RouteTarget(
                route.TargetClientCode,
                new PointDefinition
                {
                    PointId = endpoint.PointId,
                    PointName = endpoint.PointName,
                    Address = endpoint.Address,
                    DataType = Enum.TryParse<PointDataType>(endpoint.DataType, true, out var dataType)
                        ? dataType
                        : PointDataType.Float64,
                    Direction = PointDirection.Output,
                    SourceInputPointId = $"{route.SourceClientCode}:{route.SourcePointId}",
                    Unit = endpoint.Unit,
                    Group = "系统路由"
                }));
        }

        router.RegisterRoutes(targets);
    }
}
