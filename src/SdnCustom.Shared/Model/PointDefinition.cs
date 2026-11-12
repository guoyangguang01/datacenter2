namespace SdnCustom.Shared.Model;

public sealed record PointDefinition
{
    public required string PointId { get; init; }
    public required string PointName { get; init; }
    public required string Address { get; init; }
    public PointDataType DataType { get; init; } = PointDataType.Float64;
    public string Unit { get; init; } = string.Empty;
    public PointDirection Direction { get; init; } = PointDirection.Input;
    public string? SourceInputPointId { get; init; }
    public double Deadband { get; init; }
    public string Group { get; init; } = "默认";
    public bool Writable => Direction == PointDirection.Output;
}
