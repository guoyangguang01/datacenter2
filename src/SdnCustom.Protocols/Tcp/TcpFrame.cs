using System.Buffers.Binary;

namespace SdnCustom.Protocols.Tcp;

public sealed class TcpFrame
{
    public const int HeaderLength = 64;
    public const int BodyLengthOffset = 8;
    public const int BodyCrcOffset = 56;
    public const int HeaderCrcOffset = 60;
    public const int DefaultMaximumBodyLength = 1024 * 1024;

    private TcpFrame(byte[] body)
    {
        Body = body;
    }

    public byte[] Body { get; }

    public static byte[] Encode(ReadOnlySpan<byte> body)
    {
        var frame = new byte[checked(HeaderLength + body.Length)];
        var header = frame.AsSpan(0, HeaderLength);
        BinaryPrimitives.WriteInt32LittleEndian(
            header[BodyLengthOffset..(BodyLengthOffset + sizeof(int))],
            body.Length);
        BinaryPrimitives.WriteUInt32LittleEndian(
            header[BodyCrcOffset..(BodyCrcOffset + sizeof(uint))],
            Crc64Jones.ComputeLow32(body));
        BinaryPrimitives.WriteUInt32LittleEndian(
            header[HeaderCrcOffset..(HeaderCrcOffset + sizeof(uint))],
            Crc64Jones.ComputeLow32(header[..HeaderCrcOffset]));
        body.CopyTo(frame.AsSpan(HeaderLength));
        return frame;
    }

    public static TcpFrame Decode(ReadOnlySpan<byte> frame)
    {
        if (frame.Length < HeaderLength)
        {
            throw new InvalidDataException(
                $"燃油系统 TCP 帧至少需要 {HeaderLength} 字节。");
        }

        var header = frame[..HeaderLength];
        var bodyLength = BinaryPrimitives.ReadInt32LittleEndian(
            header[BodyLengthOffset..(BodyLengthOffset + sizeof(int))]);
        if (bodyLength < 0 || frame.Length != HeaderLength + bodyLength)
        {
            throw new InvalidDataException(
                $"燃油系统 TCP 正文长度无效：头部={bodyLength}，实际={frame.Length - HeaderLength}。");
        }

        var body = frame[HeaderLength..].ToArray();
        ValidateChecksum(
            BinaryPrimitives.ReadUInt32LittleEndian(header[BodyCrcOffset..(BodyCrcOffset + sizeof(uint))]),
            Crc64Jones.ComputeLow32(body),
            "正文");
        ValidateChecksum(
            BinaryPrimitives.ReadUInt32LittleEndian(header[HeaderCrcOffset..(HeaderCrcOffset + sizeof(uint))]),
            Crc64Jones.ComputeLow32(header[..HeaderCrcOffset]),
            "头部");
        return new TcpFrame(body);
    }

    public static async ValueTask<TcpFrame> ReadAsync(
        Stream stream,
        int maximumBodyLength,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        if (maximumBodyLength <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maximumBodyLength));
        }

        var header = new byte[HeaderLength];
        await stream.ReadExactlyAsync(header, cancellationToken).ConfigureAwait(false);
        var bodyLength = BinaryPrimitives.ReadInt32LittleEndian(
            header.AsSpan(BodyLengthOffset, sizeof(int)));
        if (bodyLength < 0 || bodyLength > maximumBodyLength)
        {
            throw new InvalidDataException(
                $"燃油系统 TCP 正文长度 {bodyLength} 超出允许范围 0-{maximumBodyLength}。");
        }

        var body = new byte[bodyLength];
        if (bodyLength > 0)
        {
            await stream.ReadExactlyAsync(body, cancellationToken).ConfigureAwait(false);
        }

        ValidateChecksum(
            BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(BodyCrcOffset, sizeof(uint))),
            Crc64Jones.ComputeLow32(body),
            "正文");
        ValidateChecksum(
            BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(HeaderCrcOffset, sizeof(uint))),
            Crc64Jones.ComputeLow32(header.AsSpan(0, HeaderCrcOffset)),
            "头部");
        return new TcpFrame(body);
    }

    private static void ValidateChecksum(uint expected, uint actual, string section)
    {
        // 示例项目的解码器忽略校验值。为兼容只填长度的服务端，零值视为“未校验”。
        if (expected != 0 && expected != actual)
        {
            throw new InvalidDataException(
                $"燃油系统 TCP {section} CRC 不匹配：期望 0x{expected:X8}，实际 0x{actual:X8}。");
        }
    }
}
