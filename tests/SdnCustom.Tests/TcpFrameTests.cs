using SdnCustom.Protocols.Tcp;

namespace SdnCustom.Tests;

public sealed class TcpFrameTests
{
    [Fact]
    public void EncodesBigEndianLengthAndJsonBody()
    {
        var encoded = new TcpFrame(TcpCommand.ReadRequest, """{"pointIds":["p1"]}""").Encode();
        var length = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(encoded.AsSpan(0, 4));
        var decoded = TcpFrame.Decode(encoded.AsSpan(4));

        Assert.Equal(encoded.Length - 4, length);
        Assert.Equal(TcpCommand.ReadRequest, decoded.Command);
        Assert.Equal("""{"pointIds":["p1"]}""", decoded.Body);
    }

    [Fact]
    public void RejectsEmptyCommandPayload()
    {
        Assert.Throws<InvalidDataException>(() => TcpFrame.Decode(ReadOnlySpan<byte>.Empty));
    }
}
