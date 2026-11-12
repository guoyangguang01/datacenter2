using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace SdnCustom.Server.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class InitialCreate : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "client_system",
                columns: table => new
                {
                    ClientCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    ClientName = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    Authorized = table.Column<bool>(type: "INTEGER", nullable: false),
                    DesiredRunning = table.Column<bool>(type: "INTEGER", nullable: false),
                    Protocol = table.Column<string>(type: "TEXT", nullable: false),
                    EndpointDisplay = table.Column<string>(type: "TEXT", nullable: false),
                    AppVersion = table.Column<string>(type: "TEXT", nullable: false),
                    CatalogRevision = table.Column<string>(type: "TEXT", nullable: false),
                    FirstSeen = table.Column<DateTimeOffset>(type: "TEXT", nullable: false),
                    LastSeen = table.Column<DateTimeOffset>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_client_system", x => x.ClientCode);
                });

            migrationBuilder.CreateTable(
                name: "latest_value",
                columns: table => new
                {
                    ClientCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    PointId = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    ValueJson = table.Column<string>(type: "TEXT", nullable: true),
                    Quality = table.Column<string>(type: "TEXT", nullable: false),
                    TimestampUnixNanoseconds = table.Column<long>(type: "INTEGER", nullable: false),
                    SourceClientCode = table.Column<string>(type: "TEXT", nullable: false),
                    SourcePointId = table.Column<string>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_latest_value", x => new { x.ClientCode, x.PointId });
                });

            migrationBuilder.CreateTable(
                name: "point_catalog",
                columns: table => new
                {
                    ClientCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    PointId = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    PointName = table.Column<string>(type: "TEXT", maxLength: 256, nullable: false),
                    Address = table.Column<string>(type: "TEXT", maxLength: 512, nullable: false),
                    DataType = table.Column<string>(type: "TEXT", nullable: false),
                    Unit = table.Column<string>(type: "TEXT", nullable: false),
                    Direction = table.Column<string>(type: "TEXT", nullable: false),
                    SourceOutputPointId = table.Column<string>(type: "TEXT", nullable: false),
                    Deadband = table.Column<double>(type: "REAL", nullable: false),
                    Group = table.Column<string>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_point_catalog", x => new { x.ClientCode, x.PointId });
                    table.ForeignKey(
                        name: "FK_point_catalog_client_system_ClientCode",
                        column: x => x.ClientCode,
                        principalTable: "client_system",
                        principalColumn: "ClientCode",
                        onDelete: ReferentialAction.Cascade);
                });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "latest_value");

            migrationBuilder.DropTable(
                name: "point_catalog");

            migrationBuilder.DropTable(
                name: "client_system");
        }
    }
}
