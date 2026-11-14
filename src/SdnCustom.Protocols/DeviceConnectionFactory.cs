namespace SdnCustom.Protocols;

public sealed class DeviceConnectionFactory : IDeviceConnectionFactory
{
    public IDeviceConnection Create(DeviceConnectionOptions options) => options.Protocol switch
    {
        Shared.Model.ClientProtocol.CustomTcp => new Tcp.CustomTcpServerDeviceConnection(),
        Shared.Model.ClientProtocol.OpcUaClient => new OpcUa.OpcUaDeviceConnection(new OpcUa.Open62541NativeBridge()),
        Shared.Model.ClientProtocol.OpcUaServer => new OpcUa.OpcUaServerDeviceConnection(),
        _ => throw new ArgumentOutOfRangeException(nameof(options), options.Protocol, "Unsupported device protocol")
    };
}
