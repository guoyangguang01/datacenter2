using System.Text.Json;
using System.Text.Json.Serialization;
using SdnCustom.Shared.Model;

namespace SdnCustom.Client.Core.Configuration;

public sealed class ClientProtocolJsonConverter : JsonConverter<ClientProtocol>
{
    public override ClientProtocol Read(
        ref Utf8JsonReader reader,
        Type typeToConvert,
        JsonSerializerOptions options)
    {
        if (reader.TokenType != JsonTokenType.String)
        {
            throw new JsonException("客户端协议必须是字符串。");
        }

        var value = reader.GetString();
        if (string.Equals(value, "CustomTcp", StringComparison.OrdinalIgnoreCase))
        {
            return ClientProtocol.Tcp;
        }

        return Enum.TryParse<ClientProtocol>(value, true, out var protocol)
            ? protocol
            : throw new JsonException($"不支持的客户端协议：{value}");
    }

    public override void Write(
        Utf8JsonWriter writer,
        ClientProtocol value,
        JsonSerializerOptions options) =>
        writer.WriteStringValue(value.ToString());
}
