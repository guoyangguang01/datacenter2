using SdnCustom.Shared.Model;

namespace SdnCustom.Client.Core.Configuration;

public static class ClientConfigurationValidator
{
    public static IReadOnlyList<string> Validate(ClientConfiguration configuration)
    {
        var errors = new List<string>();
        if (string.IsNullOrWhiteSpace(configuration.ClientCode))
        {
            errors.Add("客户端代码不能为空。");
        }

        if (string.IsNullOrWhiteSpace(configuration.ClientName))
        {
            errors.Add("客户端名称不能为空。");
        }

        if (string.IsNullOrWhiteSpace(configuration.Server.Host))
        {
            errors.Add("服务端地址不能为空。");
        }

        if (configuration.Server.Port is < 1 or > 65535)
        {
            errors.Add("服务端端口必须在 1-65535 之间。");
        }

        if (configuration.Server.UseTls &&
            !configuration.Server.AllowUntrustedServerCertificate &&
            string.IsNullOrWhiteSpace(configuration.Server.ServerCertificateThumbprint))
        {
            errors.Add("关闭“信任自动生成证书”时，必须填写服务端证书指纹。");
        }

        if (configuration.Device.Protocol == ClientProtocol.CustomTcp &&
            (string.IsNullOrWhiteSpace(configuration.Device.Host) || configuration.Device.Port is < 1 or > 65535))
        {
            errors.Add("TCP 设备地址和端口无效。");
        }

        if (configuration.Device.Protocol == ClientProtocol.OpcUa &&
            !Uri.TryCreate(configuration.Device.OpcUaEndpoint, UriKind.Absolute, out _))
        {
            errors.Add("OPC UA Endpoint 不是有效 URI。");
        }

        if (configuration.UserToCenterFrequencyMilliseconds is < 20 or > 60000)
        {
            errors.Add("采集发送周期必须在 20-60000ms 之间。");
        }

        var duplicates = configuration.Points
            .GroupBy(point => point.PointId, StringComparer.Ordinal)
            .Where(group => group.Count() > 1)
            .Select(group => group.Key);
        foreach (var duplicate in duplicates)
        {
            errors.Add($"测点 ID 重复：{duplicate}");
        }

        var pointsById = configuration.Points
            .GroupBy(point => point.PointId, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First(), StringComparer.Ordinal);
        foreach (var point in configuration.Points)
        {
            if (string.IsNullOrWhiteSpace(point.PointId) ||
                string.IsNullOrWhiteSpace(point.PointName) ||
                string.IsNullOrWhiteSpace(point.Address))
            {
                errors.Add("测点 ID、名称和地址不能为空。");
            }

            if (point.Direction == PointDirection.Output && string.IsNullOrWhiteSpace(point.SourceInputPointId))
            {
                errors.Add($"输出测点 {point.PointId} 必须引用 INPUT 测点。");
                continue;
            }

            if (point.Direction == PointDirection.Input &&
                !string.IsNullOrWhiteSpace(point.SourceInputPointId))
            {
                errors.Add($"输入测点 {point.PointId} 是数据来源，不能设置源测点引用。");
                continue;
            }

            if (point.Direction != PointDirection.Output ||
                string.IsNullOrWhiteSpace(point.SourceInputPointId))
            {
                continue;
            }

            var source = point.SourceInputPointId.Trim();
            var separator = source.IndexOf(':');
            var sourceClient = separator > 0 ? source[..separator] : configuration.ClientCode;
            if (separator > 0)
            {
                source = source[(separator + 1)..];
            }

            if (!string.Equals(sourceClient, configuration.ClientCode, StringComparison.Ordinal))
            {
                continue;
            }

            if (!pointsById.TryGetValue(source, out var sourcePoint))
            {
                errors.Add($"输出测点 {point.PointId} 引用的 INPUT 测点不存在：{source}");
                continue;
            }

            if (sourcePoint.Direction != PointDirection.Input)
            {
                errors.Add($"输出测点 {point.PointId} 必须引用 INPUT 测点，不能引用 {sourcePoint.Direction}。");
            }

            if (sourcePoint.DataType != point.DataType)
            {
                errors.Add(
                    $"输出测点 {point.PointId} 与源 INPUT 测点 {source} 的数据类型必须一致。");
            }
        }

        return errors;
    }
}
