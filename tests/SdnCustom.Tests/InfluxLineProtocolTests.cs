using SdnCustom.Server.History;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class InfluxLineProtocolTests
{
    [Fact]
    public void WritesOutputDoubleWithEscapedTags()
    {
        var line = InfluxLineProtocol.From(new PointSample(
            "CLIENT A",
            "TEMP,1",
            new PointValue(21.5, PointQuality.Good, 1_700_000_000_000_000_000L)));

        Assert.Equal(
            "point_value,client_code=CLIENT\\ A,point_id=TEMP\\,1,direction=OUTPUT " +
            "value_double=21.5,quality=\"GOOD\" 1700000000000000000",
            line);
    }

    [Fact]
    public void WritesInputStringsAndSourceDirection()
    {
        var line = InfluxLineProtocol.From(new PointSample(
            "B",
            "IN",
            new PointValue("RUN \"A\"", PointQuality.Uncertain, 42),
            "A",
            "OUT"));

        Assert.Contains("direction=INPUT", line);
        Assert.Contains("value_string=\"RUN \\\"A\\\"\"", line);
        Assert.Contains("quality=\"UNCERTAIN\" 42", line);
    }
}
