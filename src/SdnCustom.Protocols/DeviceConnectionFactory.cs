namespace SdnCustom.Protocols;

public sealed class DeviceConnectionFactory : IDeviceConnectionFactory
{
    public IDeviceConnection Create(DeviceConnectionOptions options) => options.Protocol switch
    {
        Shared.Model.ClientProtocol.CustomTcp => new Tcp.CustomTcpDeviceConnection(),
        Shared.Model.ClientProtocol.OpcUa => new OpcUa.OpcUaDeviceConnection(new OpcUa.Open62541NativeBridge()),
        _ => throw new ArgumentOutOfRangeException(nameof(options), options.Protocol, "Unsupported device protocol")
    };
}
