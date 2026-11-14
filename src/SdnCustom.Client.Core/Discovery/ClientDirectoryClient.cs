using System.Net.Http.Json;
using System.Text.Json;
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
        using var response = await _httpClient.GetAsync(uri, cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            var message = string.Empty;
            try
            {
                using var document = JsonDocument.Parse(body);
                message = document.RootElement.TryGetProperty("message", out var messageElement)
                    ? messageElement.GetString() ?? string.Empty
                    : string.Empty;
            }
            catch (JsonException)
            {
            }

            throw new InvalidOperationException(
                string.IsNullOrWhiteSpace(message)
                    ? $"服务器拒绝客户端列表请求：HTTP {(int)response.StatusCode}"
                    : message);
        }

        var clients = await response.Content.ReadFromJsonAsync<List<RegisteredClient>>(
            cancellationToken: cancellationToken).ConfigureAwait(false);
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
