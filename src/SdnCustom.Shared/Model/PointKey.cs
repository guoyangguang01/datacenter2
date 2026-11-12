namespace SdnCustom.Shared.Model;

public readonly record struct PointKey(string ClientCode, string PointId)
{
    public static PointKey Create(string clientCode, string pointId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(clientCode);
        ArgumentException.ThrowIfNullOrWhiteSpace(pointId);
        return new PointKey(clientCode.Trim(), pointId.Trim());
    }

    public override string ToString() => $"{ClientCode}:{PointId}";
}
