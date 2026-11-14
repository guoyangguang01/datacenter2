using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace SdnCustom.Server.Migrations
{
    /// <inheritdoc />
    public partial class InitialCreate : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "business_system",
                columns: table => new
                {
                    SystemCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    SystemName = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    Description = table.Column<string>(type: "TEXT", maxLength: 512, nullable: false),
                    CreatedAt = table.Column<DateTimeOffset>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_business_system", x => x.SystemCode);
                });

            migrationBuilder.CreateTable(
                name: "latest_value",
                columns: table => new
                {
                    ClientCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    PointId = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    Direction = table.Column<string>(type: "TEXT", nullable: false),
                    ValueJson = table.Column<string>(type: "TEXT", nullable: true),
                    Quality = table.Column<string>(type: "TEXT", nullable: false),
                    TimestampUnixNanoseconds = table.Column<long>(type: "INTEGER", nullable: false),
                    SourceClientCode = table.Column<string>(type: "TEXT", nullable: false),
                    SourcePointId = table.Column<string>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_latest_value", x => new { x.ClientCode, x.PointId, x.Direction });
                });

            migrationBuilder.CreateTable(
                name: "client_system",
                columns: table => new
                {
                    ClientCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    ClientName = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    SystemCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    Authorized = table.Column<bool>(type: "INTEGER", nullable: false),
                    DesiredRunning = table.Column<bool>(type: "INTEGER", nullable: false),
                    PointsConfigured = table.Column<bool>(type: "INTEGER", nullable: false),
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
                    table.ForeignKey(
                        name: "FK_client_system_business_system_SystemCode",
                        column: x => x.SystemCode,
                        principalTable: "business_system",
                        principalColumn: "SystemCode",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "point_link",
                columns: table => new
                {
                    LinkId = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    SystemCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    Declaration = table.Column<string>(type: "TEXT", maxLength: 16, nullable: false),
                    SourceClientCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    SourcePointId = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    SourcePointName = table.Column<string>(type: "TEXT", maxLength: 256, nullable: false),
                    SourceProtocol = table.Column<string>(type: "TEXT", nullable: false),
                    TargetClientCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    TargetPointId = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    Enabled = table.Column<bool>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_point_link", x => x.LinkId);
                    table.ForeignKey(
                        name: "FK_point_link_business_system_SystemCode",
                        column: x => x.SystemCode,
                        principalTable: "business_system",
                        principalColumn: "SystemCode",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "point_catalog",
                columns: table => new
                {
                    ClientCode = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    PointId = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    Direction = table.Column<string>(type: "TEXT", nullable: false),
                    PointName = table.Column<string>(type: "TEXT", maxLength: 256, nullable: false),
                    Address = table.Column<string>(type: "TEXT", maxLength: 512, nullable: false),
                    Protocol = table.Column<string>(type: "TEXT", nullable: false),
                    DataType = table.Column<string>(type: "TEXT", nullable: false),
                    Unit = table.Column<string>(type: "TEXT", nullable: false),
                    OpcUaParameter1 = table.Column<string>(type: "TEXT", nullable: false),
                    OpcUaParameter2 = table.Column<string>(type: "TEXT", nullable: false),
                    OpcUaParameter3 = table.Column<string>(type: "TEXT", nullable: false),
                    Deadband = table.Column<double>(type: "REAL", nullable: false),
                    Group = table.Column<string>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_point_catalog", x => new { x.ClientCode, x.PointId, x.Direction });
                    table.ForeignKey(
                        name: "FK_point_catalog_client_system_ClientCode",
                        column: x => x.ClientCode,
                        principalTable: "client_system",
                        principalColumn: "ClientCode",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_client_system_SystemCode",
                table: "client_system",
                column: "SystemCode");

            migrationBuilder.CreateIndex(
                name: "IX_point_link_SystemCode_SourceClientCode_SourcePointId",
                table: "point_link",
                columns: new[] { "SystemCode", "SourceClientCode", "SourcePointId" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "latest_value");

            migrationBuilder.DropTable(
                name: "point_catalog");

            migrationBuilder.DropTable(
                name: "point_link");

            migrationBuilder.DropTable(
                name: "client_system");

            migrationBuilder.DropTable(
                name: "business_system");
        }
    }
}
