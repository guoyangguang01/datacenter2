using Microsoft.EntityFrameworkCore;

namespace SdnCustom.Server.Persistence;

public sealed class ServerDbContext(DbContextOptions<ServerDbContext> options) : DbContext(options)
{
    public DbSet<ClientSystemEntity> ClientSystems => Set<ClientSystemEntity>();
    public DbSet<PointCatalogEntity> PointCatalog => Set<PointCatalogEntity>();
    public DbSet<LatestValueEntity> LatestValues => Set<LatestValueEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<ClientSystemEntity>(entity =>
        {
            entity.ToTable("client_system");
            entity.HasKey(item => item.ClientCode);
            entity.Property(item => item.ClientCode).HasMaxLength(64);
            entity.Property(item => item.ClientName).HasMaxLength(128);
            entity.HasMany(item => item.Points)
                .WithOne(item => item.ClientSystem)
                .HasForeignKey(item => item.ClientCode)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<PointCatalogEntity>(entity =>
        {
            entity.ToTable("point_catalog");
            entity.HasKey(item => new { item.ClientCode, item.PointId });
            entity.Property(item => item.ClientCode).HasMaxLength(64);
            entity.Property(item => item.PointId).HasMaxLength(128);
            entity.Property(item => item.PointName).HasMaxLength(256);
            entity.Property(item => item.Address).HasMaxLength(512);
        });

        modelBuilder.Entity<LatestValueEntity>(entity =>
        {
            entity.ToTable("latest_value");
            entity.HasKey(item => new { item.ClientCode, item.PointId });
            entity.Property(item => item.ClientCode).HasMaxLength(64);
            entity.Property(item => item.PointId).HasMaxLength(128);
        });
    }
}
