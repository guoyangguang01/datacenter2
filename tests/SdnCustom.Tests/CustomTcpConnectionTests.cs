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
    public async Task ReadsAndWritesCurrentJsonProtocol()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var serverTask = Task.Run(async () =>
        {
            using var socket = await listener.AcceptTcpClientAsync(timeout.Token);
            await using var stream = socket.GetStream();
            var readRequest = await ReceiveFrameAsync(stream, timeout.Token);
            Assert.Equal(TcpCommand.ReadRequest, readRequest.Command);
            await SendFrameAsync(stream, new TcpFrame(
                TcpCommand.ReadResponse,
                """{"values":[{"pointId":"P1","value":12.5,"quality":"GOOD","timestamp":1700000000000}]}"""),
                timeout.Token);

            var writeRequest = await ReceiveFrameAsync(stream, timeout.Token);
            Assert.Equal(TcpCommand.WriteRequest, writeRequest.Command);
            using var writeJson = JsonDocument.Parse(writeRequest.Body);
            Assert.Equal("P1", writeJson.RootElement.GetProperty("pointId").GetString());
            await SendFrameAsync(stream, new TcpFrame(TcpCommand.WriteResponse, """{"success":true}"""), timeout.Token);
        }, timeout.Token);

        try
        {
            await using var connection = new CustomTcpDeviceConnection();
            await connection.ConnectAsync(new DeviceConnectionOptions
            {
                Protocol = ClientProtocol.CustomTcp,
                Host = "127.0.0.1",
                Port = port,
                TimeoutMilliseconds = 2000
            }, timeout.Token);

            var point = new PointDefinition
            {
                PointId = "P1",
                PointName = "Point 1",
                Address = "40001",
                DataType = PointDataType.Float64
            };
            var values = await connection.ReadAsync("CLIENT-01", [point], timeout.Token);
            await connection.WriteAsync("CLIENT-01", point, 8.5, timeout.Token);

            var value = Assert.Single(values);
            Assert.Equal(12.5, Assert.IsType<double>(value.Value.Value));
            Assert.Equal(1_700_000_000_000_000_000L, value.Value.TimestampUnixNanoseconds);
            await serverTask;
        }
        finally
        {
            listener.Stop();
        }
    }

    private static async Task<TcpFrame> ReceiveFrameAsync(NetworkStream stream, CancellationToken cancellationToken)
    {
        var lengthBytes = new byte[4];
        await stream.ReadExactlyAsync(lengthBytes, cancellationToken);
        var length = BinaryPrimitives.ReadInt32BigEndian(lengthBytes);
        var payload = new byte[length];
        await stream.ReadExactlyAsync(payload, cancellationToken);
        return TcpFrame.Decode(payload);
    }

    private static async Task SendFrameAsync(NetworkStream stream, TcpFrame frame, CancellationToken cancellationToken)
    {
        await stream.WriteAsync(frame.Encode(), cancellationToken);
        await stream.FlushAsync(cancellationToken);
    }
}
