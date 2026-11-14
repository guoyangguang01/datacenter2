using Avalonia;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using SdnCustom.Server.Admin;
using SdnCustom.Server.Hosting;

namespace SdnCustom.Server.App;

internal static class Program
{
    public static async Task<int> Main(string[] args)
    {
        var headless = args.Contains("--headless", StringComparer.OrdinalIgnoreCase);
        if (!headless)
        {
            var remoteAdmin = await RemoteAdminFacade.TryCreateAsync(ReadManagementPort());
            if (remoteAdmin is not null)
            {
                App.AdminFacade = remoteAdmin;
                BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
                return 0;
            }
        }

        var app = ServerHost.Build(
            args,
            settings => settings.SeedFuelSystemSample = ReadSeedSampleSetting());
        await ServerHost.InitializeAsync(app);

        if (headless)
        {
            await app.RunAsync();
            return 0;
        }

        await app.StartAsync();
        try
        {
            App.AdminFacade = app.Services.GetRequiredService<IAdminFacade>();
            BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
        }
        finally
        {
            using var shutdown = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await app.StopAsync(shutdown.Token);
            await app.DisposeAsync();
        }

        return 0;
    }

    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .LogToTrace();

    private static int ReadManagementPort()
    {
        var configuration = new ConfigurationBuilder()
            .SetBasePath(AppContext.BaseDirectory)
            .AddJsonFile("appsettings.json", optional: true)
            .AddEnvironmentVariables()
            .Build();
        return configuration.GetValue("SdnCustom:HttpPort", 7444);
    }

    private static bool ReadSeedSampleSetting()
    {
        var configuration = new ConfigurationBuilder()
            .SetBasePath(AppContext.BaseDirectory)
            .AddJsonFile("appsettings.json", optional: true)
            .AddEnvironmentVariables()
            .Build();
        return configuration.GetValue("SdnCustom:SeedFuelSystemSample", true);
    }
}
