using System.Globalization;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.History;

public static class InfluxLineProtocol
{
    public static string From(PointSample sample)
    {
        var direction = string.IsNullOrWhiteSpace(sample.SourcePointId) ? "OUTPUT" : "INPUT";
        var tags =
            $"point_value,client_code={EscapeTag(sample.ClientCode)},point_id={EscapeTag(sample.PointId)},direction={direction}";
        var fields = sample.Value.Value switch
        {
            null => "value_string=\"\"",
            bool value => $"value_bool={(value ? "true" : "false")}",
            string value => $"value_string=\"{EscapeField(value)}\"",
            byte or sbyte or short or ushort or int or uint or long or ulong =>
                $"value_int={Convert.ToInt64(sample.Value.Value, CultureInfo.InvariantCulture)}i",
            float or double or decimal =>
                $"value_double={Convert.ToDouble(sample.Value.Value, CultureInfo.InvariantCulture).ToString("R", CultureInfo.InvariantCulture)}",
            _ => $"value_string=\"{EscapeField(sample.Value.Value.ToString() ?? string.Empty)}\""
        };
        return $"{tags} {fields},quality=\"{sample.Value.Quality.ToString().ToUpperInvariant()}\" {sample.Value.TimestampUnixNanoseconds}";
    }

    private static string EscapeTag(string value) =>
        value.Replace("\\", "\\\\")
            .Replace(" ", "\\ ")
            .Replace(",", "\\,")
            .Replace("=", "\\=")
            .Replace("\r", "\\r")
            .Replace("\n", "\\n");

    private static string EscapeField(string value) =>
        value.Replace("\\", "\\\\")
            .Replace("\"", "\\\"")
            .Replace("\r", "\\r")
            .Replace("\n", "\\n");
}
