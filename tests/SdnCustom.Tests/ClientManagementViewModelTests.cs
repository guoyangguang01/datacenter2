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
            SourceOutputPointId = "OUT"
        };
        var snapshot = new AdminSnapshot(
            [
                new AdminClientView(
                    "CLIENT-1",
                    "Client one",
                    true,
                    false,
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

        viewModel.SelectedPointFilter = viewModel.PointFilters[1];
        Assert.Single(viewModel.FilteredPoints);
        Assert.Equal("OUT", viewModel.FilteredPoints[0].PointId);

        viewModel.SelectedPointFilter = viewModel.PointFilters[2];
        Assert.Single(viewModel.FilteredPoints);
        Assert.Equal("IN", viewModel.FilteredPoints[0].PointId);
        Assert.Equal(2, viewModel.Points.Count);
    }

    private sealed class StubAdminFacade(AdminSnapshot snapshot) : IAdminFacade
    {
        public Task<AdminSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult(snapshot);

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
