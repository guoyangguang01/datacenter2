using System.Reflection;
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

    [Fact]
    public void EscapesLineBreaksInTagsAndStringFields()
    {
        var line = InfluxLineProtocol.From(new PointSample(
            "CLIENT\nA",
            "IN",
            new PointValue("line 1\r\nline 2", PointQuality.Good, 1)));

        Assert.Contains("client_code=CLIENT\\nA", line);
        Assert.Contains("value_string=\"line 1\\r\\nline 2\"", line);
    }

    [Fact]
    public void ParsesAnnotatedCsvWithQuotedCommaAndLineBreak()
    {
        const string csv =
            "#datatype,string,long,dateTime:RFC3339,string,string\n" +
            "#group,false,false,false,false,false\n" +
            "#default,_result,,,,\n" +
            ",result,table,_time,_value,quality\n" +
            ",,0,2026-11-12T10:00:00Z,\"hello,\nworld\",GOOD\n\n" +
            ",result,table,_time,_value,quality\n" +
            ",,0,2026-11-12T10:01:00Z,12.5,UNCERTAIN\n";
        var method = typeof(InfluxHistoryStore).GetMethod(
            "ParseCsv",
            BindingFlags.NonPublic | BindingFlags.Static)
            ?? throw new InvalidOperationException("ParseCsv method not found.");

        var points = Assert.IsType<List<HistoryPoint>>(method.Invoke(null, [csv]));

        Assert.Equal(2, points.Count);
        Assert.Equal("hello,\nworld", points[0].Value);
        Assert.Equal(PointQuality.Good, points[0].Quality);
        Assert.Equal(12.5, points[1].Value);
        Assert.Equal(PointQuality.Uncertain, points[1].Quality);
    }
}
