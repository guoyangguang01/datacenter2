using System.Text.Json;

namespace SdnCustom.Server.Admin;

public sealed record CreateClientRequest(
    string ClientCode,
    string ClientName,
    string? SystemCode = null);
public sealed record UpdateClientRequest(string ClientName);
public sealed record WritePointRequest(string PointId, JsonElement Value);
public sealed record ImportGasMasterRequest(string SystemCode, string SystemName, string Csv);
public sealed record ClientDirectoryItem(
    string ClientCode,
    string ClientName,
    string SystemCode,
    string SystemName,
    bool Online);
