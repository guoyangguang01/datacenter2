using System.Net;
using System.Net.Sockets;
using SdnCustom.Client.Core.Configuration;
using SdnCustom.Client.Core.Runtime;
using SdnCustom.Protocols.Tcp;
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
        using var occupiedDevicePort = new TcpListener(IPAddress.Loopback, 0);
        occupiedDevicePort.Start();
        var unavailableDevicePort = ((IPEndPoint)occupiedDevicePort.LocalEndpoint).Port;
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
            await admin.StartCommunicationAsync(timeout.Token);

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
                    Protocol = ClientProtocol.Tcp,
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

            await Assert.ThrowsAnyAsync<Exception>(
                () => supervisor.StartCommunicationAsync(timeout.Token));
            await WaitUntilAsync(
                async () => (await admin.GetSnapshotAsync(timeout.Token))
                    .Clients.Single().CommunicationState == ClientConnectionState.Error,
                timeout.Token);

            var failed = (await admin.GetSnapshotAsync(timeout.Token)).Clients.Single();
            Assert.True(failed.Online);
            Assert.False(failed.DesiredRunning);
            Assert.Equal(ClientConnectionState.Error, failed.CommunicationState);

            await admin.StopCommunicationAsync(timeout.Token);
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
        var deviceServerPort = GetFreePort();
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
            await admin.StartCommunicationAsync(timeout.Token);

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
                    Protocol = ClientProtocol.Tcp,
                    Host = "127.0.0.1",
                    Port = deviceServerPort,
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
            var invalidOutput = await Assert.ThrowsAsync<InvalidOperationException>(() =>
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
                            SourceInputPointId = "TEMP"
                        }
                    ],
                    timeout.Token));
            Assert.Contains("PointLink", invalidOutput.Message);
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
                    }
                ],
                timeout.Token);
            ClientConfiguration? normalized = null;
            await WaitUntilAsync(
                async () =>
                {
                    normalized = await store.LoadAsync(timeout.Token);
                    return normalized.Points.Count == 1 &&
                           normalized.Points[0].PointId == "TEMP";
                },
                timeout.Token);
            Assert.Equal(PointDirection.Input, normalized!.Points[0].Direction);

            await supervisor.StartCommunicationAsync(timeout.Token);
            using var externalTcp = new TcpClient();
            await externalTcp.ConnectAsync(IPAddress.Loopback, deviceServerPort, timeout.Token);
            var externalTcpStream = externalTcp.GetStream();
            var inputBody = TcpMessageCodec.EncodeValues(
                [new TcpValue("TEMP", 21.5)]);
            await externalTcpStream.WriteAsync(
                TcpFrame.Encode(inputBody),
                timeout.Token);
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

            await admin.StopCommunicationAsync(timeout.Token);
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
                async () => (await admin.GetSnapshotAsync(timeout.Token)).Clients.Single().Points
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

}
