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
    IReadOnlyList<AdminPointValue> LatestValues);

public sealed record AdminSnapshot(IReadOnlyList<AdminClientView> Clients, int OnlineClients, int TotalPoints);

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
    Task CreateClientAsync(string clientCode, string clientName, CancellationToken cancellationToken = default);
    Task RenameClientAsync(string clientCode, string clientName, CancellationToken cancellationToken = default);
    Task DeleteClientAsync(string clientCode, CancellationToken cancellationToken = default);
    Task StartClientAsync(string clientCode, CancellationToken cancellationToken = default);
    Task StopClientAsync(string clientCode, CancellationToken cancellationToken = default);
    Task SaveClientPointsAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default);
    Task WritePointAsync(string clientCode, string pointId, object? value, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
        string clientCode,
        string pointId,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default);
}

public sealed class AdminFacade : IAdminFacade
{
    private readonly IClientSystemStore _clients;
    private readonly IAgentRegistry _agents;
    private readonly IClientCommunicationStateStore _communicationStates;
    private readonly ILatestValueStore _latestValues;
    private readonly IServerControlService _control;
    private readonly IServerLogStore _logs;
    private readonly IOptions<ServerSettings> _settings;

    public AdminFacade(
        IClientSystemStore clients,
        IAgentRegistry agents,
        IClientCommunicationStateStore communicationStates,
        ILatestValueStore latestValues,
        IServerControlService control,
        IServerLogStore logs,
        IOptions<ServerSettings> settings)
    {
        _clients = clients;
        _agents = agents;
        _communicationStates = communicationStates;
        _latestValues = latestValues;
        _control = control;
        _logs = logs;
        _settings = settings;
    }

    public async Task<AdminSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default)
    {
        var clients = await _clients.ListAsync(cancellationToken).ConfigureAwait(false);
        var onlineCodes = _agents.List().Select(session => session.ClientCode).ToHashSet(StringComparer.Ordinal);
        var allValues = _latestValues.GetAll()
            .ToDictionary(value => PointKey.Create(value.ClientCode, value.PointId));

        var result = clients.Select(client =>
        {
            var latest = client.Points
                .Select(point => allValues.GetValueOrDefault(PointKey.Create(client.ClientCode, point.PointId)))
                .Where(value => value is not null)
                .Select(value => new AdminPointValue(
                    value!.PointId,
                    client.Points.First(point => point.PointId == value.PointId).PointName,
                    client.Points.First(point => point.PointId == value.PointId).Direction,
                    value.Value.Value,
                    value.Value.Quality,
                    value.Value.TimestampUnixNanoseconds))
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
                latest);
        }).ToArray();

        return new AdminSnapshot(result, result.Count(client => client.Online), result.Sum(client => client.Points.Count));
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

    public Task CreateClientAsync(string clientCode, string clientName, CancellationToken cancellationToken = default) =>
        _control.CreateClientAsync(clientCode, clientName, cancellationToken);

    public Task RenameClientAsync(string clientCode, string clientName, CancellationToken cancellationToken = default) =>
        _control.RenameClientAsync(clientCode, clientName, cancellationToken);

    public Task DeleteClientAsync(string clientCode, CancellationToken cancellationToken = default) =>
        _control.DeleteClientAsync(clientCode, cancellationToken);

    public Task StartClientAsync(string clientCode, CancellationToken cancellationToken = default) =>
        _control.StartClientAsync(clientCode, cancellationToken);

    public Task StopClientAsync(string clientCode, CancellationToken cancellationToken = default) =>
        _control.StopClientAsync(clientCode, cancellationToken);

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

    public Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
        string clientCode,
        string pointId,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default) =>
        _control.QueryHistoryAsync(clientCode, pointId, from, to, limit, cancellationToken);
}
