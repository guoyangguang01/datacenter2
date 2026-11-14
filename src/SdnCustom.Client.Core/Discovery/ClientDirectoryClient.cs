using System.Net.Http.Json;
using SdnCustom.Client.Core.Configuration;

namespace SdnCustom.Client.Core.Discovery;

public sealed record RegisteredClient(
    string ClientCode,
    string ClientName,
    string SystemCode,
    string SystemName,
    bool Online)
{
    public string DisplayName => $"{ClientCode} · {ClientName}";
    public string SystemLabel => string.IsNullOrWhiteSpace(SystemName) ? SystemCode : SystemName;
}

public interface IClientDirectoryClient
{
    Task<IReadOnlyList<RegisteredClient>> ListAsync(
        ServerConnectionOptions server,
        CancellationToken cancellationToken = default);
}

public sealed class AdminClientDirectoryClient : IClientDirectoryClient, IDisposable
{
    private readonly HttpClient _httpClient;
    private readonly bool _ownsHttpClient;

    public AdminClientDirectoryClient(HttpClient? httpClient = null)
    {
        _ownsHttpClient = httpClient is null;
        _httpClient = httpClient ?? new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(5)
        };
    }

    public async Task<IReadOnlyList<RegisteredClient>> ListAsync(
        ServerConnectionOptions server,
        CancellationToken cancellationToken = default)
    {
        var uri = new UriBuilder(
            Uri.UriSchemeHttp,
            server.Host,
            server.ManagementPort,
            "/api/clients").Uri;
        var clients = await _httpClient.GetFromJsonAsync<List<RegisteredClient>>(
            uri,
            cancellationToken).ConfigureAwait(false);
        return clients ?? [];
    }

    public void Dispose()
    {
        if (_ownsHttpClient)
        {
            _httpClient.Dispose();
        }
    }
}
