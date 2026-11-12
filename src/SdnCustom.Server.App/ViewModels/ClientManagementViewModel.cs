using System.Collections.ObjectModel;
using System.Globalization;
using System.Text;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SdnCustom.Server.Admin;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.App.ViewModels;

public sealed partial class ClientManagementViewModel : ObservableObject
{
    private readonly IAdminFacade _admin;
    private readonly SemaphoreSlim _runStateRefreshGate = new(1, 1);

    [ObservableProperty] private ClientOption? _selectedClient;
    [ObservableProperty] private PointFilterOption? _selectedPointFilter;
    [ObservableProperty] private string _selectedClientName = string.Empty;
    [ObservableProperty] private EditablePointRow? _selectedPoint;
    [ObservableProperty] private string _newClientCode = string.Empty;
    [ObservableProperty] private string _newClientName = string.Empty;
    [ObservableProperty] private string _statusText = string.Empty;
    [ObservableProperty] private bool _canEditPoints = true;
    [ObservableProperty] private bool _isPointsReadOnly;
    [ObservableProperty] private bool _canManageClient;

    public ObservableCollection<ClientOption> Clients { get; } = [];
    public ObservableCollection<EditablePointRow> Points { get; } = [];
    public ObservableCollection<EditablePointRow> FilteredPoints { get; } = [];
    public IReadOnlyList<PointFilterOption> PointFilters { get; } =
    [
        new("全部", null),
        new("输出测点", PointDirection.Output),
        new("输入测点", PointDirection.Input)
    ];
    public IReadOnlyList<PointDataType> DataTypeOptions { get; } = Enum.GetValues<PointDataType>();
    public IReadOnlyList<PointDirection> DirectionOptions { get; } = Enum.GetValues<PointDirection>();
    public string ClientCountText => $"{Clients.Count} 个客户端";
    public bool HasNoClients => Clients.Count == 0;
    public string SelectedClientStateText
    {
        get
        {
            if (SelectedClient is null)
            {
                return "尚未选择客户端";
            }

            var connection = SelectedClient.Online ? "在线" : "离线";
            return $"{connection} · {SelectedClient.CommunicationText} · {Points.Count} 个测点";
        }
    }
    public bool ShowPoints => SelectedClient is not null && FilteredPoints.Count > 0;
    public bool ShowNoClientState => SelectedClient is null;
    public bool ShowNoPointsState => SelectedClient is not null && Points.Count == 0;
    public bool ShowNoFilteredPoints =>
        SelectedClient is not null && Points.Count > 0 && FilteredPoints.Count == 0;

    public ClientManagementViewModel(IAdminFacade admin)
    {
        _admin = admin;
        SelectedPointFilter = PointFilters[0];
    }

    partial void OnSelectedClientChanged(ClientOption? value)
    {
        SelectedClientName = value?.ClientName ?? string.Empty;
        CanManageClient = value is not null;
        LoadPoints();
        NotifyLayoutState();
        RenameClientCommand.NotifyCanExecuteChanged();
    }

    partial void OnSelectedClientNameChanged(string value) => RenameClientCommand.NotifyCanExecuteChanged();
    partial void OnNewClientCodeChanged(string value) => CreateClientCommand.NotifyCanExecuteChanged();
    partial void OnNewClientNameChanged(string value) => CreateClientCommand.NotifyCanExecuteChanged();
    partial void OnSelectedPointFilterChanged(PointFilterOption? value) => RefreshFilteredPoints();

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        var selectedCode = SelectedClient?.ClientCode;
        var snapshot = await _admin.GetSnapshotAsync(cancellationToken);
        Clients.Clear();
        foreach (var client in snapshot.Clients)
        {
            Clients.Add(new ClientOption(
                client.ClientCode,
                client.ClientName,
                client.Online,
                client.DesiredRunning,
                client.CommunicationState,
                client.Points));
        }
        SelectedClient = Clients.FirstOrDefault(client => client.ClientCode == selectedCode)
                         ?? Clients.FirstOrDefault();
        StatusText = $"已加载 {Clients.Count} 个客户端。";
        NotifyLayoutState();
    }

    [RelayCommand]
    private async Task RefreshAsync()
    {
        await InitializeAsync();
        StatusText = $"已刷新，共 {Clients.Count} 个客户端。";
    }

    public async Task RefreshRunStateAsync(CancellationToken cancellationToken = default)
    {
        if (!await _runStateRefreshGate.WaitAsync(0, cancellationToken))
        {
            return;
        }

        try
        {
            var snapshot = await _admin.GetSnapshotAsync(cancellationToken);
            foreach (var client in Clients)
            {
                var current = snapshot.Clients.FirstOrDefault(item => item.ClientCode == client.ClientCode);
                if (current is null)
                {
                    continue;
                }
                client.Online = current.Online;
                client.DesiredRunning = current.DesiredRunning;
                client.CommunicationState = current.CommunicationState;
            }

            if (SelectedClient is not null)
            {
                CanEditPoints = CanEditSelectedClientPoints();
                IsPointsReadOnly = !CanEditPoints;
            }
            OnPropertyChanged(nameof(SelectedClientStateText));
        }
        finally
        {
            _runStateRefreshGate.Release();
        }
    }

    [RelayCommand]
    private void AddPoint()
    {
        if (!CanEditPoints)
        {
            StatusText = "客户端正在运行通讯，请先停止通讯后修改点表。";
            return;
        }

        var index = 1;
        string pointId;
        do
        {
            pointId = $"POINT-{index++:D4}";
        }
        while (Points.Any(point => point.PointId == pointId));

        var row = new EditablePointRow
        {
            PointId = pointId,
            PointName = pointId,
            Address = "0",
            DataType = PointDataType.Float64,
            Direction = PointDirection.Output,
            Unit = "/",
            Group = "默认"
        };
        Points.Add(row);
        SelectedPointFilter = PointFilters.First(filter => filter.Direction == row.Direction);
        RefreshFilteredPoints();
        SelectedPoint = FilteredPoints.FirstOrDefault(point => point.PointId == row.PointId);
        NotifyLayoutState();
    }

    [RelayCommand]
    private void RemovePoint()
    {
        if (!CanEditPoints)
        {
            StatusText = "客户端正在运行通讯，请先停止通讯后修改点表。";
            return;
        }

        if (SelectedPoint is not null)
        {
            Points.Remove(SelectedPoint);
            RefreshFilteredPoints();
        }
    }

    [RelayCommand]
    private async Task SavePointsAsync()
    {
        if (SelectedClient is null)
        {
            StatusText = "请先选择客户端系统。";
            return;
        }

        try
        {
            if (!CanEditPoints)
            {
                StatusText = "客户端正在运行通讯，请先停止通讯后修改点表。";
                return;
            }

            await _admin.SaveClientPointsAsync(
                SelectedClient.ClientCode,
                Points.Select(point => point.ToDefinition()).ToArray());
            await InitializeAsync();
            StatusText = $"已保存并下发 {Points.Count} 个测点。";
        }
        catch (Exception exception)
        {
            StatusText = $"保存失败：{exception.Message}";
        }
    }

    [RelayCommand(CanExecute = nameof(CanCreateClient))]
    private async Task CreateClientAsync()
    {
        try
        {
            await _admin.CreateClientAsync(NewClientCode, NewClientName);
            var code = NewClientCode.Trim();
            NewClientCode = string.Empty;
            NewClientName = string.Empty;
            await InitializeAsync();
            SelectedClient = Clients.FirstOrDefault(client => client.ClientCode == code);
            StatusText = $"已创建客户端 {code}。";
        }
        catch (Exception exception)
        {
            StatusText = $"创建失败：{exception.Message}";
        }
    }

    private bool CanCreateClient() =>
        !string.IsNullOrWhiteSpace(NewClientCode) && !string.IsNullOrWhiteSpace(NewClientName);

    [RelayCommand(CanExecute = nameof(CanRenameClient))]
    private async Task RenameClientAsync()
    {
        if (SelectedClient is null)
        {
            return;
        }

        try
        {
            await _admin.RenameClientAsync(SelectedClient.ClientCode, SelectedClientName);
            var code = SelectedClient.ClientCode;
            await InitializeAsync();
            SelectedClient = Clients.FirstOrDefault(client => client.ClientCode == code);
            StatusText = $"已更新客户端 {code} 的名称。";
        }
        catch (Exception exception)
        {
            StatusText = $"改名失败：{exception.Message}";
        }
    }

    private bool CanRenameClient() =>
        SelectedClient is not null &&
        !string.IsNullOrWhiteSpace(SelectedClientName) &&
        !string.Equals(SelectedClientName.Trim(), SelectedClient.ClientName, StringComparison.Ordinal);

    public async Task DeleteSelectedClientAsync()
    {
        if (SelectedClient is null)
        {
            StatusText = "请先选择客户端系统。";
            return;
        }

        var code = SelectedClient.ClientCode;
        try
        {
            await _admin.DeleteClientAsync(code);
            await InitializeAsync();
            StatusText = $"已删除客户端 {code}。";
        }
        catch (Exception exception)
        {
            StatusText = $"删除失败：{exception.Message}";
        }
    }

    [RelayCommand]
    private void CancelChanges() => LoadPoints();

    public async Task ExportCsvAsync(
        Stream stream,
        bool outputOnly,
        CancellationToken cancellationToken = default)
    {
        await using var writer = new StreamWriter(
            stream,
            new UTF8Encoding(encoderShouldEmitUTF8Identifier: true),
            leaveOpen: true);
        await writer.WriteLineAsync(
            "pointId,pointName,dataType,unit,direction,address,referencePointId,deadband,group");
        foreach (var point in Points.Where(point => !outputOnly || point.Direction == PointDirection.Output))
        {
            await writer.WriteLineAsync(string.Join(
                ',',
                Escape(point.PointId),
                Escape(point.PointName),
                point.DataType,
                Escape(point.Unit),
                point.Direction,
                Escape(point.Address),
                Escape(point.SourceOutputPointId),
                point.Deadband.ToString(CultureInfo.InvariantCulture),
                Escape(point.Group)));
        }
        await writer.FlushAsync(cancellationToken);
        StatusText = outputOnly ? "输出测点已导出。" : "数据源总表已导出。";
    }

    public async Task ImportCsvAsync(Stream stream, CancellationToken cancellationToken = default)
    {
        if (!CanEditPoints)
        {
            StatusText = "客户端正在运行通讯，请先停止通讯后导入点表。";
            return;
        }

        using var reader = new StreamReader(stream, Encoding.UTF8, true, leaveOpen: true);
        var records = SplitCsvRecords(await reader.ReadToEndAsync(cancellationToken));
        if (records.Count == 0)
        {
            StatusText = "导入失败：CSV 文件为空。";
            return;
        }

        var columns = records[0]
            .Select(column => column.Trim().TrimStart('\uFEFF'))
            .ToArray();
        var requiredColumns = new[]
        {
            "pointId",
            "pointName",
            "dataType",
            "direction",
            "address"
        };
        var missingColumns = requiredColumns
            .Where(required => !columns.Contains(required, StringComparer.OrdinalIgnoreCase))
            .ToArray();
        if (missingColumns.Length > 0)
        {
            StatusText = $"导入失败：CSV 缺少必需列 {string.Join("、", missingColumns)}。";
            return;
        }

        var imported = new List<EditablePointRow>();
        var errors = new List<string>();
        for (var recordIndex = 1; recordIndex < records.Count; recordIndex++)
        {
            var values = records[recordIndex];
            if (values.All(string.IsNullOrWhiteSpace))
            {
                continue;
            }

            var lineNumber = recordIndex + 1;
            string Value(string name)
            {
                var index = Array.FindIndex(
                    columns,
                    column => column.Equals(name, StringComparison.OrdinalIgnoreCase));
                return index >= 0 && index < values.Length ? values[index] : string.Empty;
            }

            var dataTypeText = Value("dataType");
            var parsedDataTypeValue = PointDataType.Float64;
            if (!string.IsNullOrWhiteSpace(dataTypeText) &&
                !Enum.TryParse(dataTypeText, true, out parsedDataTypeValue))
            {
                errors.Add($"第 {lineNumber} 行数据类型无效：{dataTypeText}");
                continue;
            }

            var directionText = Value("direction");
            var parsedDirectionValue = PointDirection.Output;
            if (!string.IsNullOrWhiteSpace(directionText) &&
                !Enum.TryParse(directionText, true, out parsedDirectionValue))
            {
                errors.Add($"第 {lineNumber} 行方向无效：{directionText}");
                continue;
            }

            var deadbandText = Value("deadband");
            if (!string.IsNullOrWhiteSpace(deadbandText) &&
                !double.TryParse(
                    deadbandText,
                    NumberStyles.Float,
                    CultureInfo.InvariantCulture,
                    out _))
            {
                errors.Add($"第 {lineNumber} 行死区无效：{deadbandText}");
                continue;
            }

            var pointId = Value("pointId").Trim();
            var pointName = Value("pointName").Trim();
            var address = Value("address").Trim();
            if (string.IsNullOrWhiteSpace(pointId) ||
                string.IsNullOrWhiteSpace(pointName) ||
                string.IsNullOrWhiteSpace(address))
            {
                errors.Add($"第 {lineNumber} 行测点 ID、名称和地址不能为空。");
                continue;
            }

            var sourceOutputPointId = Value("referencePointId").Trim();
            if (parsedDirectionValue == PointDirection.Input &&
                string.IsNullOrWhiteSpace(sourceOutputPointId))
            {
                errors.Add($"第 {lineNumber} 行 INPUT 测点必须填写 referencePointId。");
                continue;
            }

            imported.Add(new EditablePointRow
            {
                PointId = pointId,
                PointName = pointName,
                DataType = parsedDataTypeValue,
                Unit = Value("unit"),
                Direction = parsedDirectionValue,
                Address = address,
                SourceOutputPointId = sourceOutputPointId,
                Deadband = string.IsNullOrWhiteSpace(deadbandText)
                    ? 0
                    : double.Parse(deadbandText, NumberStyles.Float, CultureInfo.InvariantCulture),
                Group = string.IsNullOrWhiteSpace(Value("group")) ? "默认" : Value("group")
            });
        }

        var duplicateIds = imported
            .GroupBy(point => point.PointId, StringComparer.Ordinal)
            .Where(group => group.Count() > 1)
            .Select(group => group.Key)
            .ToArray();
        if (duplicateIds.Length > 0)
        {
            errors.Add($"测点 ID 重复：{string.Join("、", duplicateIds)}");
        }

        if (errors.Count > 0)
        {
            StatusText = $"导入失败：{string.Join("；", errors.Take(5))}";
            return;
        }

        if (imported.Count == 0)
        {
            StatusText = "导入失败：CSV 中没有可用测点。";
            return;
        }

        Points.Clear();
        foreach (var point in imported)
        {
            Points.Add(point);
        }
        RefreshFilteredPoints();
        StatusText = $"已导入 {Points.Count} 个测点，点击保存后下发。";
    }

    private void LoadPoints()
    {
        Points.Clear();
        if (SelectedClient is null)
        {
            FilteredPoints.Clear();
            CanEditPoints = false;
            IsPointsReadOnly = true;
            NotifyLayoutState();
            return;
        }

        CanEditPoints = CanEditSelectedClientPoints();
        IsPointsReadOnly = !CanEditPoints;
        foreach (var point in SelectedClient.Points)
        {
            Points.Add(EditablePointRow.From(point));
        }
        RefreshFilteredPoints();
        StatusText = CanEditPoints
            ? $"{SelectedClient.ClientName}：{Points.Count} 个测点。"
            : $"{SelectedClient.ClientName} 正在运行通讯，点表已锁定。";
        NotifyLayoutState();
    }

    private void NotifyLayoutState()
    {
        OnPropertyChanged(nameof(ClientCountText));
        OnPropertyChanged(nameof(HasNoClients));
        OnPropertyChanged(nameof(SelectedClientStateText));
        OnPropertyChanged(nameof(ShowPoints));
        OnPropertyChanged(nameof(ShowNoClientState));
        OnPropertyChanged(nameof(ShowNoPointsState));
        OnPropertyChanged(nameof(ShowNoFilteredPoints));
    }

    private bool CanEditSelectedClientPoints() =>
        SelectedClient is not
        {
            Online: true
        } ||
        (!SelectedClient.DesiredRunning &&
         SelectedClient.CommunicationState is not
             (ClientConnectionState.Connected or ClientConnectionState.Connecting));

    private void RefreshFilteredPoints()
    {
        var selectedPointId = SelectedPoint?.PointId;
        FilteredPoints.Clear();
        foreach (var point in Points.Where(point =>
                     SelectedPointFilter?.Direction is null ||
                     point.Direction == SelectedPointFilter.Direction))
        {
            FilteredPoints.Add(point);
        }

        foreach (var filter in PointFilters)
        {
            filter.Count = filter.Direction is null
                ? Points.Count
                : Points.Count(point => point.Direction == filter.Direction);
        }

        SelectedPoint = FilteredPoints.FirstOrDefault(point => point.PointId == selectedPointId)
                        ?? FilteredPoints.FirstOrDefault();
        NotifyLayoutState();
    }

    private static string Escape(string value) =>
        value.Contains(',') ||
        value.Contains('"') ||
        value.Contains('\r') ||
        value.Contains('\n')
            ? '"' + value.Replace("\"", "\"\"") + '"'
            : value;

    private static IReadOnlyList<string[]> SplitCsvRecords(string csv)
    {
        var records = new List<string[]>();
        var values = new List<string>();
        var field = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < csv.Length; index++)
        {
            var character = csv[index];
            if (character == '"')
            {
                if (quoted && index + 1 < csv.Length && csv[index + 1] == '"')
                {
                    field.Append('"');
                    index++;
                }
                else
                {
                    quoted = !quoted;
                }
            }
            else if (character == ',' && !quoted)
            {
                values.Add(field.ToString());
                field.Clear();
            }
            else if (character is '\r' or '\n' && !quoted)
            {
                if (character == '\r' && index + 1 < csv.Length && csv[index + 1] == '\n')
                {
                    index++;
                }

                values.Add(field.ToString());
                field.Clear();
                if (values.Any(value => value.Length > 0))
                {
                    records.Add(values.ToArray());
                }
                values.Clear();
            }
            else
            {
                field.Append(character);
            }
        }

        if (field.Length > 0 || values.Count > 0)
        {
            values.Add(field.ToString());
            if (values.Any(value => value.Length > 0))
            {
                records.Add(values.ToArray());
            }
        }

        return records;
    }
}

public sealed partial class PointFilterOption : ObservableObject
{
    [ObservableProperty] private int _count;

    public PointFilterOption(string label, PointDirection? direction)
    {
        Label = label;
        Direction = direction;
    }

    public string Label { get; }
    public PointDirection? Direction { get; }
    public string Display => Count > 0 ? $"{Label}  {Count}" : Label;

    partial void OnCountChanged(int value) => OnPropertyChanged(nameof(Display));
}

public sealed partial class ClientOption : ObservableObject
{
    [ObservableProperty] private bool _online;
    [ObservableProperty] private bool _desiredRunning;
    [ObservableProperty] private ClientConnectionState _communicationState;
    [ObservableProperty] private string _clientName;

    public ClientOption(
        string clientCode,
        string clientName,
        bool online,
        bool desiredRunning,
        ClientConnectionState communicationState,
        IReadOnlyList<PointDefinition> points)
    {
        ClientCode = clientCode;
        ClientName = clientName;
        Online = online;
        DesiredRunning = desiredRunning;
        CommunicationState = communicationState;
        Points = points;
    }

    public string ClientCode { get; }
    public IReadOnlyList<PointDefinition> Points { get; }
    public string Display => $"{ClientName}  ({ClientCode})";
    public string CommunicationText => CommunicationState switch
    {
        ClientConnectionState.Connected => "通讯正常",
        ClientConnectionState.Connecting => "正在连接设备",
        ClientConnectionState.Error => "通讯故障",
        _ => Online ? "通讯未启动" : "客户端离线"
    };

    partial void OnCommunicationStateChanged(ClientConnectionState value) =>
        OnPropertyChanged(nameof(CommunicationText));
    partial void OnOnlineChanged(bool value) =>
        OnPropertyChanged(nameof(CommunicationText));
}

public sealed partial class EditablePointRow : ObservableObject
{
    [ObservableProperty] private string _pointId = string.Empty;
    [ObservableProperty] private string _pointName = string.Empty;
    [ObservableProperty] private PointDataType _dataType = PointDataType.Float64;
    [ObservableProperty] private string _unit = "/";
    [ObservableProperty] private PointDirection _direction = PointDirection.Output;
    [ObservableProperty] private string _address = string.Empty;
    [ObservableProperty] private string _sourceOutputPointId = string.Empty;
    [ObservableProperty] private double _deadband;
    [ObservableProperty] private string _group = "默认";

    public IReadOnlyList<PointDataType> DataTypeOptions { get; } = Enum.GetValues<PointDataType>();
    public IReadOnlyList<PointDirection> DirectionOptions { get; } = Enum.GetValues<PointDirection>();

    public static EditablePointRow From(PointDefinition point) => new()
    {
        PointId = point.PointId,
        PointName = point.PointName,
        DataType = point.DataType,
        Unit = point.Unit,
        Direction = point.Direction,
        Address = point.Address,
        SourceOutputPointId = point.SourceOutputPointId ?? string.Empty,
        Deadband = point.Deadband,
        Group = point.Group
    };

    public PointDefinition ToDefinition() => new()
    {
        PointId = PointId.Trim(),
        PointName = PointName.Trim(),
        DataType = DataType,
        Unit = Unit.Trim(),
        Direction = Direction,
        Address = Address.Trim(),
        SourceOutputPointId = string.IsNullOrWhiteSpace(SourceOutputPointId)
            ? null
            : SourceOutputPointId.Trim(),
        Deadband = Math.Max(0, Deadband),
        Group = string.IsNullOrWhiteSpace(Group) ? "默认" : Group.Trim()
    };
}
