using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using SdnCustom.Server.Admin;
using SdnCustom.Server.App.ViewModels;
using SdnCustom.Server.App.Views;

namespace SdnCustom.Server.App;

public sealed partial class App : Application
{
    public static IAdminFacade? AdminFacade { get; set; }

    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var admin = AdminFacade
                        ?? throw new InvalidOperationException("Server services are unavailable.");
            desktop.MainWindow = new MainWindow
            {
                DataContext = new MainWindowViewModel(admin)
            };
        }

        base.OnFrameworkInitializationCompleted();
    }
}
