using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;

namespace SdnCustom.Protocols.Tcp;

public sealed class TcpServerDeviceConnection : IDeviceConnection
{
    private readonly object _peersGate = new();
    private readonly ConcurrentDictionary<string, PointSample> _latestInputs = new(StringComparer.Ordinal);
    private readonly HashSet<TcpPeer> _peers = [];
    private readonly Dictionary<string, PointDefinition> _inputPoints = new(StringComparer.Ordinal);
    private TcpListener? _listener;
    private CancellationTokenSource? _cancellation;
    private Task? _acceptTask;
    private DeviceConnectionOptions _options = new();

    public ClientProtocol Protocol => ClientProtocol.Tcp;
    public bool IsConnected => _listener is not null && _cancellation?.IsCancellationRequested == false;

    public Task ConnectAsync(
        DeviceConnectionOptions options,
        CancellationToken cancellationToken = default)
    {
        if (!IPAddress.TryParse(options.Host, out var address))
        {
            throw new InvalidOperationException($"燃油系统 TCP 监听地址无效：{options.Host}");
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
        bool hasPeer;
        lock (_peersGate)
        {
            hasPeer = _peers.Count > 0;
        }

        var now = UnixTime.NowNanoseconds();
        var values = points
            .Where(point => point.Direction == PointDirection.Input)
            .Select(point => hasPeer && _latestInputs.TryGetValue(point.PointId, out var sample)
                ? sample
                : new PointSample(
                    clientCode,
                    point.PointId,
                    new PointValue(null, PointQuality.CommLost, now)))
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

        var body = TcpMessageCodec.EncodeValues(
            items.Select(item => new TcpValue(item.Point.PointId, item.Value)));
        var frame = TcpFrame.Encode(body);
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
                await peer.DisposeAsync().ConfigureAwait(false);
            }
        }

        var success = delivered > 0;
        var error = success ? string.Empty : "没有已连接的燃油系统 TCP 用户，输出未发送。";
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
            client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
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
            var maximumBodyLength = Math.Max(
                _options.SizeLimitBytes,
                TcpFrame.DefaultMaximumBodyLength);
            while (!cancellationToken.IsCancellationRequested)
            {
                var frame = await TcpFrame.ReadAsync(
                    peer.Stream,
                    maximumBodyLength,
                    cancellationToken).ConfigureAwait(false);
                ApplyInputValues(frame.Body);
            }
        }
        catch (Exception exception) when (
            exception is IOException or SocketException or ObjectDisposedException or
                OperationCanceledException or InvalidDataException or OverflowException)
        {
        }
        finally
        {
            RemovePeer(peer);
            await peer.DisposeAsync().ConfigureAwait(false);
        }
    }

    private void ApplyInputValues(ReadOnlySpan<byte> body)
    {
        foreach (var item in TcpMessageCodec.DecodeValues(body))
        {
            if (!_inputPoints.TryGetValue(item.PointId, out var point))
            {
                continue;
            }

            try
            {
                _latestInputs[item.PointId] = new PointSample(
                    _options.ClientCode,
                    item.PointId,
                    new PointValue(
                        PointTypeConverter.Convert(item.Value, point.DataType),
                        PointQuality.Good,
                        UnixTime.NowNanoseconds()));
            }
            catch (Exception exception) when (
                exception is FormatException or InvalidCastException or OverflowException)
            {
            }
        }
    }

    private void RemovePeer(TcpPeer peer)
    {
        lock (_peersGate)
        {
            _peers.Remove(peer);
            if (_peers.Count == 0)
            {
                _latestInputs.Clear();
            }
        }
    }

    private sealed class TcpPeer(TcpClient client) : IAsyncDisposable
    {
        private readonly SemaphoreSlim _sendGate = new(1, 1);
        private int _disposed;

        public NetworkStream Stream { get; } = client.GetStream();

        public async Task SendAsync(byte[] frame, CancellationToken cancellationToken)
        {
            ObjectDisposedException.ThrowIf(
                Volatile.Read(ref _disposed) != 0,
                this);
            await _sendGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                ObjectDisposedException.ThrowIf(
                    Volatile.Read(ref _disposed) != 0,
                    this);
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
            if (Interlocked.Exchange(ref _disposed, 1) != 0)
            {
                return ValueTask.CompletedTask;
            }

            try
            {
                client.Client.Shutdown(SocketShutdown.Both);
            }
            catch (Exception exception) when (exception is SocketException or ObjectDisposedException)
            {
            }
            Stream.Dispose();
            client.Dispose();
            _sendGate.Dispose();
            return ValueTask.CompletedTask;
        }
    }
}
