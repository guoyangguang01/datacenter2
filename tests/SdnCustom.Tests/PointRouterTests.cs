using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class PointRouterTests
{
    [Fact]
    public void ResolvesGlobalAndLocalInputReferences()
    {
        var router = new PointRouter();
        router.RegisterClient("A",
        [
            Point("OUT-A", PointDirection.Output, null)
        ]);
        router.RegisterClient("B",
        [
            Point("IN-B1", PointDirection.Input, "A:OUT-A"),
            Point("OUT-B", PointDirection.Output, null),
            Point("IN-B2", PointDirection.Input, "OUT-B"),
            Point("IN-B3", PointDirection.Input, "B:OTHER")
        ]);

        var targets = router.ResolveInputTargets(new PointSample(
            "A",
            "OUT-A",
            new PointValue(42, PointQuality.Good, UnixTime.NowNanoseconds())));

        Assert.Single(targets);
        Assert.Contains(targets, target => target.Point.PointId == "IN-B1");
        Assert.DoesNotContain(targets, target => target.Point.PointId == "IN-B3");

        var localTargets = router.ResolveInputTargets(new PointSample(
            "B",
            "OUT-B",
            new PointValue(7, PointQuality.Good, UnixTime.NowNanoseconds())));
        Assert.Single(localTargets);
        Assert.Equal("IN-B2", localTargets[0].Point.PointId);
    }

    private static PointDefinition Point(string id, PointDirection direction, string? source) => new()
    {
        PointId = id,
        PointName = id,
        Address = id,
        Direction = direction,
        SourceOutputPointId = source
    };
}
