using SdnCustom.Shared.Model;

namespace SdnCustom.Client.Core.Runtime;

public sealed record ClientLogEntry(DateTimeOffset Timestamp, string Level, string Message);

public sealed record ClientRuntimeEvent(
    ClientConnectionState State,
    string Message,
    IReadOnlyList<PointSample>? LatestValues = null);
