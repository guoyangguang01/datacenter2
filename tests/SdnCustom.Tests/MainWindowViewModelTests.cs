using SdnCustom.Server.Admin;
using SdnCustom.Server.App.ViewModels;
using SdnCustom.Server.History;
using SdnCustom.Server.Persistence;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class MainWindowViewModelTests
{
    [Fact]
    public async Task DiscardsHistoryResultWhenPointSelectionChangesDuringQuery()
    {
        var completion = new TaskCompletionSource<IReadOnlyList<HistoryPoint>>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var viewModel = new MainWindowViewModel(new StubAdminFacade(completion.Task));
        await viewModel.RefreshAsync();
        var originalPoint = viewModel.SelectedPoint;
        Assert.NotNull(originalPoint);

        var queryTask = viewModel.QueryHistoryCommand.ExecuteAsync(null);
        viewModel.SelectedPoint = viewModel.Points.Single(point => point.PointId == "P2");
        completion.SetResult([
            new HistoryPoint(DateTimeOffset.UtcNow, 42d, PointQuality.Good)
        ]);
        await queryTask;

        Assert.Empty(viewModel.History);
        Assert.Contains("旧结果已丢弃", viewModel.OperationMessage);
    }

    private sealed class StubAdminFacade(Task<IReadOnlyList<HistoryPoint>> history)
        : IAdminFacade
    {
        public Task<AdminSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default)
        {
            var points = new[]
            {
                new PointDefinition
                {
                    PointId = "P1",
                    PointName = "Point 1",
                    Address = "1"
                },
                new PointDefinition
                {
                    PointId = "P2",
                    PointName = "Point 2",
                    Address = "2"
                }
            };
            var client = new AdminClientView(
                "CLIENT-1",
                "Client one",
                true,
                true,
                ClientConnectionState.Connected,
                ClientProtocol.CustomTcp,
                "127.0.0.1:9002",
                "1.0",
                DateTimeOffset.UtcNow,
                points,
                []);
            return Task.FromResult(new AdminSnapshot([client], 1, points.Length));
        }

        public Task<AdminSystemSettingsView> GetSystemSettingsAsync(
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task<IReadOnlyList<ServerLogEntry>> GetLogsAsync(
            int count = 500,
            CancellationToken cancellationToken = default) =>
            Task.FromResult<IReadOnlyList<ServerLogEntry>>([]);

        public Task CreateClientAsync(
            string clientCode,
            string clientName,
            CancellationToken cancellationToken = default,
            string? systemCode = null) =>
            throw new NotSupportedException();

        public Task RenameClientAsync(
            string clientCode,
            string clientName,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task DeleteClientAsync(
            string clientCode,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task StartCommunicationAsync(CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task StopCommunicationAsync(CancellationToken cancellationToken = default) =>
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

        public Task<GasMasterImportResult> ImportGasMasterAsync(
            string systemCode,
            string systemName,
            string csv,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task<string> ExportGasMasterAsync(
            string systemCode,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task<IReadOnlyList<HistoryPoint>> QueryHistoryAsync(
            string clientCode,
            string pointId,
            PointDirection direction,
            DateTimeOffset from,
            DateTimeOffset to,
            int limit,
            CancellationToken cancellationToken = default) =>
            history;
    }
}
