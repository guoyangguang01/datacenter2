namespace SdnCustom.Server.Persistence;

public sealed class BusinessSystemEntity
{
    public string SystemCode { get; set; } = string.Empty;
    public string SystemName { get; set; } = string.Empty;
    public string Description { get; set; } = string.Empty;
    public DateTimeOffset CreatedAt { get; set; }
    public List<ClientSystemEntity> Clients { get; set; } = [];
    public List<PointLinkEntity> Links { get; set; } = [];
}

public sealed class ClientSystemEntity
{
    public string ClientCode { get; set; } = string.Empty;
    public string ClientName { get; set; } = string.Empty;
    public string SystemCode { get; set; } = string.Empty;
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
    public BusinessSystemEntity? BusinessSystem { get; set; }
}

public sealed class PointCatalogEntity
{
    public string ClientCode { get; set; } = string.Empty;
    public string PointId { get; set; } = string.Empty;
    public string PointName { get; set; } = string.Empty;
    public string Address { get; set; } = string.Empty;
    public string Protocol { get; set; } = string.Empty;
    public string DataType { get; set; } = string.Empty;
    public string Unit { get; set; } = string.Empty;
    public string Direction { get; set; } = string.Empty;
    public string OpcUaParameter1 { get; set; } = string.Empty;
    public string OpcUaParameter2 { get; set; } = string.Empty;
    public string OpcUaParameter3 { get; set; } = string.Empty;
    public double Deadband { get; set; }
    public string Group { get; set; } = string.Empty;
    public ClientSystemEntity? ClientSystem { get; set; }
}

public sealed class LatestValueEntity
{
    public string ClientCode { get; set; } = string.Empty;
    public string PointId { get; set; } = string.Empty;
    public string Direction { get; set; } = string.Empty;
    public string? ValueJson { get; set; }
    public string Quality { get; set; } = string.Empty;
    public long TimestampUnixNanoseconds { get; set; }
    public string SourceClientCode { get; set; } = string.Empty;
    public string SourcePointId { get; set; } = string.Empty;
}

public sealed class PointLinkEntity
{
    public string LinkId { get; set; } = string.Empty;
    public string SystemCode { get; set; } = string.Empty;
    public string Declaration { get; set; } = string.Empty;
    public string SourceClientCode { get; set; } = string.Empty;
    public string SourcePointId { get; set; } = string.Empty;
    public string SourcePointName { get; set; } = string.Empty;
    public string SourceProtocol { get; set; } = string.Empty;
    public string TargetClientCode { get; set; } = string.Empty;
    public string TargetPointId { get; set; } = string.Empty;
    public bool Enabled { get; set; }
    public BusinessSystemEntity? BusinessSystem { get; set; }
}
