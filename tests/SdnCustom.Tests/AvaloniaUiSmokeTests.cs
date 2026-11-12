using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using Avalonia.Interactivity;

[assembly: AvaloniaTestApplication(typeof(SdnCustom.Tests.TestAppBuilder))]

namespace SdnCustom.Tests;

public static class TestAppBuilder
{
    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<SdnCustom.Server.App.App>()
            .UseHeadless(new AvaloniaHeadlessPlatformOptions());
}

public sealed class AvaloniaUiSmokeTests
{
    [AvaloniaFact]
    public void ServerWindowLoadsXaml()
    {
        var window = new SdnCustom.Server.App.Views.MainWindow();
        Assert.NotNull(window.Content);
    }

    [AvaloniaFact]
    public void ClientWindowLoadsXaml()
    {
        var window = new SdnCustom.Client.App.Views.MainWindow();
        Assert.NotNull(window.Content);
    }

    [AvaloniaFact]
    public void ReferenceStyleDialogsLoadXaml()
    {
        Assert.NotNull(new SdnCustom.Client.App.Views.ClientDetailsWindow().Content);
        Assert.NotNull(new SdnCustom.Server.App.Views.ClientManagementWindow().Content);
        Assert.NotNull(new SdnCustom.Server.App.Views.SystemSettingsWindow().Content);
        Assert.NotNull(new SdnCustom.Server.App.Views.ConfirmationWindow().Content);
        Assert.NotNull(new SdnCustom.Server.App.Views.HelpWindow().Content);
    }

    [AvaloniaFact]
    public void ClientApplicationStylesLoad()
    {
        var app = new SdnCustom.Client.App.App();
        app.Initialize();
        Assert.NotEmpty(app.Styles);
    }

    [AvaloniaFact]
    public void HistoryNavigationSwitchesTabWithoutThrowing()
    {
        var window = new SdnCustom.Server.App.Views.MainWindow();
        var button = window.FindControl<Button>("HistoryNavButton");
        var tabs = window.FindControl<TabControl>("WorkspaceTabs");

        Assert.NotNull(button);
        Assert.NotNull(tabs);
        button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));

        Assert.Equal(2, tabs.SelectedIndex);
    }

    [AvaloniaFact]
    public void ConfirmationWindowAcceptsCustomMessage()
    {
        var window = new SdnCustom.Server.App.Views.ConfirmationWindow("确认删除", "删除");
        Assert.NotNull(window.Content);
    }

    [AvaloniaFact]
    public void LogNavigationFindsBottomLogPanel()
    {
        var window = new SdnCustom.Server.App.Views.MainWindow();
        var button = window.FindControl<Button>("LogNavButton");
        var panel = window.FindControl<Border>("LogPanel");
        var list = window.FindControl<ListBox>("LogList");

        Assert.NotNull(button);
        Assert.NotNull(panel);
        Assert.NotNull(list);
    }
}
