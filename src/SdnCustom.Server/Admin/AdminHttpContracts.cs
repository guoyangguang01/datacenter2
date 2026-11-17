using System.Text.Json;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Admin;

public sealed record CreateClientRequest(
    string ClientCode,
    string ClientName,
    string? SystemCode = null,
    ClientProtocol Protocol = ClientProtocol.Tcp);
public sealed record UpdateClientRequest(string ClientName, ClientProtocol? Protocol = null);
public sealed record WritePointRequest(string PointId, JsonElement Value);
public sealed record ImportGasMasterRequest(string SystemCode, string SystemName, string Csv);
public sealed record ClientDirectoryItem(
    string ClientCode,
    string ClientName,
    string SystemCode,
    string SystemName,
    bool Online);
