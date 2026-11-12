using System.Globalization;
using System.Text;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;

namespace SdnCustom.Protocols.OpcUa;

public sealed class OpcUaDeviceConnection : IDeviceConnection
{
    private readonly IOpen62541NativeBridge _bridge;
    private readonly SemaphoreSlim _ioGate = new(1, 1);
    private nint _handle;

    public OpcUaDeviceConnection(IOpen62541NativeBridge bridge)
    {
        _bridge = bridge;
    }

    public ClientProtocol Protocol => ClientProtocol.OpcUa;
    public bool IsConnected => _handle != nint.Zero;

    public async Task ConnectAsync(
        DeviceConnectionOptions options,
        CancellationToken cancellationToken = default)
    {
        await _ioGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            _handle = await Task.Run(() =>
            {
                var handle = _bridge.Create();
                try
                {
                    var result = _bridge.Connect(handle, options.OpcUaEndpoint);
                    if (result != 0)
                    {
                        throw new IOException($"open62541 connect failed with status 0x{result:X8}.");
                    }

                    return handle;
                }
                catch
                {
                    _bridge.Destroy(handle);
                    throw;
                }
            }, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _ioGate.Release();
        }
    }

    public async Task<IReadOnlyList<PointSample>> ReadAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        await _ioGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            EnsureConnected();
            return await Task.Run(() =>
            {
                var values = new List<PointSample>(points.Count);
                foreach (var point in points)
                {
                    var buffer = new byte[4096];
                    var status = _bridge.Read(
                        _handle,
                        point.Address,
                        (int)point.DataType,
                        buffer,
                        out var length,
                        out var timestamp);
                    if (status != 0)
                    {
                        values.Add(new PointSample(
                            clientCode,
                            point.PointId,
                            new PointValue(null, PointQuality.Bad, UnixTime.NowNanoseconds())));
                        continue;
                    }

                    var text = Encoding.UTF8.GetString(buffer, 0, length);
                    object? value = point.DataType == PointDataType.String
                        ? text
                        : PointTypeConverter.Convert(text, point.DataType);
                    values.Add(new PointSample(
                        clientCode,
                        point.PointId,
                        new PointValue(value, PointQuality.Good, timestamp)));
                }

                return (IReadOnlyList<PointSample>)values;
            }, cancellationToken).ConfigureAwait(false);
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
        await _ioGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            EnsureConnected();
            var text = value is null
                ? string.Empty
                : Convert.ToString(value, CultureInfo.InvariantCulture) ?? string.Empty;
            await Task.Run(() =>
            {
                var status = _bridge.Write(_handle, point.Address, (int)point.DataType, text);
                if (status != 0)
                {
                    throw new IOException($"open62541 write failed with status 0x{status:X8}.");
                }
            }, cancellationToken).ConfigureAwait(false);
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
            if (_handle == nint.Zero)
            {
                return;
            }

            var handle = _handle;
            _handle = nint.Zero;
            await Task.Run(() =>
            {
                try
                {
                    _bridge.Disconnect(handle);
                }
                finally
                {
                    _bridge.Destroy(handle);
                }
            }, cancellationToken).ConfigureAwait(false);
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

    private void EnsureConnected()
    {
        if (_handle == nint.Zero)
        {
            throw new IOException("OPC UA device is not connected.");
        }
    }
}
