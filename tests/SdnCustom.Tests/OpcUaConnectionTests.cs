using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using SdnCustom.Protocols;
using SdnCustom.Protocols.OpcUa;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class OpcUaConnectionTests
{
    [Fact]
    public async Task ConnectRunsBlockingNativeCallOffCallerThread()
    {
        using var bridge = new BlockingBridge();
        await using var connection = new OpcUaDeviceConnection(bridge);
        var releaseTask = Task.Run(async () =>
        {
            await Task.Delay(TimeSpan.FromSeconds(1));
            bridge.Release();
        });

        var stopwatch = Stopwatch.StartNew();
        var connectTask = connection.ConnectAsync(new DeviceConnectionOptions
        {
            Protocol = ClientProtocol.OpcUaClient,
            OpcUaEndpoint = "opc.tcp://127.0.0.1:4840"
        });
        stopwatch.Stop();

        Assert.True(
            stopwatch.Elapsed < TimeSpan.FromMilliseconds(500),
            $"ConnectAsync blocked for {stopwatch.Elapsed}.");
        Assert.False(connectTask.IsCompleted);
        await releaseTask;
        await connectTask;
        Assert.True(connection.IsConnected);
        Assert.Equal(0, bridge.DestroyCount);
    }

    [Fact]
    public async Task EmbeddedServerStartsAndMapsConfiguredPoints()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();

        var input = new PointDefinition
        {
            PointId = "SERVER-INPUT",
            PointName = "Server input",
            Address = "ns=1;s=server-input",
            DataType = PointDataType.Bool,
            Direction = PointDirection.Input
        };
        var output = new PointDefinition
        {
            PointId = "SERVER-OUTPUT",
            PointName = "Server output",
            Address = "ns=1;s=server-output",
            DataType = PointDataType.Float64,
            Direction = PointDirection.Output
        };
        await using var connection = new OpcUaServerDeviceConnection();
        await connection.ConnectAsync(new DeviceConnectionOptions
        {
            ClientCode = $"TEST-{Guid.NewGuid():N}",
            Protocol = ClientProtocol.OpcUaServer,
            OpcUaEndpoint = $"opc.tcp://127.0.0.1:{port}",
            Points = [input, output]
        }).WaitAsync(TimeSpan.FromSeconds(30));

        Assert.True(connection.IsConnected);
        var nodeManager = connection.GetType()
            .GetField("_nodeManager", System.Reflection.BindingFlags.Instance |
                                      System.Reflection.BindingFlags.NonPublic)
            ?.GetValue(connection);
        Assert.NotNull(nodeManager);
        var namespaceIndex = (ushort)(nodeManager!.GetType()
            .GetProperty("NamespaceIndex")
            ?.GetValue(nodeManager) ?? throw new InvalidOperationException());
        Assert.Equal(1, namespaceIndex);
        var read = Assert.Single(await connection.ReadAsync("TEST", [input]));
        Assert.Equal(PointQuality.Good, read.Value.Quality);
        Assert.Equal(false, read.Value.Value);

        var write = Assert.Single(await connection.WriteBatchAsync(
            "TEST",
            [new DeviceWriteItem(output, 12.5, 123)]));
        Assert.True(write.Success);
        await connection.DisconnectAsync();
        Assert.False(connection.IsConnected);
    }

    private sealed class BlockingBridge : IOpen62541NativeBridge, IDisposable
    {
        private readonly ManualResetEventSlim _release = new();
        private int _destroyCount;

        public int DestroyCount => Volatile.Read(ref _destroyCount);

        public nint Create() => 1;

        public int Connect(nint handle, string endpoint)
        {
            _release.Wait();
            return 0;
        }

        public int Disconnect(nint handle) => 0;

        public int Read(
            nint handle,
            string nodeId,
            int dataType,
            Span<byte> valueBuffer,
            out int valueLength,
            out long timestampUnixNanoseconds)
        {
            valueLength = 0;
            timestampUnixNanoseconds = 0;
            return 0;
        }

        public int Write(nint handle, string nodeId, int dataType, string value) => 0;

        public void Destroy(nint handle) => Interlocked.Increment(ref _destroyCount);

        public void Release() => _release.Set();

        public void Dispose() => _release.Dispose();
    }
}
