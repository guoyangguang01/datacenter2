using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using SdnCustom.Server.Admin;

namespace SdnCustom.Server.App.Views;

public sealed partial class SystemSettingsWindow : Window
{
    private readonly IAdminFacade? _admin;
    private readonly TextBlock _grpcPortText;
    private readonly TextBlock _httpPortText;
    private readonly TextBlock _tlsText;
    private readonly TextBlock _influxUrlText;
    private readonly TextBlock _influxScopeText;
    private readonly TextBlock _dataDirectoryText;

    public SystemSettingsWindow() : this(null)
    {
    }

    public SystemSettingsWindow(IAdminFacade? admin)
    {
        _admin = admin;
        InitializeComponent();
        _grpcPortText = this.FindControl<TextBlock>("GrpcPortText")
                        ?? throw new InvalidOperationException("GrpcPortText control is missing.");
        _httpPortText = this.FindControl<TextBlock>("HttpPortText")
                        ?? throw new InvalidOperationException("HttpPortText control is missing.");
        _tlsText = this.FindControl<TextBlock>("TlsText")
                   ?? throw new InvalidOperationException("TlsText control is missing.");
        _influxUrlText = this.FindControl<TextBlock>("InfluxUrlText")
                         ?? throw new InvalidOperationException("InfluxUrlText control is missing.");
        _influxScopeText = this.FindControl<TextBlock>("InfluxScopeText")
                           ?? throw new InvalidOperationException("InfluxScopeText control is missing.");
        _dataDirectoryText = this.FindControl<TextBlock>("DataDirectoryText")
                             ?? throw new InvalidOperationException("DataDirectoryText control is missing.");
        Opened += async (_, _) => await LoadSettingsAsync();
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    private async Task LoadSettingsAsync()
    {
        if (_admin is null)
        {
            ShowUnavailable("未连接服务端管理接口。");
            return;
        }

        try
        {
            var settings = await _admin.GetSystemSettingsAsync();
            _grpcPortText.Text = $"{settings.GrpcPort}（gRPC）";
            _httpPortText.Text = $"127.0.0.1:{settings.HttpPort}";
            _tlsText.Text = settings.UseTls ? "已启用" : "已关闭";
            _influxUrlText.Text = settings.InfluxUrl;
            _influxScopeText.Text = $"{settings.InfluxOrg} / {settings.InfluxBucket}";
            _dataDirectoryText.Text = settings.DataDirectory;
        }
        catch (Exception exception)
        {
            ShowUnavailable(exception.Message);
        }
    }

    private void ShowUnavailable(string message)
    {
        _grpcPortText.Text = _httpPortText.Text = _tlsText.Text = "--";
        _influxUrlText.Text = _influxScopeText.Text = _dataDirectoryText.Text = "--";
        Title = $"系统设置 - 读取失败：{message}";
    }

    private void Close_OnClick(object? sender, RoutedEventArgs e) => Close();
}
