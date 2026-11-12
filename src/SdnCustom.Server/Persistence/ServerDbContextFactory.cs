using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace SdnCustom.Server.Persistence;

public sealed class ServerDbContextFactory : IDesignTimeDbContextFactory<ServerDbContext>
{
    public ServerDbContext CreateDbContext(string[] args)
    {
        var databasePath = Environment.GetEnvironmentVariable("SDNCUSTOM_DB_PATH")
                           ?? Path.Combine("data", "sdncustom.db");
        var options = new DbContextOptionsBuilder<ServerDbContext>()
            .UseSqlite(new SqliteConnectionStringBuilder
            {
                DataSource = databasePath,
                DefaultTimeout = 30
            }.ToString())
            .Options;
        return new ServerDbContext(options);
    }
}
