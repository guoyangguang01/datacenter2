using Microsoft.EntityFrameworkCore;

namespace SdnCustom.Server.Persistence;

public sealed record BusinessSystemRecord(
    string SystemCode,
    string SystemName,
    string Description,
    int ClientCount,
    int PointCount,
    int RouteCount);

public interface IBusinessSystemStore
{
    Task<IReadOnlyList<BusinessSystemRecord>> ListAsync(
        CancellationToken cancellationToken = default);
}

public sealed class BusinessSystemStore(
    IDbContextFactory<ServerDbContext> contextFactory) : IBusinessSystemStore
{
    public async Task<IReadOnlyList<BusinessSystemRecord>> ListAsync(
        CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken)
            .ConfigureAwait(false);
        return await context.BusinessSystems
            .AsNoTracking()
            .OrderBy(item => item.SystemName)
            .Select(item => new BusinessSystemRecord(
                item.SystemCode,
                item.SystemName,
                item.Description,
                item.Clients.Count,
                item.Clients.SelectMany(client => client.Points).Count(),
                item.Links.Count(link => link.Declaration == "Output")))
            .ToArrayAsync(cancellationToken)
            .ConfigureAwait(false);
    }
}
