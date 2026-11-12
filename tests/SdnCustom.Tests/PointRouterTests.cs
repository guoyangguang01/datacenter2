using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class PointRouterTests
{
    [Fact]
    public void ResolvesGlobalAndLocalOutputReferences()
    {
        var router = new PointRouter();
        router.RegisterClient("A",
        [
            Point("IN-A", PointDirection.Input, null)
        ]);
        router.RegisterClient("B",
        [
            Point("OUT-B1", PointDirection.Output, "A:IN-A"),
            Point("IN-B", PointDirection.Input, null),
            Point("OUT-B2", PointDirection.Output, "IN-B"),
            Point("OUT-B3", PointDirection.Output, "B:OTHER")
        ]);

        var targets = router.ResolveOutputTargets(new PointSample(
            "A",
            "IN-A",
            new PointValue(42, PointQuality.Good, UnixTime.NowNanoseconds())));

        Assert.Single(targets);
        Assert.Contains(targets, target => target.Point.PointId == "OUT-B1");
        Assert.DoesNotContain(targets, target => target.Point.PointId == "OUT-B3");

        var localTargets = router.ResolveOutputTargets(new PointSample(
            "B",
            "IN-B",
            new PointValue(7, PointQuality.Good, UnixTime.NowNanoseconds())));
        Assert.Single(localTargets);
        Assert.Equal("OUT-B2", localTargets[0].Point.PointId);
    }

    [Fact]
    public void FansOutOneInputValueToEveryReferencingOutput()
    {
        var router = new PointRouter();
        router.RegisterClient("SOURCE", [Point("IN", PointDirection.Input, null)]);
        router.RegisterClient("A", [Point("OUT-A", PointDirection.Output, "SOURCE:IN")]);
        router.RegisterClient("B", [Point("OUT-B", PointDirection.Output, "SOURCE:IN")]);

        var targets = router.ResolveOutputTargets(new PointSample(
            "SOURCE",
            "IN",
            new PointValue(42, PointQuality.Good, UnixTime.NowNanoseconds())));

        Assert.Equal(2, targets.Count);
        Assert.Contains(targets, target => target.ClientCode == "A" && target.Point.PointId == "OUT-A");
        Assert.Contains(targets, target => target.ClientCode == "B" && target.Point.PointId == "OUT-B");
    }

    private static PointDefinition Point(string id, PointDirection direction, string? source) => new()
    {
        PointId = id,
        PointName = id,
        Address = id,
        Direction = direction,
        SourceInputPointId = source
    };
}
