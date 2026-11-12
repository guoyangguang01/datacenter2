using SdnCustom.Shared;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class ChangeGateTests
{
    [Fact]
    public void FiltersValuesInsideDeadband()
    {
        var gate = new ChangeGate();
        var points = new[]
        {
            new PointDefinition
            {
                PointId = "p1",
                PointName = "Pressure",
                Address = "40001",
                DataType = PointDataType.Float64,
                Deadband = 0.5
            }
        };

        var first = gate.Filter("client-a", points, [Sample("p1", 10.0)]);
        var inside = gate.Filter("client-a", points, [Sample("p1", 10.4)]);
        var outside = gate.Filter("client-a", points, [Sample("p1", 10.6)]);

        Assert.Single(first);
        Assert.Empty(inside);
        Assert.Single(outside);
    }

    [Fact]
    public void TreatsQualityChangeAsEffective()
    {
        var previous = new PointValue(12.0, PointQuality.Good, UnixTime.NowNanoseconds());
        var current = new PointValue(12.0, PointQuality.Bad, UnixTime.NowNanoseconds());

        Assert.True(ChangeGate.IsEffectiveChange(previous, current, 100));
    }

    [Fact]
    public void UsesEqualityForStrings()
    {
        var previous = new PointValue("RUN", PointQuality.Good, UnixTime.NowNanoseconds());
        Assert.False(ChangeGate.IsEffectiveChange(
            previous,
            previous with { TimestampUnixNanoseconds = previous.TimestampUnixNanoseconds + 1 },
            10));
        Assert.True(ChangeGate.IsEffectiveChange(
            previous,
            new PointValue("STOP", PointQuality.Good, UnixTime.NowNanoseconds()),
            10));
    }

    private static PointSample Sample(string pointId, double value) => new(
        "client-a",
        pointId,
        new PointValue(value, PointQuality.Good, UnixTime.NowNanoseconds()));
}
