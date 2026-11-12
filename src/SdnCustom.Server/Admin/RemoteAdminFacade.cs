using System.Globalization;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using SdnCustom.Server.History;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Admin;

public sealed class RemoteAdminFacade : IAdminFacade
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        Converters = { new JsonStringEnumConverter() }
    };

    private readonly HttpClient _httpClient;

    public RemoteAdminFacade(Uri baseAddress)
    {
        _httpClient = new HttpClient { BaseAddress = baseAddress, Timeout = TimeSpan.FromSeconds(10) };
    }

    public static async Task<RemoteAdminFacade?> TryCreateAsync(
        int localHttpPort,
        CancellationToken cancellationToken = default)
    {
        var facade = new RemoteAdminFacade(new Uri($"http://127.0.0.1:{localHttpPort}/"));
        try
        {
            using var response = await facade._httpClient.GetAsync("health", cancellationToken).ConfigureAwait(false);
            return response.IsSuccessStatusCode ? facade : null;
        }
        catch (HttpRequestException)
        {
            return null;
        }
        catch (TaskCanceledException)
        {
            return null;
        }
    }

    public Task<AdminSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default) =>
        GetAsync<AdminSnapshot>("api/status", cancellationToken);

    public async Task<IReadOnlyList<ServerLogEntry>> GetLogsAsync(
        int count = 500,
        CancellationToken cancellationToken = default)
    {
        await using var stream = await _httpClient.GetStreamAsync($"api/logs?count={count}", cancellationToken)
            .ConfigureAwait(false);
        return await JsonSerializer.DeserializeAsync<List<ServerLogEntry>>(stream, JsonOptions, cancellationToken)
               .ConfigureAwait(false)
               ?? [];
    }

    public async Task CreateClientAsync(string clientCode, string clientName, CancellationToken cancellationToken = default)
    {
        using var response = await _httpClient.PostAsJsonAsync(
            "api/clients",
            new CreateClientRequest(clientCode, clientName),
            cancellationToken).ConfigureAwait(false);
        await EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);
    }

    public async Task StartClientAsync(string clientCode, CancellationToken cancellationToken = default)
    {
        using var response = await _httpClient.PostAsync(
            $"api/clients/{Uri.EscapeDataString(clientCode)}/start",
            null,
            cancellationToken).ConfigureAwait(false);
        await EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);
    }

    public async Task StopClientAsync(string clientCode, CancellationToken cancellationToken = default)
    {
        using var response = await _httpClient.PostAsync(
            $"api/clients/{Uri.EscapeDataString(clientCode)}/stop",
            null,
            cancellationToken).ConfigureAwait(false);
        await EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);
    }

    public async Task SaveClientPointsAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        using var response = await _httpClient.PutAsJsonAsync(
            $"api/clients/{Uri.EscapeDataString(clientCode)}/points",
            points,
            cancellationToken).ConfigureAwait(false);
        await EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);
    }

    public async Task WritePointAsync(
        string clientCode,
        string pointId,
        object? value,
        CancellationToken cancellationToken = default)
    {
        using var response = await _httpClient.PostAsJsonAsync(
            $"api/clients/{Uri.EscapeDataString(clientCode)}/write",
            new WritePointRequest(pointId, JsonSerializer.SerializeToElement(value)),
            cancellationToken).ConfigureAwait(false);
        await EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);
    }

    public async Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
        string clientCode,
        string pointId,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default)
    {
        var url =
            $"api/history?clientCode={Uri.EscapeDataString(clientCode)}" +
            $"&pointId={Uri.EscapeDataString(pointId)}" +
            $"&from={Uri.EscapeDataString(from.ToString("O", CultureInfo.InvariantCulture))}" +
            $"&to={Uri.EscapeDataString(to.ToString("O", CultureInfo.InvariantCulture))}" +
            $"&limit={limit}";
        return await GetAsync<List<HistoryPoint>>(url, cancellationToken).ConfigureAwait(false);
    }

    private async Task<T> GetAsync<T>(string url, CancellationToken cancellationToken)
    {
        using var response = await _httpClient.GetAsync(url, cancellationToken).ConfigureAwait(false);
        await EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);
        return await response.Content.ReadFromJsonAsync<T>(JsonOptions, cancellationToken).ConfigureAwait(false)
               ?? throw new InvalidOperationException($"服务端返回了空响应：{url}");
    }

    private static async Task EnsureSuccessAsync(
        HttpResponseMessage response,
        CancellationToken cancellationToken)
    {
        if (response.IsSuccessStatusCode)
        {
            return;
        }

        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        throw new HttpRequestException(
            $"服务端管理接口失败：{(int)response.StatusCode} {response.ReasonPhrase} {body}");
    }
}
