using System.Globalization;
using SdnCustom.Shared.Model;

namespace SdnCustom.Shared;

public static class PointTypeConverter
{
    public static object? Convert(object? value, PointDataType dataType)
    {
        if (value is null)
        {
            return null;
        }

        return dataType switch
        {
            PointDataType.Bool => value is bool boolean ? boolean : bool.Parse(value.ToString()!),
            PointDataType.Int16 => System.Convert.ToInt16(value, CultureInfo.InvariantCulture),
            PointDataType.Int32 => System.Convert.ToInt32(value, CultureInfo.InvariantCulture),
            PointDataType.Float32 => System.Convert.ToSingle(value, CultureInfo.InvariantCulture),
            PointDataType.Float64 => System.Convert.ToDouble(value, CultureInfo.InvariantCulture),
            PointDataType.String => value.ToString(),
            _ => value
        };
    }
}
