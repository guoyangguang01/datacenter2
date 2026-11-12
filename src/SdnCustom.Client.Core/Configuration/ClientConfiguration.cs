using SdnCustom.Shared.Model;

namespace SdnCustom.Client.Core.Configuration;

public sealed class ClientConfiguration
{
    public int SchemaVersion { get; set; } = 1;
    public string ClientCode { get; set; } = "CLIENT-001";
    public string ClientName { get; set; } = "单连接客户端";
    public ServerConnectionOptions Server { get; set; } = new();
    public DeviceOptions Device { get; set; } = new();
    public bool AutoStart { get; set; }
    public int CenterToUserFrequencyMilliseconds { get; set; } = 200;
    public int UserToCenterFrequencyMilliseconds { get; set; } = 200;
    public bool AllowCenterToUserMissingItems { get; set; } = true;
    public bool AllowUserToCenterMissingItems { get; set; } = true;
    public bool SendCenterToUserOnChange { get; set; }
    public bool SendUserToCenterOnChange { get; set; }
    public List<PointDefinition> Points { get; set; } = [];
}

public sealed class ServerConnectionOptions
{
    public string Host { get; set; } = "127.0.0.1";
    public int Port { get; set; } = 7443;
    public bool UseTls { get; set; } = true;
    public string ServerCertificateThumbprint { get; set; } = string.Empty;
    public bool AllowUntrustedServerCertificate { get; set; } = true;
}

public sealed class DeviceOptions
{
    public ClientProtocol Protocol { get; set; } = ClientProtocol.CustomTcp;
    public string Host { get; set; } = "127.0.0.1";
    public int Port { get; set; } = 9002;
    public string OpcUaEndpoint { get; set; } = "opc.tcp://127.0.0.1:4840";
    public int TimeoutMilliseconds { get; set; } = 5000;
}
