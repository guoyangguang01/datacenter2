using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class PointRouterTests
{
    [Fact]
    public void ResolvesGlobalAndLocalOutputReferences()
    {
        var router = new PointRouter();
        router.RegisterRoutes(
        [
            new RouteTarget("B", Point("OUT-B1", PointDirection.Output, "A:IN-A")),
            new RouteTarget("B", Point("OUT-B2", PointDirection.Output, "B:IN-B"))
        ]);

        var targets = router.ResolveOutputTargets(new PointSample(
            "A",
            "IN-A",
            new PointValue(42, PointQuality.Good, UnixTime.NowNanoseconds())));

        Assert.Single(targets);
        Assert.Contains(targets, target => target.Point.PointId == "OUT-B1");
    }

    [Fact]
    public void FansOutOneInputValueToEveryReferencingOutput()
    {
        var router = new PointRouter();
        router.RegisterRoutes(
        [
            new RouteTarget("A", Point("OUT-A", PointDirection.Output, "SOURCE:IN")),
            new RouteTarget("B", Point("OUT-B", PointDirection.Output, "SOURCE:IN"))
        ]);

        var targets = router.ResolveOutputTargets(new PointSample(
            "SOURCE",
            "IN",
            new PointValue(42, PointQuality.Good, UnixTime.NowNanoseconds())));

        Assert.Equal(2, targets.Count);
        Assert.Contains(targets, target => target.ClientCode == "A" && target.Point.PointId == "OUT-A");
        Assert.Contains(targets, target => target.ClientCode == "B" && target.Point.PointId == "OUT-B");
    }

    [Fact]
    public void ResolvesLinksLoadedFromSystemPointLinkTable()
    {
        var router = new PointRouter();
        router.RegisterRoutes(
        [
            new RouteTarget(
                "TARGET-A",
                new PointDefinition
                {
                    PointId = "TARGET-A00001-b",
                    PointName = "Route A",
                    Address = string.Empty,
                    DataType = PointDataType.Bool,
                    Direction = PointDirection.Output,
                    SourceInputPointId = "SOURCE:SOURCE00001-b"
                }),
            new RouteTarget(
                "TARGET-B",
                new PointDefinition
                {
                    PointId = "TARGET-B00001-b",
                    PointName = "Route B",
                    Address = "ns=1;s=ROUTE_B",
                    DataType = PointDataType.Bool,
                    Direction = PointDirection.Output,
                    SourceInputPointId = "SOURCE:SOURCE00001-b"
                })
        ]);

        var targets = router.ResolveOutputTargets(new PointSample(
            "SOURCE",
            "SOURCE00001-b",
            new PointValue(true, PointQuality.Good, UnixTime.NowNanoseconds())));

        Assert.Equal(2, targets.Count);
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
