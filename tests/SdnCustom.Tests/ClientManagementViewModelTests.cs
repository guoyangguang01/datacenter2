using System.Text;
using SdnCustom.Server.Admin;
using SdnCustom.Server.App.ViewModels;
using SdnCustom.Server.History;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class ClientManagementViewModelTests
{
    [Fact]
    public async Task FiltersPointsByDirectionAndKeepsFullCatalog()
    {
        var output = new PointDefinition
        {
            PointId = "OUT",
            PointName = "Output",
            Address = "1",
            Direction = PointDirection.Output
        };
        var input = new PointDefinition
        {
            PointId = "IN",
            PointName = "Input",
            Address = "2",
            Direction = PointDirection.Input,
            SourceInputPointId = null
        };
        var snapshot = new AdminSnapshot(
            [
                new AdminClientView(
                    "CLIENT-1",
                    "Client one",
                    true,
                    false,
                    ClientConnectionState.Connected,
                    ClientProtocol.CustomTcp,
                    "127.0.0.1:9002",
                    "1.0",
                    DateTimeOffset.UtcNow,
                    [output, input],
                    [])
            ],
            1,
            2);
        var viewModel = new ClientManagementViewModel(new StubAdminFacade(snapshot));

        await viewModel.InitializeAsync();

        Assert.Equal(2, viewModel.Points.Count);
        Assert.Equal(2, viewModel.FilteredPoints.Count);
        Assert.Equal(2, viewModel.PointFilters[0].Count);
        Assert.Equal(1, viewModel.PointFilters[1].Count);
        Assert.Equal(1, viewModel.PointFilters[2].Count);
        Assert.False(viewModel.CanEditPoints);

        viewModel.SelectedPointFilter = viewModel.PointFilters[1];
        Assert.Single(viewModel.FilteredPoints);
        Assert.Equal("IN", viewModel.FilteredPoints[0].PointId);

        viewModel.SelectedPointFilter = viewModel.PointFilters[2];
        Assert.Single(viewModel.FilteredPoints);
        Assert.Equal("OUT", viewModel.FilteredPoints[0].PointId);
        Assert.Equal(2, viewModel.Points.Count);
    }

    [Fact]
    public async Task InvalidCsvDoesNotReplaceCurrentPointCatalog()
    {
        var output = new PointDefinition
        {
            PointId = "OUT",
            PointName = "Output",
            Address = "1",
            Direction = PointDirection.Output
        };
        var snapshot = new AdminSnapshot(
            [
                new AdminClientView(
                    "CLIENT-1",
                    "Client one",
                    true,
                    false,
                    ClientConnectionState.Disconnected,
                    ClientProtocol.CustomTcp,
                    "127.0.0.1:9002",
                    "1.0",
                    DateTimeOffset.UtcNow,
                    [output],
                    [])
            ],
            1,
            1);
        var viewModel = new ClientManagementViewModel(new StubAdminFacade(snapshot));
        await viewModel.InitializeAsync();
        await using var stream = new MemoryStream(Encoding.UTF8.GetBytes(
            "pointId,pointName,dataType,direction,address,sourceInputPointId\n" +
            "BAD,Bad point,NotAType,输入,1\n"));

        await viewModel.ImportCsvAsync(stream);

        var remaining = Assert.Single(viewModel.Points);
        Assert.Equal("OUT", remaining.PointId);
        Assert.Contains("数据类型无效", viewModel.StatusText);
    }

    [Fact]
    public async Task ImportsGasTableDirectionAndDoubleType()
    {
        var snapshot = new AdminSnapshot(
            [
                new AdminClientView(
                    "CLIENT-1",
                    "Client one",
                    false,
                    false,
                    ClientConnectionState.Disconnected,
                    ClientProtocol.CustomTcp,
                    "127.0.0.1:9002",
                    "1.0",
                    DateTimeOffset.UtcNow,
                    [],
                    [])
            ],
            0,
            0);
        var viewModel = new ClientManagementViewModel(new StubAdminFacade(snapshot));
        await viewModel.InitializeAsync();
        await using var stream = new MemoryStream(Encoding.UTF8.GetBytes(
            "pointId,pointName,dataType,direction,address,sourceInputPointId\n" +
            "P1,Temperature,double,输入,40001,\n"));

        await viewModel.ImportCsvAsync(stream);

        var point = Assert.Single(viewModel.Points);
        Assert.Equal(PointDirection.Input, point.Direction);
        Assert.Equal(PointDataType.Float64, point.DataType);
    }

    [Fact]
    public async Task ImportAndExportPreserveQuotedMultilineFields()
    {
        var snapshot = new AdminSnapshot(
            [
                new AdminClientView(
                    "CLIENT-1",
                    "Client one",
                    true,
                    false,
                    ClientConnectionState.Disconnected,
                    ClientProtocol.CustomTcp,
                    "127.0.0.1:9002",
                    "1.0",
                    DateTimeOffset.UtcNow,
                    [],
                    [])
            ],
            1,
            0);
        var viewModel = new ClientManagementViewModel(new StubAdminFacade(snapshot));
        await viewModel.InitializeAsync();
        await using var import = new MemoryStream(Encoding.UTF8.GetBytes(
            "pointId,pointName,dataType,unit,direction,address,sourceInputPointId,deadband,group\n" +
            "P1,\"Name\nline\",Float64,/,输入,1,,0,Default\n"));

        await viewModel.ImportCsvAsync(import);

        var point = Assert.Single(viewModel.Points);
        Assert.Equal("Name\nline", point.PointName);

        await using var export = new MemoryStream();
        await viewModel.ExportCsvAsync(export, outputOnly: false);
        export.Position = 0;
        using var reader = new StreamReader(export, Encoding.UTF8, true, leaveOpen: true);
        var csv = await reader.ReadToEndAsync();
        Assert.Contains("\"Name\nline\"", csv);
        Assert.Contains(",输入,", csv);
    }

    private sealed class StubAdminFacade(AdminSnapshot snapshot) : IAdminFacade
    {
        public Task<AdminSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult(snapshot);

        public Task<AdminSystemSettingsView> GetSystemSettingsAsync(
            CancellationToken cancellationToken = default) =>
            Task.FromResult(new AdminSystemSettingsView(
                "/data",
                7443,
                7444,
                true,
                "http://127.0.0.1:8086",
                "sdncustom",
                "sdncustom"));

        public Task<IReadOnlyList<ServerLogEntry>> GetLogsAsync(
            int count = 500,
            CancellationToken cancellationToken = default) =>
            Task.FromResult<IReadOnlyList<ServerLogEntry>>([]);

        public Task CreateClientAsync(
            string clientCode,
            string clientName,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task RenameClientAsync(
            string clientCode,
            string clientName,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task DeleteClientAsync(string clientCode, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task StartClientAsync(string clientCode, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task StopClientAsync(string clientCode, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task SaveClientPointsAsync(
            string clientCode,
            IReadOnlyList<PointDefinition> points,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task WritePointAsync(
            string clientCode,
            string pointId,
            object? value,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
            string clientCode,
            string pointId,
            DateTimeOffset from,
            DateTimeOffset to,
            int limit,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
    }
}
