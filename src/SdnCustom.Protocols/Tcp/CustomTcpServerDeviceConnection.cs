using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;

namespace SdnCustom.Protocols.Tcp;

public sealed class CustomTcpServerDeviceConnection : IDeviceConnection
{
    private readonly object _peersGate = new();
    private readonly ConcurrentDictionary<string, PointSample> _latestInputs = new(StringComparer.Ordinal);
    private readonly HashSet<TcpPeer> _peers = [];
    private readonly Dictionary<string, PointDefinition> _inputPoints = new(StringComparer.Ordinal);
    private TcpListener? _listener;
    private CancellationTokenSource? _cancellation;
    private Task? _acceptTask;
    private DeviceConnectionOptions _options = new();

    public ClientProtocol Protocol => ClientProtocol.CustomTcp;
    public bool IsConnected => _listener is not null && _cancellation?.IsCancellationRequested == false;

    public Task ConnectAsync(
        DeviceConnectionOptions options,
        CancellationToken cancellationToken = default)
    {
        if (!IPAddress.TryParse(options.Host, out var address))
        {
            throw new InvalidOperationException($"TCP 服务端监听地址无效：{options.Host}");
        }

        _options = options;
        _latestInputs.Clear();
        _inputPoints.Clear();
        foreach (var point in options.Points.Where(point => point.Direction == PointDirection.Input))
        {
            _inputPoints[point.PointId] = point;
        }

        _cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _listener = new TcpListener(address, options.Port);
        _listener.Start();
        _acceptTask = AcceptClientsAsync(_listener, _cancellation.Token);
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<PointSample>> ReadAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var values = points
            .Where(point => point.Direction == PointDirection.Input)
            .Select(point => _latestInputs.TryGetValue(point.PointId, out var sample)
                ? sample
                : new PointSample(
                    clientCode,
                    point.PointId,
                    new PointValue(null, PointQuality.CommLost, UnixTime.NowNanoseconds())))
            .ToArray();
        return Task.FromResult<IReadOnlyList<PointSample>>(values);
    }

    public async Task<IReadOnlyList<DeviceWriteResult>> WriteBatchAsync(
        string clientCode,
        IReadOnlyList<DeviceWriteItem> items,
        CancellationToken cancellationToken = default)
    {
        if (items.Count == 0)
        {
            return [];
        }

        var body = JsonSerializer.Serialize(new
        {
            values = items.Select(item => new
            {
                pointId = item.Point.PointId,
                value = item.Value,
                quality = "Good",
                timestamp = item.TimestampUnixNanoseconds / 1_000_000L
            }).ToArray()
        });
        var frame = new TcpFrame(TcpCommand.ValuePush, body).Encode();
        TcpPeer[] peers;
        lock (_peersGate)
        {
            peers = _peers.ToArray();
        }

        var delivered = 0;
        foreach (var peer in peers)
        {
            try
            {
                await peer.SendAsync(frame, cancellationToken).ConfigureAwait(false);
                delivered++;
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                RemovePeer(peer);
            }
        }

        var success = delivered > 0;
        var error = success ? string.Empty : "没有已连接的外部 TCP 客户端，输出未发送。";
        return items
            .Select(item => new DeviceWriteResult(item.Point.PointId, success, error))
            .ToArray();
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken = default)
    {
        var cancellation = _cancellation;
        _cancellation = null;
        if (cancellation is not null)
        {
            await cancellation.CancelAsync().ConfigureAwait(false);
        }

        _listener?.Stop();
        _listener = null;
        TcpPeer[] peers;
        lock (_peersGate)
        {
            peers = _peers.ToArray();
            _peers.Clear();
        }
        foreach (var peer in peers)
        {
            await peer.DisposeAsync().ConfigureAwait(false);
        }

        if (_acceptTask is not null)
        {
            try
            {
                await _acceptTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
            catch (SocketException)
            {
            }
            _acceptTask = null;
        }

        cancellation?.Dispose();
        _latestInputs.Clear();
    }

    public async ValueTask DisposeAsync()
    {
        await DisconnectAsync().ConfigureAwait(false);
    }

    private async Task AcceptClientsAsync(
        TcpListener listener,
        CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }

            client.NoDelay = true;
            var peer = new TcpPeer(client);
            lock (_peersGate)
            {
                _peers.Add(peer);
            }
            _ = ReadPeerAsync(peer, cancellationToken);
        }
    }

    private async Task ReadPeerAsync(TcpPeer peer, CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                var frame = await ReceiveAsync(
                    peer.Stream,
                    _options.SizeLimitBytes,
                    cancellationToken).ConfigureAwait(false);
                switch (frame.Command)
                {
                    case TcpCommand.ValuePush:
                        ApplyInputPush(frame.Body);
                        break;
                    case TcpCommand.HeartbeatRequest:
                        await peer.SendAsync(
                            new TcpFrame(TcpCommand.HeartbeatResponse, string.Empty).Encode(),
                            cancellationToken).ConfigureAwait(false);
                        break;
                }
            }
        }
        catch (Exception exception) when (
            exception is IOException or SocketException or OperationCanceledException or InvalidDataException)
        {
        }
        finally
        {
            RemovePeer(peer);
            await peer.DisposeAsync().ConfigureAwait(false);
        }
    }

    private void ApplyInputPush(string body)
    {
        using var document = JsonDocument.Parse(body);
        if (!document.RootElement.TryGetProperty("values", out var values) ||
            values.ValueKind != JsonValueKind.Array)
        {
            return;
        }

        foreach (var item in values.EnumerateArray())
        {
            var pointId = item.TryGetProperty("pointId", out var pointIdElement)
                ? pointIdElement.GetString()
                : null;
            if (pointId is null || !_inputPoints.TryGetValue(pointId, out var point))
            {
                continue;
            }

            var rawValue = item.TryGetProperty("value", out var valueElement)
                ? ReadJsonValue(valueElement)
                : null;
            var qualityText = item.TryGetProperty("quality", out var qualityElement)
                ? qualityElement.GetString()
                : "Good";
            var quality = Enum.TryParse<PointQuality>(qualityText, true, out var parsedQuality)
                ? parsedQuality
                : PointQuality.Good;
            var timestamp = item.TryGetProperty("timestamp", out var timestampElement) &&
                            timestampElement.TryGetInt64(out var milliseconds)
                ? milliseconds * 1_000_000L
                : UnixTime.NowNanoseconds();
            _latestInputs[pointId] = new PointSample(
                _options.ClientCode,
                pointId,
                new PointValue(
                    PointTypeConverter.Convert(rawValue, point.DataType),
                    quality,
                    timestamp));
        }
    }

    private void RemovePeer(TcpPeer peer)
    {
        lock (_peersGate)
        {
            _peers.Remove(peer);
        }
    }

    private static async Task<TcpFrame> ReceiveAsync(
        NetworkStream stream,
        int sizeLimitBytes,
        CancellationToken cancellationToken)
    {
        var lengthBytes = new byte[4];
        await stream.ReadExactlyAsync(lengthBytes, cancellationToken).ConfigureAwait(false);
        var length = BinaryPrimitives.ReadInt32BigEndian(lengthBytes);
        if (length is <= 0 or > 65535 || length > sizeLimitBytes)
        {
            throw new InvalidDataException($"Invalid TCP frame length: {length}.");
        }

        var payload = new byte[length];
        await stream.ReadExactlyAsync(payload, cancellationToken).ConfigureAwait(false);
        return TcpFrame.Decode(payload);
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

    private sealed class TcpPeer(TcpClient client) : IAsyncDisposable
    {
        private readonly SemaphoreSlim _sendGate = new(1, 1);

        public NetworkStream Stream { get; } = client.GetStream();

        public async Task SendAsync(byte[] frame, CancellationToken cancellationToken)
        {
            await _sendGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                await Stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
                await Stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                _sendGate.Release();
            }
        }

        public ValueTask DisposeAsync()
        {
            try
            {
                client.Client.Shutdown(SocketShutdown.Both);
            }
            catch (SocketException)
            {
            }
            Stream.Dispose();
            client.Dispose();
            _sendGate.Dispose();
            return ValueTask.CompletedTask;
        }
    }
}
