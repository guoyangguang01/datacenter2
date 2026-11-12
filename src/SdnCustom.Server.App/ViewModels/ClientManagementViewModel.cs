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

    [ObservableProperty] private ClientOption? _selectedClient;
    [ObservableProperty] private EditablePointRow? _selectedPoint;
    [ObservableProperty] private string _newClientCode = string.Empty;
    [ObservableProperty] private string _newClientName = string.Empty;
    [ObservableProperty] private string _statusText = string.Empty;
    [ObservableProperty] private bool _canEditPoints = true;
    [ObservableProperty] private bool _isPointsReadOnly;

    public ObservableCollection<ClientOption> Clients { get; } = [];
    public ObservableCollection<EditablePointRow> Points { get; } = [];
    public IReadOnlyList<PointDataType> DataTypeOptions { get; } = Enum.GetValues<PointDataType>();
    public IReadOnlyList<PointDirection> DirectionOptions { get; } = Enum.GetValues<PointDirection>();

    public ClientManagementViewModel(IAdminFacade admin)
    {
        _admin = admin;
    }

    partial void OnSelectedClientChanged(ClientOption? value) => LoadPoints();

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
                client.Points));
        }
        SelectedClient = Clients.FirstOrDefault(client => client.ClientCode == selectedCode)
                         ?? Clients.FirstOrDefault();
        StatusText = $"已加载 {Clients.Count} 个客户端。";
    }

    public async Task RefreshRunStateAsync(CancellationToken cancellationToken = default)
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
        }

        if (SelectedClient is not null)
        {
            CanEditPoints = !(SelectedClient.Online && SelectedClient.DesiredRunning);
            IsPointsReadOnly = !CanEditPoints;
            if (!CanEditPoints)
            {
                StatusText = $"{SelectedClient.ClientName} 正在运行通讯，点表已锁定。";
            }
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
        SelectedPoint = row;
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
            SelectedPoint = Points.FirstOrDefault();
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

    [RelayCommand]
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
        var header = await reader.ReadLineAsync(cancellationToken);
        if (header is null)
        {
            return;
        }

        var columns = header.Split(',')
            .Select(column => column.Trim().TrimStart('\uFEFF'))
            .ToArray();
        var imported = new List<EditablePointRow>();
        while (await reader.ReadLineAsync(cancellationToken) is { } line)
        {
            if (string.IsNullOrWhiteSpace(line))
            {
                continue;
            }

            var values = SplitCsv(line);
            string Value(string name)
            {
                var index = Array.FindIndex(
                    columns,
                    column => column.Equals(name, StringComparison.OrdinalIgnoreCase));
                return index >= 0 && index < values.Count ? values[index] : string.Empty;
            }

            imported.Add(new EditablePointRow
            {
                PointId = Value("pointId"),
                PointName = Value("pointName"),
                DataType = Enum.TryParse<PointDataType>(Value("dataType"), true, out var dataType)
                    ? dataType
                    : PointDataType.Float64,
                Unit = Value("unit"),
                Direction = Enum.TryParse<PointDirection>(Value("direction"), true, out var direction)
                    ? direction
                    : PointDirection.Output,
                Address = Value("address"),
                SourceOutputPointId = Value("referencePointId"),
                Deadband = double.TryParse(
                    Value("deadband"),
                    NumberStyles.Float,
                    CultureInfo.InvariantCulture,
                    out var deadband)
                    ? deadband
                    : 0,
                Group = string.IsNullOrWhiteSpace(Value("group")) ? "默认" : Value("group")
            });
        }

        Points.Clear();
        foreach (var point in imported)
        {
            Points.Add(point);
        }
        SelectedPoint = Points.FirstOrDefault();
        StatusText = $"已导入 {Points.Count} 个测点，点击保存后下发。";
    }

    private void LoadPoints()
    {
        Points.Clear();
        if (SelectedClient is null)
        {
            CanEditPoints = false;
            IsPointsReadOnly = true;
            return;
        }

        CanEditPoints = !(SelectedClient.Online && SelectedClient.DesiredRunning);
        IsPointsReadOnly = !CanEditPoints;
        foreach (var point in SelectedClient.Points)
        {
            Points.Add(EditablePointRow.From(point));
        }
        SelectedPoint = Points.FirstOrDefault();
        StatusText = CanEditPoints
            ? $"{SelectedClient.ClientName}：{Points.Count} 个测点。"
            : $"{SelectedClient.ClientName} 正在运行通讯，点表已锁定。";
    }

    private static string Escape(string value) =>
        value.Contains(',') || value.Contains('"')
            ? '"' + value.Replace("\"", "\"\"") + '"'
            : value;

    private static IReadOnlyList<string> SplitCsv(string line)
    {
        var values = new List<string>();
        var builder = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < line.Length; index++)
        {
            var character = line[index];
            if (character == '"')
            {
                if (quoted && index + 1 < line.Length && line[index + 1] == '"')
                {
                    builder.Append('"');
                    index++;
                }
                else
                {
                    quoted = !quoted;
                }
            }
            else if (character == ',' && !quoted)
            {
                values.Add(builder.ToString());
                builder.Clear();
            }
            else
            {
                builder.Append(character);
            }
        }
        values.Add(builder.ToString().TrimEnd('\r'));
        return values;
    }
}

public sealed partial class ClientOption : ObservableObject
{
    [ObservableProperty] private bool _online;
    [ObservableProperty] private bool _desiredRunning;

    public ClientOption(
        string clientCode,
        string clientName,
        bool online,
        bool desiredRunning,
        IReadOnlyList<PointDefinition> points)
    {
        ClientCode = clientCode;
        ClientName = clientName;
        Online = online;
        DesiredRunning = desiredRunning;
        Points = points;
    }

    public string ClientCode { get; }
    public string ClientName { get; }
    public IReadOnlyList<PointDefinition> Points { get; }
    public string Display => $"{ClientName}  ({ClientCode})";
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
