using SdnCustom.Shared.Model;

namespace SdnCustom.Protocols;

public sealed record DeviceConnectionOptions
{
    public ClientProtocol Protocol { get; init; } = ClientProtocol.CustomTcp;
    public string Host { get; init; } = "127.0.0.1";
    public int Port { get; init; } = 9002;
    public string OpcUaEndpoint { get; init; } = "opc.tcp://127.0.0.1:4840";
    public int TimeoutMilliseconds { get; init; } = 5000;
    public int SizeLimitBytes { get; init; } = 65535;
}
