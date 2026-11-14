using System.Net;
using System.Net.Sockets;
using Grpc.Net.Client;
using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;
using SdnCustom.Contracts;
using SdnCustom.Contracts.Agent.V1;
using SdnCustom.Server.Hosting;
using SdnCustom.Shared.Model;
using SharedPointDataType = SdnCustom.Shared.Model.PointDataType;
using SharedPointDefinition = SdnCustom.Shared.Model.PointDefinition;

namespace SdnCustom.Tests;

public sealed class GrpcRegistrationTests
{
    [Fact]
    public async Task RegisteredClientReceivesWelcomeAndDuplicateIsRejected()
    {
        var dataDirectory = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"));
        var grpcPort = GetFreePort();
        var httpPort = GetFreePort();
        WebApplication? app = null;
        try
        {
            app = ServerHost.Build([], settings =>
            {
                settings.DataDirectory = dataDirectory;
                settings.GrpcPort = grpcPort;
                settings.HttpPort = httpPort;
                settings.UseTls = false;
            });
            await ServerHost.InitializeAsync(app);
            var admin = app.Services.GetRequiredService<SdnCustom.Server.Admin.IAdminFacade>();
            await admin.CreateClientAsync("CLIENT-01", "Test client");
            await app.StartAsync();
            var attachedAdmin = await SdnCustom.Server.Admin.RemoteAdminFacade.TryCreateAsync(httpPort);
            Assert.NotNull(attachedAdmin);
            Assert.Single((await attachedAdmin.GetSnapshotAsync()).Clients);

            using var channel = GrpcChannel.ForAddress($"http://127.0.0.1:{grpcPort}");
            var client = new AgentGateway.AgentGatewayClient(channel);
            using var directoryHttp = new HttpClient
            {
                BaseAddress = new Uri($"http://127.0.0.1:{httpPort}/")
            };
            using (var rejectedDirectory = await directoryHttp.GetAsync("api/clients"))
            {
                Assert.Equal(HttpStatusCode.Conflict, rejectedDirectory.StatusCode);
            }
            using (var rejected = client.Connect())
            {
                await rejected.RequestStream.WriteAsync(Hello("CLIENT-01"));
                Assert.True(await rejected.ResponseStream.MoveNext(CancellationToken.None));
                Assert.False(rejected.ResponseStream.Current.Welcome.Accepted);
                Assert.Contains("未启动", rejected.ResponseStream.Current.Welcome.Message);
                await rejected.RequestStream.CompleteAsync();
            }

            await admin.StartCommunicationAsync();
            using (var acceptedDirectory = await directoryHttp.GetAsync("api/clients"))
            {
                Assert.Equal(HttpStatusCode.OK, acceptedDirectory.StatusCode);
            }
            using var call = client.Connect();
            await call.RequestStream.WriteAsync(Hello("CLIENT-01"));
            Assert.True(await call.ResponseStream.MoveNext(CancellationToken.None));
            Assert.True(call.ResponseStream.Current.Welcome.Accepted);
            Assert.Empty(call.ResponseStream.Current.Welcome.ConfiguredPoints);

            using var duplicate = client.Connect();
            await duplicate.RequestStream.WriteAsync(Hello("CLIENT-01"));
            Assert.True(await duplicate.ResponseStream.MoveNext(CancellationToken.None));
            Assert.False(duplicate.ResponseStream.Current.Welcome.Accepted);
            await duplicate.RequestStream.CompleteAsync();
            await call.RequestStream.CompleteAsync();
        }
        finally
        {
            if (app is not null)
            {
                await app.StopAsync();
                await app.DisposeAsync();
            }
            if (Directory.Exists(dataDirectory))
            {
                try
                {
                    Directory.Delete(dataDirectory, true);
                }
                catch (IOException)
                {
                    // SQLite may release its native file handle slightly after host disposal.
                }
            }
        }
    }

    [Fact]
    public async Task RemoteAdminCanRenameAndDeleteOfflineClient()
    {
        var dataDirectory = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"));
        var grpcPort = GetFreePort();
        var httpPort = GetFreePort();
        WebApplication? app = null;
        try
        {
            app = ServerHost.Build([], settings =>
            {
                settings.DataDirectory = dataDirectory;
                settings.GrpcPort = grpcPort;
                settings.HttpPort = httpPort;
                settings.UseTls = false;
            });
            await ServerHost.InitializeAsync(app);
            var admin = app.Services.GetRequiredService<SdnCustom.Server.Admin.IAdminFacade>();
            await admin.CreateClientAsync("CLIENT-DELETE", "Original name");
            await app.StartAsync();

            var remote = await SdnCustom.Server.Admin.RemoteAdminFacade.TryCreateAsync(httpPort);
            Assert.NotNull(remote);
            await remote.RenameClientAsync("CLIENT-DELETE", "Renamed client");
            var renamed = Assert.Single((await remote.GetSnapshotAsync()).Clients);
            Assert.Equal("Renamed client", renamed.ClientName);

            await remote.DeleteClientAsync("CLIENT-DELETE");
            Assert.Empty((await remote.GetSnapshotAsync()).Clients);
        }
        finally
        {
            if (app is not null)
            {
                await app.StopAsync();
                await app.DisposeAsync();
            }
            if (Directory.Exists(dataDirectory))
            {
                try
                {
                    Directory.Delete(dataDirectory, true);
                }
                catch (IOException)
                {
                }
            }
        }
    }

    private static AgentMessage Hello(string clientCode)
    {
        var hello = new Hello
        {
            ProtocolVersion = "1",
            ClientName = "Test",
            InstanceId = Guid.NewGuid().ToString("N"),
            Protocol = DeviceProtocol.CustomTcp,
            EndpointDisplay = "127.0.0.1:9002"
        };
        hello.Points.Add(ContractMapper.ToContract(new SharedPointDefinition
        {
            PointId = "P1",
            PointName = "Point 1",
            Address = "40001",
            DataType = SharedPointDataType.Float64
        }));
        return new AgentMessage { ClientCode = clientCode, Hello = hello };
    }

    private static int GetFreePort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }
}
