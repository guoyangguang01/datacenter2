namespace SdnCustom.Server.Persistence;

public sealed class ClientSystemEntity
{
    public string ClientCode { get; set; } = string.Empty;
    public string ClientName { get; set; } = string.Empty;
    public bool Authorized { get; set; }
    public bool DesiredRunning { get; set; }
    public bool PointsConfigured { get; set; }
    public string Protocol { get; set; } = string.Empty;
    public string EndpointDisplay { get; set; } = string.Empty;
    public string AppVersion { get; set; } = string.Empty;
    public string CatalogRevision { get; set; } = string.Empty;
    public DateTimeOffset FirstSeen { get; set; }
    public DateTimeOffset LastSeen { get; set; }
    public List<PointCatalogEntity> Points { get; set; } = [];
}

public sealed class PointCatalogEntity
{
    public string ClientCode { get; set; } = string.Empty;
    public string PointId { get; set; } = string.Empty;
    public string PointName { get; set; } = string.Empty;
    public string Address { get; set; } = string.Empty;
    public string DataType { get; set; } = string.Empty;
    public string Unit { get; set; } = string.Empty;
    public string Direction { get; set; } = string.Empty;
    public string SourceInputPointId { get; set; } = string.Empty;
    public double Deadband { get; set; }
    public string Group { get; set; } = string.Empty;
    public ClientSystemEntity? ClientSystem { get; set; }
}

public sealed class LatestValueEntity
{
    public string ClientCode { get; set; } = string.Empty;
    public string PointId { get; set; } = string.Empty;
    public string? ValueJson { get; set; }
    public string Quality { get; set; } = string.Empty;
    public long TimestampUnixNanoseconds { get; set; }
    public string SourceClientCode { get; set; } = string.Empty;
    public string SourcePointId { get; set; } = string.Empty;
}
