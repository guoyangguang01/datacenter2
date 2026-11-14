using Microsoft.EntityFrameworkCore;
using SdnCustom.Contracts;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Persistence;

public sealed record ClientRegistration(
    string ClientCode,
    string ClientName,
    ClientProtocol Protocol,
    string EndpointDisplay,
    string AppVersion,
    string CatalogRevision,
    IReadOnlyList<PointDefinition> Points);

public sealed record ClientSystemRecord(
    string ClientCode,
    string ClientName,
    string SystemCode,
    bool Authorized,
    bool DesiredRunning,
    bool PointsConfigured,
    ClientProtocol Protocol,
    string EndpointDisplay,
    string AppVersion,
    string CatalogRevision,
    DateTimeOffset FirstSeen,
    DateTimeOffset LastSeen,
    IReadOnlyList<PointDefinition> Points);

public interface IClientSystemStore
{
    Task InitializeAsync(CancellationToken cancellationToken = default);
    Task<ClientSystemRecord?> GetAsync(string clientCode, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<ClientSystemRecord>> ListAsync(CancellationToken cancellationToken = default);
    Task<ClientSystemRecord> CreateAuthorizedAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default,
        string systemCode = "DEFAULT");
    Task<ClientSystemRecord> RenameAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default);
    Task DeleteAsync(string clientCode, CancellationToken cancellationToken = default);
    Task<ClientSystemRecord> RegisterAsync(
        ClientRegistration registration,
        CancellationToken cancellationToken = default);
    Task SetDesiredRunningAsync(string clientCode, bool desiredRunning, CancellationToken cancellationToken = default);
    Task<ClientSystemRecord> SavePointsAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default);
}

public sealed class ClientSystemStore(IDbContextFactory<ServerDbContext> contextFactory) : IClientSystemStore
{
    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        await context.Database.MigrateAsync(cancellationToken).ConfigureAwait(false);
    }

    public async Task<ClientSystemRecord?> GetAsync(string clientCode, CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entity = await context.ClientSystems
            .Include(item => item.Points)
            .AsNoTracking()
            .SingleOrDefaultAsync(item => item.ClientCode == clientCode, cancellationToken)
            .ConfigureAwait(false);
        return entity is null ? null : Map(entity);
    }

    public async Task<IReadOnlyList<ClientSystemRecord>> ListAsync(CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entities = await context.ClientSystems
            .Include(item => item.Points)
            .AsNoTracking()
            .OrderBy(item => item.ClientCode)
            .ToListAsync(cancellationToken)
            .ConfigureAwait(false);
        return entities.Select(Map).ToArray();
    }

    public async Task<ClientSystemRecord> CreateAuthorizedAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default,
        string systemCode = "DEFAULT")
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(clientCode);
        ArgumentException.ThrowIfNullOrWhiteSpace(clientName);
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var code = clientCode.Trim();
        if (await context.ClientSystems.AnyAsync(item => item.ClientCode == code, cancellationToken).ConfigureAwait(false))
        {
            throw new InvalidOperationException($"客户端代码已存在：{code}");
        }

        var now = DateTimeOffset.UtcNow;
        var normalizedSystemCode = string.IsNullOrWhiteSpace(systemCode)
            ? "DEFAULT"
            : systemCode.Trim();
        if (!await context.BusinessSystems.AnyAsync(
                item => item.SystemCode == normalizedSystemCode,
                cancellationToken).ConfigureAwait(false))
        {
            context.BusinessSystems.Add(new BusinessSystemEntity
            {
                SystemCode = normalizedSystemCode,
                SystemName = normalizedSystemCode == "DEFAULT" ? "默认系统" : normalizedSystemCode,
                Description = "自动创建的客户端分组。",
                CreatedAt = now
            });
        }

        var entity = new ClientSystemEntity
        {
            ClientCode = code,
            ClientName = clientName.Trim(),
            SystemCode = normalizedSystemCode,
            Authorized = true,
            PointsConfigured = true,
            FirstSeen = now,
            LastSeen = now
        };
        context.ClientSystems.Add(entity);
        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        return Map(entity);
    }

    public async Task<ClientSystemRecord> RenameAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(clientName);
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entity = await context.ClientSystems
            .Include(item => item.Points)
            .SingleOrDefaultAsync(item => item.ClientCode == clientCode, cancellationToken)
            .ConfigureAwait(false)
            ?? throw new KeyNotFoundException($"客户端未登记：{clientCode}");

        entity.ClientName = clientName.Trim();
        entity.LastSeen = DateTimeOffset.UtcNow;
        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        return Map(entity);
    }

    public async Task DeleteAsync(string clientCode, CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entity = await context.ClientSystems
            .SingleOrDefaultAsync(item => item.ClientCode == clientCode, cancellationToken)
            .ConfigureAwait(false)
            ?? throw new KeyNotFoundException($"客户端未登记：{clientCode}");

        context.ClientSystems.Remove(entity);
        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
    }

    public async Task<ClientSystemRecord> RegisterAsync(
        ClientRegistration registration,
        CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entity = await context.ClientSystems
            .Include(item => item.Points)
            .SingleOrDefaultAsync(item => item.ClientCode == registration.ClientCode, cancellationToken)
            .ConfigureAwait(false)
            ?? throw new UnauthorizedAccessException($"客户端代码未登记：{registration.ClientCode}");
        if (!entity.Authorized)
        {
            throw new UnauthorizedAccessException($"客户端代码未授权：{registration.ClientCode}");
        }

        if (string.IsNullOrWhiteSpace(entity.ClientName))
        {
            entity.ClientName = registration.ClientName;
        }
        entity.Protocol = registration.Protocol.ToString();
        entity.EndpointDisplay = registration.EndpointDisplay;
        entity.AppVersion = registration.AppVersion;
        entity.LastSeen = DateTimeOffset.UtcNow;

        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        return Map(entity);
    }

    public async Task SetDesiredRunningAsync(
        string clientCode,
        bool desiredRunning,
        CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entity = await context.ClientSystems
            .SingleOrDefaultAsync(item => item.ClientCode == clientCode, cancellationToken)
            .ConfigureAwait(false)
            ?? throw new KeyNotFoundException($"客户端代码未登记：{clientCode}");
        entity.DesiredRunning = desiredRunning;
        entity.LastSeen = DateTimeOffset.UtcNow;
        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
    }

    public async Task<ClientSystemRecord> SavePointsAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken).ConfigureAwait(false);
        var entity = await context.ClientSystems
            .Include(item => item.Points)
            .SingleOrDefaultAsync(item => item.ClientCode == clientCode, cancellationToken)
            .ConfigureAwait(false)
            ?? throw new KeyNotFoundException($"客户端代码未登记：{clientCode}");

        var duplicate = points
            .GroupBy(point => (point.PointId, point.Direction))
            .FirstOrDefault(group => group.Count() > 1);
        if (duplicate is not null)
        {
            throw new InvalidOperationException(
                $"测点 ID 与方向重复：{duplicate.Key.PointId}/{duplicate.Key.Direction}");
        }
        if (points.Any(point => string.IsNullOrWhiteSpace(point.PointId) ||
                                string.IsNullOrWhiteSpace(point.PointName) ||
                                string.IsNullOrWhiteSpace(point.Address)))
        {
            throw new InvalidOperationException("测点 ID、名称和地址不能为空。");
        }
        if (points.Any(point => !string.IsNullOrWhiteSpace(point.SourceInputPointId)))
        {
            throw new InvalidOperationException(
                "客户端端点不能保存来源引用；INPUT→OUTPUT 关系必须写入 PointLink。");
        }

        var incomingIds = points
            .Select(point => (point.PointId, point.Direction))
            .ToHashSet();
        var removed = entity.Points
            .Where(point => !incomingIds.Contains((
                point.PointId,
                Enum.TryParse<PointDirection>(point.Direction, true, out var direction)
                    ? direction
                    : PointDirection.Input)))
            .ToArray();
        context.PointCatalog.RemoveRange(removed);
        foreach (var point in removed)
        {
            entity.Points.Remove(point);
        }

        var existingById = entity.Points.ToDictionary(
            point => (
                point.PointId,
                Enum.TryParse<PointDirection>(point.Direction, true, out var direction)
                    ? direction
                    : PointDirection.Input));
        foreach (var point in points)
        {
            if (existingById.TryGetValue((point.PointId, point.Direction), out var entityPoint))
            {
                CopyToEntity(point, entityPoint);
            }
            else
            {
                entity.Points.Add(ToEntity(clientCode, point));
            }
        }
        entity.PointsConfigured = true;
        entity.CatalogRevision = ComputeCatalogRevision(points);
        entity.LastSeen = DateTimeOffset.UtcNow;
        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        return Map(entity);
    }

    private static ClientSystemRecord Map(ClientSystemEntity entity)
    {
        var protocol = Enum.TryParse<ClientProtocol>(entity.Protocol, true, out var parsed)
            ? parsed
            : ClientProtocol.CustomTcp;
        return new ClientSystemRecord(
            entity.ClientCode,
            entity.ClientName,
            entity.SystemCode,
            entity.Authorized,
            entity.DesiredRunning,
            entity.PointsConfigured,
            protocol,
            entity.EndpointDisplay,
            entity.AppVersion,
            entity.CatalogRevision,
            entity.FirstSeen,
            entity.LastSeen,
            entity.Points.Select(point => new PointDefinition
            {
                PointId = point.PointId,
                PointName = point.PointName,
                Address = point.Address,
                DataType = Enum.TryParse<PointDataType>(point.DataType, true, out var dataType)
                    ? dataType
                    : PointDataType.Float64,
                Unit = point.Unit,
                Direction = Enum.TryParse<PointDirection>(point.Direction, true, out var direction)
                    ? direction
                    : PointDirection.Input,
                SourceInputPointId = null,
                Deadband = point.Deadband,
                Group = point.Group
            }).ToArray());
    }

    private static List<PointCatalogEntity> ToEntities(
        string clientCode,
        IReadOnlyList<PointDefinition> points) =>
        points.Select(point => ToEntity(clientCode, point)).ToList();

    private static PointCatalogEntity ToEntity(string clientCode, PointDefinition point) => new()
    {
        ClientCode = clientCode,
        PointId = point.PointId,
        PointName = point.PointName,
        Address = point.Address,
        DataType = point.DataType.ToString(),
        Unit = point.Unit,
        Direction = point.Direction.ToString(),
        Deadband = point.Deadband,
        Group = point.Group
    };

    private static void CopyToEntity(PointDefinition point, PointCatalogEntity entity)
    {
        entity.PointName = point.PointName;
        entity.Address = point.Address;
        entity.DataType = point.DataType.ToString();
        entity.Unit = point.Unit;
        entity.Direction = point.Direction.ToString();
        entity.Deadband = point.Deadband;
        entity.Group = point.Group;
    }

    private static string ComputeCatalogRevision(IReadOnlyList<PointDefinition> points)
    {
        var value = string.Join(
            "|",
            points.OrderBy(point => point.PointId, StringComparer.Ordinal)
                .Select(point =>
                    $"{point.PointId}:{point.PointName}:{point.Address}:{point.DataType}:{point.Direction}:{point.Deadband}"));
        return Convert.ToHexString(
            System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(value)));
    }
}
