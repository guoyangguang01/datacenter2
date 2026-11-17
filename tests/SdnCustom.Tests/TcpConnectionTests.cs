using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;
using SdnCustom.Protocols;
using SdnCustom.Protocols.Tcp;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class TcpConnectionTests
{
    [Fact]
    public void FrameUsesLittleEndianBodyLengthAndCrc64JonesLow32()
    {
        var body = Encoding.ASCII.GetBytes("123456789");

        var frame = TcpFrame.Encode(body);

        Assert.Equal(
            body.Length,
            BinaryPrimitives.ReadInt32LittleEndian(
                frame.AsSpan(TcpFrame.BodyLengthOffset, sizeof(int))));
        Assert.Equal(
            0xC4B8D9CAU,
            BinaryPrimitives.ReadUInt32LittleEndian(
                frame.AsSpan(TcpFrame.BodyCrcOffset, sizeof(uint))));
        var decoded = TcpFrame.Decode(frame);
        Assert.Equal(body, decoded.Body);
    }

    [Fact]
    public void FrameRejectsCorruptedHeader()
    {
        var frame = TcpFrame.Encode([0x81, 0xA1, 0x78, 0x01]);
        frame[0] = 0x01;

        Assert.Throws<InvalidDataException>(() => TcpFrame.Decode(frame));
    }

    [Fact]
    public void MsgPackCodecRoundTripsDatasValues()
    {
        TcpValue[] source =
        [
            new("P1", 42.5),
            new("P2", true),
            new("P3", -12L),
            new("P4", "text"),
            new("P5", null)
        ];

        var body = TcpMessageCodec.EncodeValues(source);
        var decoded = TcpMessageCodec.DecodeValues(body);

        Assert.Equal(source.Length, decoded.Count);
        Assert.Equal("P1", decoded[0].PointId);
        Assert.Equal(42.5, decoded[0].Value);
        Assert.Equal(true, decoded[1].Value);
        Assert.Equal(-12L, decoded[2].Value);
        Assert.Equal("text", decoded[3].Value);
        Assert.Null(decoded[4].Value);
    }

    [Fact]
    public void FactoryMapsTcpProtocolToTcpServer()
    {
        var factory = new DeviceConnectionFactory();

        var connection = factory.Create(new DeviceConnectionOptions
        {
            Protocol = ClientProtocol.Tcp
        });

        Assert.IsType<TcpServerDeviceConnection>(connection);
    }

    [Fact]
    public async Task ExampleUserConnectsReceivesOutputAndPublishesInput()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var port = GetFreePort();
        var input = new PointDefinition
        {
            PointId = "SWXT00001-b",
            PointName = "Input",
            Address = string.Empty,
            DataType = PointDataType.Float64,
            Direction = PointDirection.Input
        };
        var output = new PointDefinition
        {
            PointId = "XSRH00001-d",
            PointName = "Output",
            Address = string.Empty,
            DataType = PointDataType.Float64,
            Direction = PointDirection.Output
        };
        await using var connection = new TcpServerDeviceConnection();

        await connection.ConnectAsync(new DeviceConnectionOptions
        {
            ClientCode = "ONLINE-01",
            Protocol = ClientProtocol.Tcp,
            Host = IPAddress.Loopback.ToString(),
            Port = port,
            TimeoutMilliseconds = 2000,
            Points = [input, output]
        }, timeout.Token);

        using var externalUser = new TcpClient();
        await externalUser.ConnectAsync(IPAddress.Loopback, port, timeout.Token);
        var stream = externalUser.GetStream();
        var inputBody = TcpMessageCodec.EncodeValues(
            [new TcpValue(input.PointId, 21.5)]);
        await stream.WriteAsync(
            TcpFrame.Encode(inputBody),
            timeout.Token);

        IReadOnlyList<PointSample> samples = [];
        while (samples.Count == 0 || !Equals(samples[0].Value.Value, 21.5))
        {
            await Task.Delay(20, timeout.Token);
            samples = await connection.ReadAsync("ONLINE-01", [input], timeout.Token);
        }

        Assert.Equal(21.5, samples[0].Value.Value);
        var writeResult = Assert.Single(await connection.WriteBatchAsync(
            "ONLINE-01",
            [new DeviceWriteItem(output, 8.25, 0)],
            timeout.Token));
        Assert.True(writeResult.Success);

        var outputFrame = await TcpFrame.ReadAsync(
            stream,
            TcpFrame.DefaultMaximumBodyLength,
            timeout.Token);
        var outputValue = Assert.Single(
            TcpMessageCodec.DecodeValues(outputFrame.Body));
        Assert.Equal(output.PointId, outputValue.PointId);
        Assert.Equal(8.25, outputValue.Value);
    }

    private static int GetFreePort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }
}
