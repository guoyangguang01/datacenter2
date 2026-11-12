namespace SdnCustom.Server.Configuration;

public sealed class ServerSettings
{
    public string DataDirectory { get; set; } = "data";
    public int GrpcPort { get; set; } = 7443;
    public int HttpPort { get; set; } = 7444;
    public bool UseTls { get; set; } = true;
    public string CertificatePath { get; set; } = string.Empty;
    public string CertificatePassword { get; set; } = string.Empty;
    public InfluxSettings Influx { get; set; } = new();
}

public sealed class InfluxSettings
{
    public string Url { get; set; } = "http://127.0.0.1:8086";
    public string Token { get; set; } = string.Empty;
    public string Org { get; set; } = "sdncustom";
    public string Bucket { get; set; } = "sdncustom";
    public int BatchSize { get; set; } = 100;
    public int QueueCapacity { get; set; } = 10_000;
}
