using SdnCustom.Shared.Model;

namespace SdnCustom.Protocols;

public sealed record DeviceWriteItem(
    PointDefinition Point,
    object? Value,
    long TimestampUnixNanoseconds,
    string? SourceClientCode = null,
    string? SourcePointId = null);

public sealed record DeviceWriteResult(
    string PointId,
    bool Success,
    string ErrorMessage = "");

public interface IDeviceConnection : IAsyncDisposable
{
    ClientProtocol Protocol { get; }
    bool IsConnected { get; }
    Task ConnectAsync(DeviceConnectionOptions options, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<PointSample>> ReadAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default);
    async Task WriteAsync(
        string clientCode,
        PointDefinition point,
        object? value,
        CancellationToken cancellationToken = default)
    {
        var results = await WriteBatchAsync(
            clientCode,
            [new DeviceWriteItem(point, value, 0)],
            cancellationToken).ConfigureAwait(false);
        var result = results.FirstOrDefault(item => item.PointId == point.PointId);
        if (result is null || !result.Success)
        {
            throw new IOException(
                string.IsNullOrWhiteSpace(result?.ErrorMessage)
                    ? $"Device rejected write: {point.PointId}"
                    : result.ErrorMessage);
        }
    }
    Task<IReadOnlyList<DeviceWriteResult>> WriteBatchAsync(
        string clientCode,
        IReadOnlyList<DeviceWriteItem> items,
        CancellationToken cancellationToken = default);
    Task DisconnectAsync(CancellationToken cancellationToken = default);
}
