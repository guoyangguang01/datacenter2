using System.Buffers.Binary;
using System.Net.Sockets;
using System.Text.Json;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;

namespace SdnCustom.Protocols.Tcp;

public sealed class CustomTcpDeviceConnection : IDeviceConnection
{
    private readonly SemaphoreSlim _ioGate = new(1, 1);
    private TcpClient? _client;
    private NetworkStream? _stream;
    private DeviceConnectionOptions _options = new();

    public ClientProtocol Protocol => ClientProtocol.CustomTcp;
    public bool IsConnected => _client?.Connected == true && _stream is not null;

    public async Task ConnectAsync(DeviceConnectionOptions options, CancellationToken cancellationToken = default)
    {
        await DisconnectAsync(cancellationToken).ConfigureAwait(false);
        _options = options;
        var client = new TcpClient();
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromMilliseconds(Math.Max(100, options.TimeoutMilliseconds)));
            await client.ConnectAsync(options.Host, options.Port, timeout.Token).ConfigureAwait(false);
            client.NoDelay = true;
            _client = client;
            _stream = client.GetStream();
        }
        catch
        {
            client.Dispose();
            throw;
        }
    }

    public async Task<IReadOnlyList<PointSample>> ReadAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        if (points.Count == 0)
        {
            return [];
        }

        var document = JsonSerializer.SerializeToUtf8Bytes(new
        {
            pointIds = points.Select(point => point.PointId).ToArray(),
            addresses = points.Select(point => point.Address).ToArray()
        });

        await _ioGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await SendAsync(new TcpFrame(TcpCommand.ReadRequest, System.Text.Encoding.UTF8.GetString(document)), cancellationToken)
                .ConfigureAwait(false);
            var response = await ReceiveExpectedAsync(TcpCommand.ReadResponse, cancellationToken).ConfigureAwait(false);
            return ParseValues(clientCode, points, response.Body);
        }
        finally
        {
            _ioGate.Release();
        }
    }

    public async Task WriteAsync(
        string clientCode,
        PointDefinition point,
        object? value,
        CancellationToken cancellationToken = default)
    {
        var body = JsonSerializer.Serialize(new
        {
            pointId = point.PointId,
            value
        });

        await _ioGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await SendAsync(new TcpFrame(TcpCommand.WriteRequest, body), cancellationToken).ConfigureAwait(false);
            var response = await ReceiveExpectedAsync(TcpCommand.WriteResponse, cancellationToken).ConfigureAwait(false);
            if (!string.IsNullOrWhiteSpace(response.Body))
            {
                using var document = JsonDocument.Parse(response.Body);
                if (document.RootElement.TryGetProperty("success", out var success) && !success.GetBoolean())
                {
                    var message = document.RootElement.TryGetProperty("message", out var error)
                        ? error.GetString()
                        : "Device rejected write.";
                    throw new IOException(message);
                }
            }
        }
        finally
        {
            _ioGate.Release();
        }
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken = default)
    {
        await _ioGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            try
            {
                if (_client?.Client.Connected == true)
                {
                    _client.Client.Shutdown(SocketShutdown.Both);
                }
            }
            catch (SocketException)
            {
            }
            finally
            {
                _stream?.Dispose();
                _client?.Dispose();
                _stream = null;
                _client = null;
            }
        }
        finally
        {
            _ioGate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        await DisconnectAsync().ConfigureAwait(false);
        _ioGate.Dispose();
    }

    private async Task SendAsync(TcpFrame frame, CancellationToken cancellationToken)
    {
        var stream = _stream ?? throw new IOException("TCP device is not connected.");
        using var timeout = CreateTimeout(cancellationToken);
        await stream.WriteAsync(frame.Encode(), timeout.Token).ConfigureAwait(false);
        await stream.FlushAsync(timeout.Token).ConfigureAwait(false);
    }

    private async Task<TcpFrame> ReceiveExpectedAsync(byte expectedCommand, CancellationToken cancellationToken)
    {
        for (var attempts = 0; attempts < 16; attempts++)
        {
            var frame = await ReceiveAsync(cancellationToken).ConfigureAwait(false);
            if (frame.Command == expectedCommand)
            {
                return frame;
            }

            if (frame.Command == TcpCommand.HeartbeatRequest)
            {
                await SendAsync(new TcpFrame(TcpCommand.HeartbeatResponse, string.Empty), cancellationToken)
                    .ConfigureAwait(false);
                continue;
            }

            if (frame.Command == TcpCommand.ValuePush)
            {
                continue;
            }

            throw new IOException($"Unexpected TCP command 0x{frame.Command:X2}; expected 0x{expectedCommand:X2}.");
        }

        throw new IOException("Too many unrelated frames while waiting for a TCP response.");
    }

    private async Task<TcpFrame> ReceiveAsync(CancellationToken cancellationToken)
    {
        var stream = _stream ?? throw new IOException("TCP device is not connected.");
        using var timeout = CreateTimeout(cancellationToken);
        var lengthBytes = new byte[4];
        await stream.ReadExactlyAsync(lengthBytes, timeout.Token).ConfigureAwait(false);
        var length = BinaryPrimitives.ReadInt32BigEndian(lengthBytes);
        if (length is <= 0 or > 65535)
        {
            throw new InvalidDataException($"Invalid TCP frame length: {length}.");
        }

        if (length > _options.SizeLimitBytes)
        {
            throw new InvalidDataException($"TCP frame exceeds configured limit: {length}.");
        }

        var payload = new byte[length];
        await stream.ReadExactlyAsync(payload, timeout.Token).ConfigureAwait(false);
        return TcpFrame.Decode(payload);
    }

    private CancellationTokenSource CreateTimeout(CancellationToken cancellationToken)
    {
        var source = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        source.CancelAfter(TimeSpan.FromMilliseconds(Math.Max(100, _options.TimeoutMilliseconds)));
        return source;
    }

    private static IReadOnlyList<PointSample> ParseValues(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        string body)
    {
        using var document = JsonDocument.Parse(body);
        if (!document.RootElement.TryGetProperty("values", out var values) || values.ValueKind != JsonValueKind.Array)
        {
            return [];
        }

        var definitions = points.ToDictionary(point => point.PointId, StringComparer.Ordinal);
        var result = new List<PointSample>(values.GetArrayLength());
        foreach (var item in values.EnumerateArray())
        {
            var pointId = item.TryGetProperty("pointId", out var pointIdElement)
                ? pointIdElement.GetString()
                : null;
            if (pointId is null || !definitions.TryGetValue(pointId, out var definition))
            {
                continue;
            }

            var rawValue = item.TryGetProperty("value", out var valueElement)
                ? ReadJsonValue(valueElement)
                : null;
            var qualityText = item.TryGetProperty("quality", out var qualityElement)
                ? qualityElement.GetString()
                : "GOOD";
            var quality = Enum.TryParse<PointQuality>(qualityText, true, out var parsedQuality)
                ? parsedQuality
                : PointQuality.Good;
            var timestamp = item.TryGetProperty("timestamp", out var timestampElement) &&
                            timestampElement.TryGetInt64(out var milliseconds)
                ? milliseconds * 1_000_000L
                : UnixTime.NowNanoseconds();

            result.Add(new PointSample(
                clientCode,
                pointId,
                new PointValue(PointTypeConverter.Convert(rawValue, definition.DataType), quality, timestamp)));
        }

        return result;
    }

    private static object? ReadJsonValue(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.Null or JsonValueKind.Undefined => null,
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        JsonValueKind.Number when value.TryGetInt64(out var integer) => integer,
        JsonValueKind.Number => value.GetDouble(),
        JsonValueKind.String => value.GetString(),
        _ => value.GetRawText()
    };
}
