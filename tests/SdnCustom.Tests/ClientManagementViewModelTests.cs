using System.Text;
using SdnCustom.Server.Admin;
using SdnCustom.Server.App.ViewModels;
using SdnCustom.Server.History;
using SdnCustom.Server.Persistence;
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
                    ClientProtocol.Tcp,
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
        Assert.Equal(
            "输出",
            viewModel.Points.Single(point => point.PointId == "IN").DirectionDisplay);
        Assert.Equal(
            "输入",
            viewModel.Points.Single(point => point.PointId == "OUT").DirectionDisplay);

        viewModel.SelectedPointFilter = viewModel.PointFilters[1];
        Assert.Single(viewModel.FilteredPoints);
        Assert.Equal("OUT", viewModel.FilteredPoints[0].PointId);

        viewModel.SelectedPointFilter = viewModel.PointFilters[2];
        Assert.Single(viewModel.FilteredPoints);
        Assert.Equal("IN", viewModel.FilteredPoints[0].PointId);
        Assert.Equal(2, viewModel.Points.Count);
    }

    [Fact]
    public async Task ExportsPlatformInputsAsUserOutputData()
    {
        var snapshot = new AdminSnapshot(
            [
                new AdminClientView(
                    "CLIENT-1",
                    "Client one",
                    false,
                    false,
                    ClientConnectionState.Disconnected,
                    ClientProtocol.Tcp,
                    "127.0.0.1:9002",
                    "1.0",
                    DateTimeOffset.UtcNow,
                    [
                        new PointDefinition
                        {
                            PointId = "USER-OUTPUT",
                            PointName = "User output",
                            Address = string.Empty,
                            Direction = PointDirection.Input
                        },
                        new PointDefinition
                        {
                            PointId = "USER-INPUT",
                            PointName = "User input",
                            Address = string.Empty,
                            Direction = PointDirection.Output
                        }
                    ],
                    [])
            ],
            0,
            2);
        var viewModel = new ClientManagementViewModel(new StubAdminFacade(snapshot));
        await viewModel.InitializeAsync();

        await using var export = new MemoryStream();
        await viewModel.ExportCsvAsync(export, outputOnly: true);
        export.Position = 0;
        using var reader = new StreamReader(export, Encoding.UTF8, true, leaveOpen: true);
        var csv = await reader.ReadToEndAsync();

        Assert.Contains("USER-OUTPUT", csv);
        Assert.DoesNotContain("USER-INPUT", csv);

        await using var inputExport = new MemoryStream();
        await viewModel.ExportCsvAsync(inputExport, PointDirection.Output);
        inputExport.Position = 0;
        using var inputReader = new StreamReader(inputExport, Encoding.UTF8, true, leaveOpen: true);
        var inputCsv = await inputReader.ReadToEndAsync();
        Assert.Contains("USER-INPUT", inputCsv);
        Assert.DoesNotContain("USER-OUTPUT", inputCsv);
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
                    ClientProtocol.Tcp,
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
                    ClientProtocol.Tcp,
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
                    ClientProtocol.Tcp,
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

    [Fact]
    public async Task CreatesClientInCurrentlySelectedSystem()
    {
        var snapshot = new AdminSnapshot(
            [],
            0,
            0,
            [
                new AdminSystemView("FUEL", "燃油系统", string.Empty, 0, 0, 0),
                new AdminSystemView("CHEM", "化学系统", string.Empty, 0, 0, 0)
            ]);
        var admin = new StubAdminFacade(snapshot);
        var viewModel = new ClientManagementViewModel(admin);
        await viewModel.InitializeAsync();

        viewModel.SelectedSystem = viewModel.Systems.Single(system => system.SystemCode == "CHEM");
        viewModel.NewClientCode = "CHEM01";
        viewModel.NewClientName = "化学客户端";
        viewModel.NewClientProtocolOption = viewModel.ProtocolOptions.Single(option =>
            option.Value == ClientProtocol.OpcUaClient);
        await viewModel.CreateClientCommand.ExecuteAsync(null);

        Assert.Equal("CHEM", admin.CreatedClientSystemCode);
        Assert.Equal(ClientProtocol.OpcUaClient, admin.CreatedClientProtocol);
        Assert.Contains("化学系统", viewModel.StatusText);
    }

    [Fact]
    public async Task SavesConfiguredProtocolForSelectedClient()
    {
        var client = new AdminClientView(
            "CLIENT-1",
            "Client one",
            false,
            false,
            ClientConnectionState.Disconnected,
            ClientProtocol.Tcp,
            "127.0.0.1:8120",
            "1.0",
            DateTimeOffset.UtcNow,
            [],
            [],
            "FUEL",
            "燃油系统");
        var snapshot = new AdminSnapshot(
            [client],
            0,
            0,
            [new AdminSystemView("FUEL", "燃油系统", string.Empty, 1, 0, 0)]);
        var admin = new StubAdminFacade(snapshot);
        var viewModel = new ClientManagementViewModel(admin);
        await viewModel.InitializeAsync();

        viewModel.SelectedClientProtocolOption = viewModel.ProtocolOptions.Single(option =>
            option.Value == ClientProtocol.OpcUaServer);
        await viewModel.SaveClientConfigurationCommand.ExecuteAsync(null);

        Assert.Equal("CLIENT-1", admin.UpdatedClientCode);
        Assert.Equal("Client one", admin.UpdatedClientName);
        Assert.Equal(ClientProtocol.OpcUaServer, admin.UpdatedClientProtocol);
    }

    [Fact]
    public async Task AddsAndSavesSinglePointForSelectedClient()
    {
        var client = new AdminClientView(
            "CLIENT-1",
            "Client one",
            false,
            false,
            ClientConnectionState.Disconnected,
            ClientProtocol.Tcp,
            "127.0.0.1:8120",
            "1.0",
            DateTimeOffset.UtcNow,
            [],
            [],
            "FUEL",
            "燃油系统");
        var snapshot = new AdminSnapshot(
            [client],
            0,
            0,
            [new AdminSystemView("FUEL", "燃油系统", string.Empty, 1, 0, 0)]);
        var admin = new StubAdminFacade(snapshot);
        var viewModel = new ClientManagementViewModel(admin);
        await viewModel.InitializeAsync();

        viewModel.AddPointCommand.Execute(null);
        var added = Assert.Single(viewModel.Points);
        added.PointId = "NEW-POINT";
        added.PointName = "单点新增";
        added.Address = "100";
        await viewModel.SavePointsCommand.ExecuteAsync(null);

        var saved = Assert.Single(admin.SavedPoints!);
        Assert.Equal("NEW-POINT", saved.PointId);
        Assert.Equal("单点新增", saved.PointName);
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
            CancellationToken cancellationToken = default,
            string? systemCode = null,
            ClientProtocol protocol = ClientProtocol.Tcp)
        {
            CreatedClientSystemCode = systemCode;
            CreatedClientProtocol = protocol;
            return Task.CompletedTask;
        }

        public string? CreatedClientSystemCode { get; private set; }
        public ClientProtocol? CreatedClientProtocol { get; private set; }
        public string? UpdatedClientCode { get; private set; }
        public string? UpdatedClientName { get; private set; }
        public ClientProtocol? UpdatedClientProtocol { get; private set; }
        public IReadOnlyList<PointDefinition>? SavedPoints { get; private set; }

        public Task UpdateClientAsync(
            string clientCode,
            string clientName,
            ClientProtocol protocol,
            CancellationToken cancellationToken = default)
        {
            UpdatedClientCode = clientCode;
            UpdatedClientName = clientName;
            UpdatedClientProtocol = protocol;
            return Task.CompletedTask;
        }

        public Task DeleteClientAsync(string clientCode, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task StartCommunicationAsync(CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task StopCommunicationAsync(CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task SaveClientPointsAsync(
            string clientCode,
            IReadOnlyList<PointDefinition> points,
            CancellationToken cancellationToken = default)
        {
            SavedPoints = points;
            return Task.CompletedTask;
        }

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
            throw new NotSupportedException();
    }
}
