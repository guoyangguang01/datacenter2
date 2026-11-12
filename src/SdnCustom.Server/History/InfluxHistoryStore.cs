using System.Globalization;
using System.Net.Http.Headers;
using System.Text;
using System.Threading.Channels;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;
using SdnCustom.Server.Configuration;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.History;

public sealed class InfluxHistoryStore : BackgroundService, IHistoryStore
{
    private readonly HttpClient _httpClient = new();
    private readonly InfluxSettings _settings;
    private readonly IServerLogStore _logs;
    private readonly Channel<IReadOnlyList<PointSample>> _queue;

    public InfluxHistoryStore(IOptions<ServerSettings> settings, IServerLogStore logs)
    {
        _settings = settings.Value.Influx;
        _logs = logs;
        _queue = Channel.CreateBounded<IReadOnlyList<PointSample>>(new BoundedChannelOptions(_settings.QueueCapacity)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false
        });
        _httpClient.BaseAddress = new Uri(_settings.Url.TrimEnd('/') + "/");
        if (!string.IsNullOrWhiteSpace(_settings.Token))
        {
            _httpClient.DefaultRequestHeaders.Authorization =
                new AuthenticationHeaderValue("Token", _settings.Token);
        }
    }

    public ValueTask EnqueueAsync(
        IReadOnlyList<PointSample> samples,
        CancellationToken cancellationToken = default) =>
        _queue.Writer.WriteAsync(samples, cancellationToken);

    public async Task<IReadOnlyList<HistoryPoint>> QueryAsync(
        string clientCode,
        string pointId,
        DateTimeOffset from,
        DateTimeOffset to,
        int limit,
        CancellationToken cancellationToken = default)
    {
        var flux = $"""
            from(bucket: "{EscapeFlux(_settings.Bucket)}")
              |> range(start: {from.UtcDateTime:O}, stop: {to.UtcDateTime:O})
              |> filter(fn: (r) => r._measurement == "point_value" and r.client_code == "{EscapeFlux(clientCode)}" and r.point_id == "{EscapeFlux(pointId)}")
              |> sort(columns: ["_time"])
              |> limit(n: {Math.Clamp(limit, 1, 10000)})
            """;
        var uri = $"api/v2/query?org={Uri.EscapeDataString(_settings.Org)}";
        using var request = new HttpRequestMessage(HttpMethod.Post, uri)
        {
            Content = new StringContent(flux, Encoding.UTF8, "application/vnd.flux")
        };
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/csv"));
        using var response = await _httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException(
                $"InfluxDB query failed: {(int)response.StatusCode} {response.ReasonPhrase}");
        }

        var csv = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        return ParseCsv(csv);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await foreach (var batch in _queue.Reader.ReadAllAsync(stoppingToken).ConfigureAwait(false))
        {
            try
            {
                if (string.IsNullOrWhiteSpace(_settings.Token))
                {
                    continue;
                }

                var lineProtocol = string.Join('\n', batch.Select(InfluxLineProtocol.From));
                var uri = $"api/v2/write?org={Uri.EscapeDataString(_settings.Org)}&bucket={Uri.EscapeDataString(_settings.Bucket)}&precision=ns";
                using var content = new StringContent(lineProtocol, Encoding.UTF8, "text/plain");
                using var response = await _httpClient.PostAsync(uri, content, stoppingToken).ConfigureAwait(false);
                if (!response.IsSuccessStatusCode)
                {
                    _logs.Add("WARN", "InfluxDB", $"历史写入失败：{(int)response.StatusCode} {response.ReasonPhrase}");
                }
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                _logs.Add("ERROR", "InfluxDB", $"历史写入异常：{exception.Message}");
            }
        }
    }

    public override void Dispose()
    {
        _queue.Writer.TryComplete();
        _httpClient.Dispose();
        base.Dispose();
    }

    public async ValueTask DisposeAsync()
    {
        _queue.Writer.TryComplete();
        await StopAsync(CancellationToken.None).ConfigureAwait(false);
        Dispose();
    }

    private static List<HistoryPoint> ParseCsv(string csv)
    {
        var result = new List<HistoryPoint>();
        string[]? headers = null;
        foreach (var line in csv.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            if (line.StartsWith('#') || string.IsNullOrWhiteSpace(line))
            {
                continue;
            }

            var columns = SplitCsv(line);
            if (headers is null)
            {
                headers = columns;
                continue;
            }

            var row = headers.Zip(columns).ToDictionary(pair => pair.First, pair => pair.Second);
            if (!row.TryGetValue("_time", out var timeText) ||
                !DateTimeOffset.TryParse(timeText, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var timestamp))
            {
                continue;
            }

            result.Add(new HistoryPoint(
                timestamp,
                ParseValue(row.GetValueOrDefault("_value")),
                Enum.TryParse<PointQuality>(row.GetValueOrDefault("quality"), true, out var quality)
                    ? quality
                    : PointQuality.Good));
        }

        return result;
    }

    private static object? ParseValue(string? text)
    {
        if (string.IsNullOrEmpty(text)) return null;
        if (bool.TryParse(text, out var boolean)) return boolean;
        if (long.TryParse(text, CultureInfo.InvariantCulture, out var integer)) return integer;
        if (double.TryParse(text, CultureInfo.InvariantCulture, out var number)) return number;
        return text;
    }

    private static string[] SplitCsv(string line) => line.TrimEnd('\r').Split(',');
    private static string EscapeFlux(string value) => value.Replace("\\", "\\\\").Replace("\"", "\\\"");
}
