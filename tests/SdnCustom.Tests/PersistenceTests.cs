using Microsoft.EntityFrameworkCore;
using SdnCustom.Server.Persistence;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class PersistenceTests
{
    [Fact]
    public async Task PersistsClientRegistrationDesiredStateAndLatestValues()
    {
        var directory = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var options = new DbContextOptionsBuilder<ServerDbContext>()
                .UseSqlite($"Data Source={Path.Combine(directory, "config.db")}")
                .Options;
            var factory = new TestDbContextFactory(options);
            var clients = new ClientSystemStore(factory);
            await clients.InitializeAsync();
            await clients.CreateAuthorizedAsync("CLIENT-1", "Client one");
            var declaredRegistration = new ClientRegistration(
                "CLIENT-1",
                "Client one",
                ClientProtocol.OpcUa,
                "opc.tcp://127.0.0.1:4840",
                "1.0",
                "revision-1",
                [
                    new PointDefinition
                    {
                        PointId = "P1",
                        PointName = "Point one",
                        Address = "ns=2;s=P1",
                        DataType = PointDataType.Float64
                    }
                ]);
            var registration = await clients.RegisterAsync(declaredRegistration);
            await clients.SetDesiredRunningAsync("CLIENT-1", true);
            await clients.SavePointsAsync("CLIENT-1",
            [
                new PointDefinition
                {
                    PointId = "P2",
                    PointName = "Server managed point",
                    Address = "ns=2;s=P2",
                    DataType = PointDataType.Int32
                }
            ]);
            await clients.SavePointsAsync("CLIENT-1",
            [
                new PointDefinition
                {
                    PointId = "P2",
                    PointName = "Server managed point updated",
                    Address = "ns=2;s=P2",
                    DataType = PointDataType.Int32
                }
            ]);
            var stored = await clients.GetAsync("CLIENT-1");

            Assert.Equal(ClientProtocol.OpcUa, registration.Protocol);
            Assert.True(stored!.DesiredRunning);
            var storedPoint = Assert.Single(stored.Points);
            Assert.Equal("P2", storedPoint.PointId);
            Assert.Equal("Server managed point updated", storedPoint.PointName);

            var latest = new LatestValueStore(factory);
            await latest.InitializeAsync();
            await latest.UpdateAsync([
                new PointSample(
                    "CLIENT-1",
                    "P1",
                    new PointValue(15.25, PointQuality.Good, 123))
            ]);
            var reloadedLatest = new LatestValueStore(factory);
            await reloadedLatest.InitializeAsync();
            var reloadedValue = Assert.Single(
                reloadedLatest.GetClient("CLIENT-1"),
                value => value.PointId == "P1").Value.Value;
            Assert.IsType<double>(reloadedValue);
            Assert.Equal(15.25, reloadedValue);

            await latest.MarkOutputsCommLostAsync("CLIENT-1", stored.Points);
            var current = latest.GetClient("CLIENT-1");
            Assert.Contains(current, value => value.PointId == "P1");
            var p2 = Assert.Single(current, value => value.PointId == "P2");
            Assert.Equal(PointQuality.CommLost, p2.Value.Quality);
            await latest.RemovePointsAsync("CLIENT-1", ["P1"]);
            Assert.DoesNotContain(latest.GetClient("CLIENT-1"), value => value.PointId == "P1");

            var renamed = await clients.RenameAsync("CLIENT-1", "Renamed client");
            Assert.Equal("Renamed client", renamed.ClientName);
            Assert.Equal("Renamed client", (await clients.GetAsync("CLIENT-1"))!.ClientName);
            await clients.RegisterAsync(declaredRegistration);
            Assert.Equal("Renamed client", (await clients.GetAsync("CLIENT-1"))!.ClientName);

            await clients.DeleteAsync("CLIENT-1");
            Assert.Null(await clients.GetAsync("CLIENT-1"));
        }
        finally
        {
            try
            {
                Directory.Delete(directory, true);
            }
            catch (IOException)
            {
            }
        }
    }

    private sealed class TestDbContextFactory(DbContextOptions<ServerDbContext> options)
        : IDbContextFactory<ServerDbContext>
    {
        public ServerDbContext CreateDbContext() => new(options);

        public Task<ServerDbContext> CreateDbContextAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult(CreateDbContext());
    }
}
