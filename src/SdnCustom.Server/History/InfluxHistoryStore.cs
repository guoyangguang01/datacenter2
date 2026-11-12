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
    private int _missingTokenReported;
    private int _droppedBatches;

    public InfluxHistoryStore(IOptions<ServerSettings> settings, IServerLogStore logs)
    {
        _settings = settings.Value.Influx;
        _logs = logs;
        _queue = Channel.CreateBounded<IReadOnlyList<PointSample>>(new BoundedChannelOptions(
            Math.Max(1, _settings.QueueCapacity))
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false
        }, _ =>
        {
            var dropped = Interlocked.Increment(ref _droppedBatches);
            if (dropped == 1 || dropped % 100 == 0)
            {
                _logs.Add(
                    "WARN",
                    "InfluxDB",
                    $"历史写入队列已满，累计丢弃 {dropped} 个批次。");
            }
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
                    if (Interlocked.Exchange(ref _missingTokenReported, 1) == 0)
                    {
                        _logs.Add("WARN", "InfluxDB", "未配置 InfluxDB Token，历史数据将被丢弃。");
                    }
                    continue;
                }

                var samples = batch.ToList();
                var batchSize = Math.Max(1, _settings.BatchSize);
                while (samples.Count < batchSize && _queue.Reader.TryRead(out var next))
                {
                    samples.AddRange(next);
                }

                var lineProtocol = string.Join('\n', samples.Select(InfluxLineProtocol.From));
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
        foreach (var columns in ParseCsvRecords(csv))
        {
            if (columns.Length == 0 ||
                columns.All(string.IsNullOrWhiteSpace) ||
                columns[0].StartsWith('#'))
            {
                continue;
            }

            if (columns.Contains("_time", StringComparer.Ordinal))
            {
                headers = columns;
                continue;
            }

            if (headers is null)
            {
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
        if (text is null) return null;
        if (text.Length == 0) return string.Empty;
        if (bool.TryParse(text, out var boolean)) return boolean;
        if (long.TryParse(text, CultureInfo.InvariantCulture, out var integer)) return integer;
        if (double.TryParse(text, CultureInfo.InvariantCulture, out var number)) return number;
        return text;
    }

    private static IReadOnlyList<string[]> ParseCsvRecords(string csv)
    {
        var records = new List<string[]>();
        var fields = new List<string>();
        var field = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < csv.Length; index++)
        {
            var character = csv[index];
            if (character == '"')
            {
                if (quoted && index + 1 < csv.Length && csv[index + 1] == '"')
                {
                    field.Append('"');
                    index++;
                }
                else
                {
                    quoted = !quoted;
                }
                continue;
            }

            if (!quoted && character == ',')
            {
                fields.Add(field.ToString());
                field.Clear();
                continue;
            }

            if (!quoted && character is '\r' or '\n')
            {
                if (character == '\r' && index + 1 < csv.Length && csv[index + 1] == '\n')
                {
                    index++;
                }

                fields.Add(field.ToString());
                field.Clear();
                if (fields.Any(value => value.Length > 0))
                {
                    records.Add(fields.ToArray());
                }
                fields.Clear();
                continue;
            }

            field.Append(character);
        }

        if (field.Length > 0 || fields.Count > 0)
        {
            fields.Add(field.ToString());
            if (fields.Any(value => value.Length > 0))
            {
                records.Add(fields.ToArray());
            }
        }

        return records;
    }

    private static string EscapeFlux(string value) => value.Replace("\\", "\\\\").Replace("\"", "\\\"");
}
