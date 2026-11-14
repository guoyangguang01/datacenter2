using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using SdnCustom.Protocols;
using SdnCustom.Protocols.Tcp;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class CustomTcpConnectionTests
{
    [Fact]
    public async Task ServerReceivesInputPushAndBroadcastsOutputPush()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var port = GetFreePort();
        var input = new PointDefinition
        {
            PointId = "P1",
            PointName = "Pressure",
            Address = string.Empty,
            DataType = PointDataType.Float64,
            Direction = PointDirection.Input
        };
        var output = new PointDefinition
        {
            PointId = "OUT-1",
            PointName = "Valve command",
            Address = string.Empty,
            DataType = PointDataType.Float64,
            Direction = PointDirection.Output
        };
        await using var connection = new CustomTcpServerDeviceConnection();
        await connection.ConnectAsync(new DeviceConnectionOptions
        {
            ClientCode = "CLIENT-01",
            Protocol = ClientProtocol.CustomTcp,
            Host = "127.0.0.1",
            Port = port,
            Points = [input, output]
        }, timeout.Token);

        using var external = new TcpClient();
        await external.ConnectAsync(IPAddress.Loopback, port, timeout.Token);
        var stream = external.GetStream();
        var inputBody = JsonSerializer.Serialize(new
        {
            values = new[]
            {
                new
                {
                    pointId = "P1",
                    value = 42.5,
                    quality = "Good",
                    timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                }
            }
        });
        await stream.WriteAsync(
            new TcpFrame(TcpCommand.ValuePush, inputBody).Encode(),
            timeout.Token);

        IReadOnlyList<PointSample> samples = [];
        while (samples.Count == 0 || Equals(samples[0].Value.Value, null))
        {
            await Task.Delay(20, timeout.Token);
            samples = await connection.ReadAsync("CLIENT-01", [input], timeout.Token);
        }
        Assert.Equal(42.5, samples[0].Value.Value);

        var result = Assert.Single(await connection.WriteBatchAsync(
            "CLIENT-01",
            [new DeviceWriteItem(output, 12.5, 123_000_000)],
            timeout.Token));
        Assert.True(result.Success);

        var outputFrame = await ReceiveFrameAsync(stream, timeout.Token);
        Assert.Equal(TcpCommand.ValuePush, outputFrame.Command);
        using var document = JsonDocument.Parse(outputFrame.Body);
        var value = Assert.Single(document.RootElement.GetProperty("values").EnumerateArray());
        Assert.Equal("OUT-1", value.GetProperty("pointId").GetString());
        Assert.Equal(12.5, value.GetProperty("value").GetDouble());
        Assert.Equal(
            ["pointId", "value", "quality", "timestamp"],
            value.EnumerateObject().Select(property => property.Name).ToArray());
    }

    [Fact]
    public async Task HeartbeatRequestGetsResponse()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var port = GetFreePort();
        await using var connection = new CustomTcpServerDeviceConnection();
        await connection.ConnectAsync(new DeviceConnectionOptions
        {
            ClientCode = "CLIENT-01",
            Protocol = ClientProtocol.CustomTcp,
            Host = "127.0.0.1",
            Port = port,
            Points = []
        }, timeout.Token);

        using var external = new TcpClient();
        await external.ConnectAsync(IPAddress.Loopback, port, timeout.Token);
        var stream = external.GetStream();
        await stream.WriteAsync(
            new TcpFrame(TcpCommand.HeartbeatRequest, string.Empty).Encode(),
            timeout.Token);
        var response = await ReceiveFrameAsync(stream, timeout.Token);
        Assert.Equal(TcpCommand.HeartbeatResponse, response.Command);
    }

    private static int GetFreePort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }

    private static async Task<TcpFrame> ReceiveFrameAsync(
        NetworkStream stream,
        CancellationToken cancellationToken)
    {
        var lengthBytes = new byte[4];
        await stream.ReadExactlyAsync(lengthBytes, cancellationToken);
        var length = BinaryPrimitives.ReadInt32BigEndian(lengthBytes);
        var payload = new byte[length];
        await stream.ReadExactlyAsync(payload, cancellationToken);
        return TcpFrame.Decode(payload);
    }
}
