using System.Diagnostics;
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
            Protocol = ClientProtocol.OpcUa,
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
