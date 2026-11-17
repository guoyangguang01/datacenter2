using Microsoft.EntityFrameworkCore;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Persistence;

public sealed class FuelSystemSampleSeeder(
    IDbContextFactory<ServerDbContext> contextFactory,
    IGasMasterTableService gasMasterTable)
{
    private const string ResourceName = "SdnCustom.Server.SeedData.fuel-system.csv";

    public async Task SeedAsync(CancellationToken cancellationToken = default)
    {
        await using var context = await contextFactory.CreateDbContextAsync(cancellationToken)
            .ConfigureAwait(false);
        if (await context.BusinessSystems.AnyAsync(cancellationToken).ConfigureAwait(false))
        {
            return;
        }

        var now = DateTimeOffset.UtcNow;
        context.BusinessSystems.Add(new BusinessSystemEntity
        {
            SystemCode = "FUEL",
            SystemName = "燃油系统",
            Description = "根据内嵌原始燃气总表生成的样例系统。",
            CreatedAt = now
        });
        foreach (var (code, name, protocol) in SampleClients)
        {
            context.ClientSystems.Add(new ClientSystemEntity
            {
                ClientCode = code,
                ClientName = name,
                SystemCode = "FUEL",
                Authorized = true,
                DesiredRunning = false,
                PointsConfigured = true,
                Protocol = protocol.ToString(),
                FirstSeen = now,
                LastSeen = now
            });
        }

        await context.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
        await gasMasterTable.ImportAsync(
            "FUEL",
            "燃油系统",
            await ReadSampleCsvAsync(cancellationToken).ConfigureAwait(false),
            cancellationToken).ConfigureAwait(false);
    }

    private static async Task<string> ReadSampleCsvAsync(CancellationToken cancellationToken)
    {
        await using var stream = typeof(FuelSystemSampleSeeder).Assembly
            .GetManifestResourceStream(ResourceName)
            ?? throw new InvalidOperationException($"Embedded resource not found: {ResourceName}");
        using var reader = new StreamReader(stream);
        return await reader.ReadToEndAsync(cancellationToken).ConfigureAwait(false);
    }

    private static readonly (string Code, string Name, ClientProtocol Protocol)[] SampleClients =
    [
        ("FZXT", "仿真系统", ClientProtocol.Tcp),
        ("SWGZ", "三维感知", ClientProtocol.Tcp),
        ("SWSC", "实物系统（数采）", ClientProtocol.Tcp),
        ("SWXT", "实物系统（PLC）", ClientProtocol.Tcp),
        ("XNCZ", "虚拟操作", ClientProtocol.OpcUaClient)
    ];
}
