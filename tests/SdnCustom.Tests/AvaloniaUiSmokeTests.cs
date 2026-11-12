using Avalonia;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;

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
    }
}
