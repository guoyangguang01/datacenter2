using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace SdnCustom.Server.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddPointsConfigured : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "PointsConfigured",
                table: "client_system",
                type: "INTEGER",
                nullable: false,
                defaultValue: false);
            migrationBuilder.Sql("UPDATE client_system SET PointsConfigured = 1;");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "PointsConfigured",
                table: "client_system");
        }
    }
}
