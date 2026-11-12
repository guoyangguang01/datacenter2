using System.Buffers.Binary;
using System.Text;

namespace SdnCustom.Protocols.Tcp;

public readonly record struct TcpFrame(byte Command, string Body)
{
    public byte[] Encode()
    {
        var body = Encoding.UTF8.GetBytes(Body ?? string.Empty);
        var payloadLength = checked(body.Length + 1);
        var frame = new byte[payloadLength + 4];
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(0, 4), payloadLength);
        frame[4] = Command;
        body.CopyTo(frame.AsSpan(5));
        return frame;
    }

    public static TcpFrame Decode(ReadOnlySpan<byte> commandAndBody)
    {
        if (commandAndBody.IsEmpty)
        {
            throw new InvalidDataException("TCP frame has no command byte.");
        }

        return new TcpFrame(
            commandAndBody[0],
            commandAndBody.Length > 1 ? Encoding.UTF8.GetString(commandAndBody[1..]) : string.Empty);
    }
}

public static class TcpCommand
{
    public const byte ReadRequest = 0x01;
    public const byte ReadResponse = 0x02;
    public const byte WriteRequest = 0x03;
    public const byte WriteResponse = 0x04;
    public const byte ValuePush = 0x05;
    public const byte HeartbeatRequest = 0x10;
    public const byte HeartbeatResponse = 0x11;
}
