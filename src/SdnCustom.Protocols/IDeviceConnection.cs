using SdnCustom.Shared.Model;

namespace SdnCustom.Protocols;

public interface IDeviceConnection : IAsyncDisposable
{
    ClientProtocol Protocol { get; }
    bool IsConnected { get; }
    Task ConnectAsync(DeviceConnectionOptions options, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<PointSample>> ReadAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default);
    Task WriteAsync(
        string clientCode,
        PointDefinition point,
        object? value,
        CancellationToken cancellationToken = default);
    Task DisconnectAsync(CancellationToken cancellationToken = default);
}
