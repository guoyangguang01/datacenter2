using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using SdnCustom.Client.App.ViewModels;
using SdnCustom.Client.App.Views;

namespace SdnCustom.Client.App;

public sealed partial class App : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.MainWindow = new MainWindow
            {
                DataContext = new ClientViewModel()
            };
        }

        base.OnFrameworkInitializationCompleted();
    }
}
