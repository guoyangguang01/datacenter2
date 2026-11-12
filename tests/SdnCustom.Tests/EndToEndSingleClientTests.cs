using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using SdnCustom.Client.Core.Configuration;
using SdnCustom.Client.Core.Runtime;
using SdnCustom.Server.Admin;
using SdnCustom.Server.Hosting;
using SdnCustom.Shared.Model;
using Microsoft.Extensions.DependencyInjection;

namespace SdnCustom.Tests;

public sealed class EndToEndSingleClientTests
{
    [Fact]
    public async Task DeviceStartFailureKeepsServerSessionAndReportsCommunicationError()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        var dataDirectory = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"));
        var configPath = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"), "client.json");
        var grpcPort = GetFreePort();
        var httpPort = GetFreePort();
        var unavailableDevicePort = GetFreePort();
        var app = ServerHost.Build([], settings =>
        {
            settings.DataDirectory = dataDirectory;
            settings.GrpcPort = grpcPort;
            settings.HttpPort = httpPort;
            settings.UseTls = false;
        });
        AgentSupervisor? supervisor = null;

        try
        {
            await ServerHost.InitializeAsync(app, timeout.Token);
            var admin = app.Services.GetRequiredService<IAdminFacade>();
            await admin.CreateClientAsync("FAILED-DEVICE", "Failed device", timeout.Token);
            await app.StartAsync(timeout.Token);

            var store = new ClientConfigStore(configPath);
            await store.SaveAsync(new ClientConfiguration
            {
                ClientCode = "FAILED-DEVICE",
                ClientName = "Failed device",
                Server = new ServerConnectionOptions
                {
                    Host = "127.0.0.1",
                    Port = grpcPort,
                    UseTls = false
                },
                Device = new DeviceOptions
                {
                    Protocol = ClientProtocol.CustomTcp,
                    Host = "127.0.0.1",
                    Port = unavailableDevicePort,
                    TimeoutMilliseconds = 500
                }
            }, timeout.Token);

            supervisor = new AgentSupervisor(store);
            await supervisor.StartAsync(timeout.Token);
            await WaitUntilAsync(
                async () => (await admin.GetSnapshotAsync(timeout.Token)).OnlineClients == 1,
                timeout.Token);

            await admin.StartClientAsync("FAILED-DEVICE", timeout.Token);
            await WaitUntilAsync(
                async () => (await admin.GetSnapshotAsync(timeout.Token))
                    .Clients.Single().CommunicationState == ClientConnectionState.Error,
                timeout.Token);

            var failed = (await admin.GetSnapshotAsync(timeout.Token)).Clients.Single();
            Assert.True(failed.Online);
            Assert.True(failed.DesiredRunning);
            Assert.Equal(ClientConnectionState.Error, failed.CommunicationState);

            await admin.StopClientAsync("FAILED-DEVICE", timeout.Token);
            await WaitUntilAsync(
                async () => (await admin.GetSnapshotAsync(timeout.Token))
                    .Clients.Single().CommunicationState == ClientConnectionState.Disconnected,
                timeout.Token);
        }
        finally
        {
            if (supervisor is not null)
            {
                await supervisor.DisposeAsync();
            }
            await app.StopAsync(CancellationToken.None);
            await app.DisposeAsync();
            TryDeleteDirectory(dataDirectory);
            TryDeleteDirectory(Path.GetDirectoryName(configPath));
        }
    }

    [Fact]
    public async Task ClientConnectsStartsTcpAcquisitionAndPublishesValues()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        var dataDirectory = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"));
        var configPath = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"), "client.json");
        var grpcPort = GetFreePort();
        var httpPort = GetFreePort();
        await using var deviceServer = new SingleValueTcpServer(21.5);
        await deviceServer.StartAsync(timeout.Token);
        var app = ServerHost.Build([], settings =>
        {
            settings.DataDirectory = dataDirectory;
            settings.GrpcPort = grpcPort;
            settings.HttpPort = httpPort;
            settings.UseTls = false;
        });
        AgentSupervisor? supervisor = null;

        try
        {
            await ServerHost.InitializeAsync(app, timeout.Token);
            var admin = app.Services.GetRequiredService<IAdminFacade>();
            await admin.CreateClientAsync("E2E-CLIENT", "End-to-end client", timeout.Token);
            await app.StartAsync(timeout.Token);

            var store = new ClientConfigStore(configPath);
            await store.SaveAsync(new ClientConfiguration
            {
                ClientCode = "E2E-CLIENT",
                ClientName = "End-to-end client",
                Server = new ServerConnectionOptions
                {
                    Host = "127.0.0.1",
                    Port = grpcPort,
                    UseTls = false
                },
                Device = new DeviceOptions
                {
                    Protocol = ClientProtocol.CustomTcp,
                    Host = "127.0.0.1",
                    Port = deviceServer.Port,
                    TimeoutMilliseconds = 2000
                },
                Points =
                [
                    new PointDefinition
                    {
                        PointId = "TEMP",
                        PointName = "Temperature",
                        Address = "40001",
                        DataType = PointDataType.Float64,
                        Deadband = 0
                    }
                ]
            }, timeout.Token);

            supervisor = new AgentSupervisor(store);
            await supervisor.StartAsync(timeout.Token);
            await WaitUntilAsync(
                async () => (await admin.GetSnapshotAsync(timeout.Token)).OnlineClients == 1,
                timeout.Token);
            await admin.SaveClientPointsAsync(
                "E2E-CLIENT",
                [
                    new PointDefinition
                    {
                        PointId = "TEMP",
                        PointName = "Temperature from server",
                        Address = "40001",
                        DataType = PointDataType.Float64,
                        Deadband = 0
                    }
                ],
                timeout.Token);
            await WaitUntilAsync(
                async () =>
                {
                    var updated = await store.LoadAsync(timeout.Token);
                    return updated.Points.Count == 1 &&
                           updated.Points[0].PointName == "Temperature from server";
                },
                timeout.Token);
            var invalidReference = await Assert.ThrowsAsync<InvalidOperationException>(() =>
                admin.SaveClientPointsAsync(
                    "E2E-CLIENT",
                    [
                        new PointDefinition
                        {
                            PointId = "TEMP",
                            PointName = "Input",
                            Address = "40001",
                            DataType = PointDataType.Float64,
                            Direction = PointDirection.Input
                        },
                        new PointDefinition
                        {
                            PointId = "IN-1",
                            PointName = "First output",
                            Address = "40002",
                            DataType = PointDataType.Float64,
                            Direction = PointDirection.Output,
                            SourceInputPointId = "IN-2"
                        },
                        new PointDefinition
                        {
                            PointId = "IN-2",
                            PointName = "Second output",
                            Address = "40003",
                            DataType = PointDataType.Float64,
                            Direction = PointDirection.Output,
                            SourceInputPointId = "TEMP"
                        }
                    ],
                    timeout.Token));
            Assert.Contains("必须引用 INPUT", invalidReference.Message);
            await admin.SaveClientPointsAsync(
                "E2E-CLIENT",
                [
                    new PointDefinition
                    {
                        PointId = " TEMP ",
                        PointName = " Temperature ",
                        Address = " 40001 ",
                        DataType = PointDataType.Float64,
                        Direction = PointDirection.Input
                    },
                    new PointDefinition
                    {
                        PointId = " IN ",
                        PointName = " Output ",
                        Address = " 40002 ",
                        DataType = PointDataType.Float64,
                        Direction = PointDirection.Output,
                        SourceInputPointId = " E2E-CLIENT: TEMP "
                    }
                ],
                timeout.Token);
            ClientConfiguration? normalized = null;
            await WaitUntilAsync(
                async () =>
                {
                    normalized = await store.LoadAsync(timeout.Token);
                    return normalized.Points.Any(point => point.Direction == PointDirection.Output);
                },
                timeout.Token);
            var normalizedOutput = normalized!.Points.Single(point => point.Direction == PointDirection.Output);
            Assert.Equal("IN", normalizedOutput.PointId);
            Assert.Equal("E2E-CLIENT:TEMP", normalizedOutput.SourceInputPointId);

            await admin.StartClientAsync("E2E-CLIENT", timeout.Token);
            await WaitUntilAsync(
                async () =>
                {
                    var snapshot = await admin.GetSnapshotAsync(timeout.Token);
                    return snapshot.Clients.Single().LatestValues
                        .Any(value => value.PointId == "TEMP" && Equals(value.Value, 21.5));
                },
                timeout.Token);
            await WaitUntilAsync(
                async () => (await admin.GetSnapshotAsync(timeout.Token))
                    .Clients.Single().CommunicationState == ClientConnectionState.Connected,
                timeout.Token);

            var blocked = await Assert.ThrowsAsync<InvalidOperationException>(() =>
                admin.SaveClientPointsAsync(
                    "E2E-CLIENT",
                    [
                        new PointDefinition
                        {
                            PointId = "TEMP",
                            PointName = "Blocked while running",
                            Address = "40001",
                            DataType = PointDataType.Float64
                        }
                    ],
                    timeout.Token));
            Assert.Contains("先停止通讯", blocked.Message);

            await admin.StopClientAsync("E2E-CLIENT", timeout.Token);
            await WaitUntilAsync(
                async () => (await admin.GetSnapshotAsync(timeout.Token))
                    .Clients.Single().CommunicationState == ClientConnectionState.Disconnected,
                timeout.Token);
            await admin.SaveClientPointsAsync(
                "E2E-CLIENT",
                [
                    new PointDefinition
                    {
                        PointId = "TEMP",
                        PointName = "Updated after stop",
                        Address = "40001",
                        DataType = PointDataType.Float64
                    }
                ],
                timeout.Token);
            await WaitUntilAsync(
                async () => (await store.LoadAsync(timeout.Token)).Points
                    .FirstOrDefault(point => point.PointId == "TEMP")?.PointName == "Updated after stop",
                timeout.Token);
        }
        finally
        {
            if (supervisor is not null)
            {
                await supervisor.DisposeAsync();
            }
            await app.StopAsync(CancellationToken.None);
            await app.DisposeAsync();
            TryDeleteDirectory(dataDirectory);
            TryDeleteDirectory(Path.GetDirectoryName(configPath));
        }
    }

    private static async Task WaitUntilAsync(Func<Task<bool>> condition, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            if (await condition())
            {
                return;
            }
            await Task.Delay(100, cancellationToken);
        }
    }

    private static int GetFreePort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }

    private static void TryDeleteDirectory(string? directory)
    {
        if (directory is null || !Directory.Exists(directory))
        {
            return;
        }
        try
        {
            Directory.Delete(directory, true);
        }
        catch (IOException)
        {
        }
    }

    private sealed class SingleValueTcpServer(double value) : IAsyncDisposable
    {
        private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
        private readonly CancellationTokenSource _cancellation = new();
        private Task? _acceptTask;

        public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _listener.Start();
            var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _cancellation.Token);
            _acceptTask = RunAsync(linked.Token);
            return Task.CompletedTask;
        }

        public async ValueTask DisposeAsync()
        {
            await _cancellation.CancelAsync();
            _listener.Stop();
            if (_acceptTask is not null)
            {
                try
                {
                    await _acceptTask;
                }
                catch (OperationCanceledException)
                {
                }
                catch (SocketException)
                {
                }
                catch (EndOfStreamException)
                {
                }
                catch (IOException)
                {
                }
            }
            _cancellation.Dispose();
        }

        private async Task RunAsync(CancellationToken cancellationToken)
        {
            using var client = await _listener.AcceptTcpClientAsync(cancellationToken);
            await using var stream = client.GetStream();
            var lengthBytes = new byte[4];
            while (!cancellationToken.IsCancellationRequested)
            {
                await stream.ReadExactlyAsync(lengthBytes, cancellationToken);
                var length = BinaryPrimitives.ReadInt32BigEndian(lengthBytes);
                var payload = new byte[length];
                await stream.ReadExactlyAsync(payload, cancellationToken);
                if (payload[0] != 0x01)
                {
                    continue;
                }

                var body = System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new
                {
                    values = new[]
                    {
                        new
                        {
                            pointId = "TEMP",
                            value,
                            quality = "GOOD",
                            timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                        }
                    }
                });
                var response = new byte[4 + 1 + body.Length];
                BinaryPrimitives.WriteInt32BigEndian(response.AsSpan(0, 4), body.Length + 1);
                response[4] = 0x02;
                body.CopyTo(response.AsSpan(5));
                await stream.WriteAsync(response, cancellationToken);
                await stream.FlushAsync(cancellationToken);
            }
        }
    }
}
