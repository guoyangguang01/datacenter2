using System.Text.Json;

namespace SdnCustom.Server.Admin;

public sealed record CreateClientRequest(string ClientCode, string ClientName);
public sealed record UpdateClientRequest(string ClientName);
public sealed record WritePointRequest(string PointId, JsonElement Value);
