using Microsoft.EntityFrameworkCore;

namespace SdnCustom.Server.Persistence;

public sealed class ServerDbContext(DbContextOptions<ServerDbContext> options) : DbContext(options)
{
    public DbSet<BusinessSystemEntity> BusinessSystems => Set<BusinessSystemEntity>();
    public DbSet<ClientSystemEntity> ClientSystems => Set<ClientSystemEntity>();
    public DbSet<PointCatalogEntity> PointCatalog => Set<PointCatalogEntity>();
    public DbSet<PointLinkEntity> PointLinks => Set<PointLinkEntity>();
    public DbSet<LatestValueEntity> LatestValues => Set<LatestValueEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<BusinessSystemEntity>(entity =>
        {
            entity.ToTable("business_system");
            entity.HasKey(item => item.SystemCode);
            entity.Property(item => item.SystemCode).HasMaxLength(64);
            entity.Property(item => item.SystemName).HasMaxLength(128);
            entity.Property(item => item.Description).HasMaxLength(512);
            entity.HasMany(item => item.Clients)
                .WithOne(item => item.BusinessSystem)
                .HasForeignKey(item => item.SystemCode)
                .OnDelete(DeleteBehavior.Cascade);
            entity.HasMany(item => item.Links)
                .WithOne(item => item.BusinessSystem)
                .HasForeignKey(item => item.SystemCode)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<ClientSystemEntity>(entity =>
        {
            entity.ToTable("client_system");
            entity.HasKey(item => item.ClientCode);
            entity.Property(item => item.ClientCode).HasMaxLength(64);
            entity.Property(item => item.ClientName).HasMaxLength(128);
            entity.Property(item => item.SystemCode).HasMaxLength(64);
            entity.HasMany(item => item.Points)
                .WithOne(item => item.ClientSystem)
                .HasForeignKey(item => item.ClientCode)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<PointCatalogEntity>(entity =>
        {
            entity.ToTable("point_catalog");
            entity.HasKey(item => new { item.ClientCode, item.PointId, item.Direction });
            entity.Property(item => item.ClientCode).HasMaxLength(64);
            entity.Property(item => item.PointId).HasMaxLength(128);
            entity.Property(item => item.PointName).HasMaxLength(256);
            entity.Property(item => item.Address).HasMaxLength(512);
        });

        modelBuilder.Entity<LatestValueEntity>(entity =>
        {
            entity.ToTable("latest_value");
            entity.HasKey(item => new { item.ClientCode, item.PointId, item.Direction });
            entity.Property(item => item.ClientCode).HasMaxLength(64);
            entity.Property(item => item.PointId).HasMaxLength(128);
        });

        modelBuilder.Entity<PointLinkEntity>(entity =>
        {
            entity.ToTable("point_link");
            entity.HasKey(item => item.LinkId);
            entity.Property(item => item.LinkId).HasMaxLength(64);
            entity.Property(item => item.SystemCode).HasMaxLength(64);
            entity.Property(item => item.Declaration).HasMaxLength(16);
            entity.Property(item => item.SourceClientCode).HasMaxLength(64);
            entity.Property(item => item.SourcePointId).HasMaxLength(128);
            entity.Property(item => item.SourcePointName).HasMaxLength(256);
            entity.Property(item => item.TargetClientCode).HasMaxLength(64);
            entity.Property(item => item.TargetPointId).HasMaxLength(128);
            entity.HasIndex(item => new { item.SystemCode, item.SourceClientCode, item.SourcePointId });
        });
    }
}
