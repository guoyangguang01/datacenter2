using System.Globalization;
using System.Text;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;

namespace SdnCustom.Protocols.OpcUa;

public sealed class OpcUaDeviceConnection : IDeviceConnection
{
    private readonly IOpen62541NativeBridge _bridge;
    private nint _handle;

    public OpcUaDeviceConnection(IOpen62541NativeBridge bridge)
    {
        _bridge = bridge;
    }

    public ClientProtocol Protocol => ClientProtocol.OpcUa;
    public bool IsConnected => _handle != nint.Zero;

    public Task ConnectAsync(DeviceConnectionOptions options, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        _handle = _bridge.Create();
        var result = _bridge.Connect(_handle, options.OpcUaEndpoint);
        if (result != 0)
        {
            _bridge.Destroy(_handle);
            _handle = nint.Zero;
            throw new IOException($"open62541 connect failed with status 0x{result:X8}.");
        }

        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<PointSample>> ReadAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        EnsureConnected();
        var values = new List<PointSample>(points.Count);
        foreach (var point in points)
        {
            cancellationToken.ThrowIfCancellationRequested();
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

        return Task.FromResult<IReadOnlyList<PointSample>>(values);
    }

    public Task WriteAsync(
        string clientCode,
        PointDefinition point,
        object? value,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        EnsureConnected();
        var text = value is null ? string.Empty : Convert.ToString(value, CultureInfo.InvariantCulture) ?? string.Empty;
        var status = _bridge.Write(_handle, point.Address, (int)point.DataType, text);
        if (status != 0)
        {
            throw new IOException($"open62541 write failed with status 0x{status:X8}.");
        }

        return Task.CompletedTask;
    }

    public Task DisconnectAsync(CancellationToken cancellationToken = default)
    {
        if (_handle != nint.Zero)
        {
            _bridge.Disconnect(_handle);
            _bridge.Destroy(_handle);
            _handle = nint.Zero;
        }

        return Task.CompletedTask;
    }

    public async ValueTask DisposeAsync()
    {
        await DisconnectAsync().ConfigureAwait(false);
    }

    private void EnsureConnected()
    {
        if (_handle == nint.Zero)
        {
            throw new IOException("OPC UA device is not connected.");
        }
    }
}
