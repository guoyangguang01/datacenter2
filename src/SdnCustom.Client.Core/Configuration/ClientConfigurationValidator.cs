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

        if (configuration.CenterToUserFrequencyMilliseconds is < 20 or > 60000)
        {
            errors.Add("中心发往用户频率必须在 20-60000ms 之间。");
        }

        if (configuration.UserToCenterFrequencyMilliseconds is < 20 or > 60000)
        {
            errors.Add("用户发往中心频率必须在 20-60000ms 之间。");
        }

        var duplicates = configuration.Points
            .GroupBy(point => point.PointId, StringComparer.Ordinal)
            .Where(group => group.Count() > 1)
            .Select(group => group.Key);
        foreach (var duplicate in duplicates)
        {
            errors.Add($"测点 ID 重复：{duplicate}");
        }

        foreach (var point in configuration.Points)
        {
            if (string.IsNullOrWhiteSpace(point.PointId) || string.IsNullOrWhiteSpace(point.Address))
            {
                errors.Add("测点 ID 和地址不能为空。");
            }

            if (point.Direction == PointDirection.Input && string.IsNullOrWhiteSpace(point.SourceOutputPointId))
            {
                errors.Add($"输入测点 {point.PointId} 必须引用 OUTPUT 测点。");
            }
        }

        return errors;
    }
}
