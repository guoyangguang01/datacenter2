using Microsoft.EntityFrameworkCore;
using SdnCustom.Server.Persistence;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class PersistenceTests
{
    [Fact]
    public async Task SeedsFuelSystemSampleFromEmbeddedCsv()
    {
        var directory = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var options = new DbContextOptionsBuilder<ServerDbContext>()
                .UseSqlite($"Data Source={Path.Combine(directory, "sample.db")}")
                .Options;
            var factory = new TestDbContextFactory(options);
            var clients = new ClientSystemStore(factory);
            await clients.InitializeAsync();

            var gasMaster = await CreateGasMasterServiceAsync(factory);
            var seeder = new FuelSystemSampleSeeder(factory, gasMaster);
            await seeder.SeedAsync();
            await seeder.SeedAsync();

            await using var context = await factory.CreateDbContextAsync();
            var system = await context.BusinessSystems.SingleAsync();
            Assert.Equal("FUEL", system.SystemCode);
            Assert.Equal("燃油系统", system.SystemName);
            Assert.Equal(5, await context.ClientSystems.CountAsync());
            Assert.Equal(392, await context.PointCatalog.CountAsync());
            Assert.Equal(
                200,
                await context.PointCatalog.CountAsync(point => point.Direction == "Input"));
            Assert.Equal(
                192,
                await context.PointCatalog.CountAsync(point => point.Direction == "Output"));
            Assert.Equal(732, await context.PointLinks.CountAsync());
            Assert.Equal(
                366,
                await context.PointLinks.CountAsync(link => link.Declaration == "Output"));
            Assert.Equal(
                175,
                await context.PointLinks.CountAsync(link =>
                    link.Declaration == "Output" && link.Enabled));
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

    [Fact]
    public async Task ExportsAndReimportsRawGasMasterTable()
    {
        var directory = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var options = new DbContextOptionsBuilder<ServerDbContext>()
                .UseSqlite($"Data Source={Path.Combine(directory, "roundtrip.db")}")
                .Options;
            var factory = new TestDbContextFactory(options);
            var clients = new ClientSystemStore(factory);
            await clients.InitializeAsync();
            var service = await CreateGasMasterServiceAsync(factory);
            await new FuelSystemSampleSeeder(factory, service).SeedAsync();

            var csv = await service.ExportAsync("FUEL");
            var lines = csv.Split(
                ["\r\n", "\n"],
                StringSplitOptions.RemoveEmptyEntries);
            Assert.Equal(733, lines.Length);
            Assert.Contains("测点id,测点名称", lines[0]);

            var result = await service.ImportAsync("FUEL", "燃油系统", csv);

            Assert.Equal(5, result.ClientCount);
            Assert.Equal(200, result.InputCount);
            Assert.Equal(192, result.OutputCount);
            Assert.Equal(366, result.RouteCount);
            Assert.Contains(result.Warnings, warning => warning.Contains("数字型 OPC UA"));
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
                ClientProtocol.OpcUaClient,
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

            Assert.Equal(ClientProtocol.OpcUaClient, registration.Protocol);
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

            await latest.MarkInputsCommLostAsync("CLIENT-1", stored.Points);
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

    private static async Task<GasMasterTableService> CreateGasMasterServiceAsync(
        IDbContextFactory<ServerDbContext> factory)
    {
        var latest = new LatestValueStore(factory);
        await latest.InitializeAsync();
        return new GasMasterTableService(
            factory,
            new AgentRegistry(),
            latest,
            new PointLinkCatalog(new PointRouter(), factory));
    }

    private sealed class TestDbContextFactory(DbContextOptions<ServerDbContext> options)
        : IDbContextFactory<ServerDbContext>
    {
        public ServerDbContext CreateDbContext() => new(options);

        public Task<ServerDbContext> CreateDbContextAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult(CreateDbContext());
    }
}
