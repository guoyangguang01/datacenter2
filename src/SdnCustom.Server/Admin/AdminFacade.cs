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
    ClientProtocol Protocol,
    string Endpoint,
    string AppVersion,
    DateTimeOffset LastSeen,
    IReadOnlyList<PointDefinition> Points,
    IReadOnlyList<AdminPointValue> LatestValues);

public sealed record AdminSnapshot(IReadOnlyList<AdminClientView> Clients, int OnlineClients, int TotalPoints);

public interface IAdminFacade
{
    Task<AdminSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default);
    Task<IReadOnlyList<ServerLogEntry>> GetLogsAsync(int count = 500, CancellationToken cancellationToken = default);
    Task CreateClientAsync(string clientCode, string clientName, CancellationToken cancellationToken = default);
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
    private readonly ILatestValueStore _latestValues;
    private readonly IServerControlService _control;
    private readonly IServerLogStore _logs;

    public AdminFacade(
        IClientSystemStore clients,
        IAgentRegistry agents,
        ILatestValueStore latestValues,
        IServerControlService control,
        IServerLogStore logs)
    {
        _clients = clients;
        _agents = agents;
        _latestValues = latestValues;
        _control = control;
        _logs = logs;
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
                client.Protocol,
                client.EndpointDisplay,
                client.AppVersion,
                client.LastSeen,
                client.Points,
                latest);
        }).ToArray();

        return new AdminSnapshot(result, result.Count(client => client.Online), result.Sum(client => client.Points.Count));
    }

    public Task<IReadOnlyList<ServerLogEntry>> GetLogsAsync(
        int count = 500,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(_logs.GetRecent(count));

    public Task CreateClientAsync(string clientCode, string clientName, CancellationToken cancellationToken = default) =>
        _control.CreateClientAsync(clientCode, clientName, cancellationToken);

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
