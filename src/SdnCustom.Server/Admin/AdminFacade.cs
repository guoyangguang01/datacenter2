using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using SdnCustom.Server.Configuration;
using SdnCustom.Server.History;
using SdnCustom.Server.Persistence;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Admin;

public sealed record AdminPointValue(
    string PointId,
    string PointName,
    PointDirection Direction,
    object? Value,
    PointQuality Quality,
    long TimestampUnixNanoseconds);

public sealed record AdminClientView(
    string ClientCode,
    string ClientName,
    bool Online,
    bool DesiredRunning,
    ClientConnectionState CommunicationState,
    ClientProtocol Protocol,
    string Endpoint,
    string AppVersion,
    DateTimeOffset LastSeen,
    IReadOnlyList<PointDefinition> Points,
    IReadOnlyList<AdminPointValue> LatestValues,
    string SystemCode = "DEFAULT",
    string SystemName = "默认系统");

public sealed record AdminSystemView(
    string SystemCode,
    string SystemName,
    string Description,
    int ClientCount,
    int PointCount,
    int RouteCount);

public sealed record AdminRouteView(
    string SourceClientCode,
    string SourcePointId,
    string SourcePointName,
    string TargetClientCode,
    string TargetPointId,
    string TargetPointName,
    PointDataType DataType,
    string Unit,
    string Address,
    bool Enabled);

public sealed record AdminSnapshot(
    IReadOnlyList<AdminClientView> Clients,
    int OnlineClients,
    int TotalPoints,
    IReadOnlyList<AdminSystemView>? Systems = null,
    bool CommunicationRunning = false,
    IReadOnlyList<AdminRouteView>? Routes = null);

public sealed record AdminSystemSettingsView(
    string DataDirectory,
    int GrpcPort,
    int HttpPort,
    bool UseTls,
    string InfluxUrl,
    string InfluxOrg,
    string InfluxBucket);

public interface IAdminFacade
{
    Task<AdminSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default);
    Task<AdminSystemSettingsView> GetSystemSettingsAsync(CancellationToken cancellationToken = default);
    Task<IReadOnlyList<ServerLogEntry>> GetLogsAsync(int count = 500, CancellationToken cancellationToken = default);
    Task CreateClientAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default,
        string? systemCode = null,
        ClientProtocol protocol = ClientProtocol.Tcp);
    Task UpdateClientAsync(
        string clientCode,
        string clientName,
        ClientProtocol protocol,
        CancellationToken cancellationToken = default);
    Task DeleteClientAsync(string clientCode, CancellationToken cancellationToken = default);
    Task StartCommunicationAsync(CancellationToken cancellationToken = default);
    Task StopCommunicationAsync(CancellationToken cancellationToken = default);
    Task SaveClientPointsAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default);
    Task WritePointAsync(string clientCode, string pointId, object? value, CancellationToken cancellationToken = default);
    Task<GasMasterImportResult> ImportGasMasterAsync(
        string systemCode,
        string systemName,
        string csv,
        CancellationToken cancellationToken = default);
    Task<string> ExportGasMasterAsync(
        string systemCode,
        CancellationToken cancellationToken = default);
    Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
        string clientCode,
        string pointId,
        PointDirection direction,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default);
}

public sealed class AdminFacade : IAdminFacade
{
    private readonly IClientSystemStore _clients;
    private readonly IBusinessSystemStore _systems;
    private readonly IAgentRegistry _agents;
    private readonly IServerCommunicationGate _communicationGate;
    private readonly IClientCommunicationStateStore _communicationStates;
    private readonly ILatestValueStore _latestValues;
    private readonly IServerControlService _control;
    private readonly IServerLogStore _logs;
    private readonly IOptions<ServerSettings> _settings;
    private readonly IGasMasterTableService _gasMasterTable;
    private readonly IDbContextFactory<ServerDbContext> _contextFactory;

    public AdminFacade(
        IClientSystemStore clients,
        IBusinessSystemStore systems,
        IAgentRegistry agents,
        IServerCommunicationGate communicationGate,
        IClientCommunicationStateStore communicationStates,
        ILatestValueStore latestValues,
        IServerControlService control,
        IServerLogStore logs,
        IOptions<ServerSettings> settings,
        IGasMasterTableService gasMasterTable,
        IDbContextFactory<ServerDbContext> contextFactory)
    {
        _clients = clients;
        _systems = systems;
        _agents = agents;
        _communicationGate = communicationGate;
        _communicationStates = communicationStates;
        _latestValues = latestValues;
        _control = control;
        _logs = logs;
        _settings = settings;
        _gasMasterTable = gasMasterTable;
        _contextFactory = contextFactory;
    }

    public async Task<AdminSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default)
    {
        var clients = await _clients.ListAsync(cancellationToken).ConfigureAwait(false);
        var systems = await _systems.ListAsync(cancellationToken).ConfigureAwait(false);
        var systemNames = systems.ToDictionary(
            system => system.SystemCode,
            system => system.SystemName,
            StringComparer.Ordinal);
        var onlineCodes = _agents.List().Select(session => session.ClientCode).ToHashSet(StringComparer.Ordinal);
        var allValues = _latestValues.GetAll()
            .ToDictionary(value => (
                value.ClientCode,
                value.PointId,
                string.IsNullOrWhiteSpace(value.SourcePointId)
                    ? PointDirection.Input
                    : PointDirection.Output));
        var pointsByKey = clients
            .SelectMany(client => client.Points.Select(point => (
                Key: $"{client.ClientCode}:{point.PointId}:{point.Direction}",
                Point: point)))
            .ToDictionary(item => item.Key, item => item.Point, StringComparer.Ordinal);
        await using var context = await _contextFactory
            .CreateDbContextAsync(cancellationToken)
            .ConfigureAwait(false);
        var routeEntities = await context.PointLinks
            .AsNoTracking()
            .Where(route => route.Declaration == "Output")
            .OrderBy(route => route.SourceClientCode)
            .ThenBy(route => route.SourcePointId)
            .ThenBy(route => route.TargetClientCode)
            .ThenBy(route => route.TargetPointId)
            .ToArrayAsync(cancellationToken)
            .ConfigureAwait(false);
        var routes = routeEntities
            .Select(route => (
                Route: route,
                Target: pointsByKey.GetValueOrDefault(
                    $"{route.TargetClientCode}:{route.TargetPointId}:{PointDirection.Output}")))
            .Where(item => item.Target is not null)
            .Select(item => new AdminRouteView(
                item.Route.SourceClientCode,
                item.Route.SourcePointId,
                item.Route.SourcePointName,
                item.Route.TargetClientCode,
                item.Route.TargetPointId,
                item.Target!.PointName,
                item.Target.DataType,
                item.Target.Unit,
                item.Target.Address,
                item.Route.Enabled))
            .ToArray();

        var result = clients.Select(client =>
        {
            var latest = client.Points
                .Select(point => (
                    Point: point,
                    Value: allValues.GetValueOrDefault((
                        client.ClientCode,
                        point.PointId,
                        point.Direction))))
                .Where(item => item.Value is not null)
                .Select(item => new AdminPointValue(
                    item.Point.PointId,
                    item.Point.PointName,
                    item.Point.Direction,
                    item.Value!.Value.Value,
                    item.Value.Value.Quality,
                    item.Value.Value.TimestampUnixNanoseconds))
                .ToArray();

            return new AdminClientView(
                client.ClientCode,
                client.ClientName,
                onlineCodes.Contains(client.ClientCode),
                client.DesiredRunning,
                _communicationStates.Get(client.ClientCode)?.State ?? ClientConnectionState.Disconnected,
                client.Protocol,
                client.EndpointDisplay,
                client.AppVersion,
                client.LastSeen,
                client.Points,
                latest,
                client.SystemCode,
                systemNames.GetValueOrDefault(client.SystemCode, "未分组"));
        }).ToArray();

        var systemViews = systems.Select(system => new AdminSystemView(
            system.SystemCode,
            system.SystemName,
            system.Description,
            system.ClientCount,
            system.PointCount,
            system.RouteCount)).ToArray();
        return new AdminSnapshot(
            result,
            result.Count(client => client.Online),
            result.Sum(client => client.Points.Count),
            systemViews,
            _communicationGate.IsRunning,
            routes);
    }

    public Task<AdminSystemSettingsView> GetSystemSettingsAsync(
        CancellationToken cancellationToken = default)
    {
        var settings = _settings.Value;
        return Task.FromResult(new AdminSystemSettingsView(
            Path.GetFullPath(settings.DataDirectory, AppContext.BaseDirectory),
            settings.GrpcPort,
            settings.HttpPort,
            settings.UseTls,
            settings.Influx.Url,
            settings.Influx.Org,
            settings.Influx.Bucket));
    }

    public Task<IReadOnlyList<ServerLogEntry>> GetLogsAsync(
        int count = 500,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(_logs.GetRecent(count));

    public Task CreateClientAsync(
        string clientCode,
        string clientName,
        CancellationToken cancellationToken = default,
        string? systemCode = null,
        ClientProtocol protocol = ClientProtocol.Tcp) =>
        _control.CreateClientAsync(clientCode, clientName, cancellationToken, systemCode, protocol);

    public Task UpdateClientAsync(
        string clientCode,
        string clientName,
        ClientProtocol protocol,
        CancellationToken cancellationToken = default) =>
        _control.UpdateClientAsync(clientCode, clientName, protocol, cancellationToken);

    public Task DeleteClientAsync(string clientCode, CancellationToken cancellationToken = default) =>
        _control.DeleteClientAsync(clientCode, cancellationToken);

    public Task StartCommunicationAsync(CancellationToken cancellationToken = default) =>
        _control.StartCommunicationAsync(cancellationToken);

    public Task StopCommunicationAsync(CancellationToken cancellationToken = default) =>
        _control.StopCommunicationAsync(cancellationToken);

    public Task SaveClientPointsAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default) =>
        _control.SaveClientPointsAsync(clientCode, points, cancellationToken);

    public Task WritePointAsync(
        string clientCode,
        string pointId,
        object? value,
        CancellationToken cancellationToken = default) =>
        _control.WritePointAsync(clientCode, pointId, value, cancellationToken);

    public Task<GasMasterImportResult> ImportGasMasterAsync(
        string systemCode,
        string systemName,
        string csv,
        CancellationToken cancellationToken = default) =>
        _gasMasterTable.ImportAsync(systemCode, systemName, csv, cancellationToken);

    public Task<string> ExportGasMasterAsync(
        string systemCode,
        CancellationToken cancellationToken = default) =>
        _gasMasterTable.ExportAsync(systemCode, cancellationToken);

    public Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
        string clientCode,
        string pointId,
        PointDirection direction,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default) =>
        _control.QueryHistoryAsync(clientCode, pointId, direction, from, to, limit, cancellationToken);
}
