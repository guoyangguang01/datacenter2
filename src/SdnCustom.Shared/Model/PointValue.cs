namespace SdnCustom.Shared.Model;

public sealed record PointValue(object? Value, PointQuality Quality, long TimestampUnixNanoseconds)
{
    public static PointValue CommLost(long timestampUnixNanoseconds) =>
        new(null, PointQuality.CommLost, timestampUnixNanoseconds);
}

public sealed record PointSample(
    string ClientCode,
    string PointId,
    PointValue Value,
    string? SourceClientCode = null,
    string? SourcePointId = null)
{
    public PointKey GlobalKey => PointKey.Create(ClientCode, PointId);
}
