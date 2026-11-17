using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using SdnCustom.Server.Admin;
using SdnCustom.Server.Configuration;
using SdnCustom.Server.Grpc;
using SdnCustom.Server.History;
using SdnCustom.Server.Persistence;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.Hosting;

public static class ServerHost
{
    public static WebApplication Build(string[] args, Action<ServerSettings>? configure = null)
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            Args = args,
            ContentRootPath = AppContext.BaseDirectory
        });

        builder.Services.AddWindowsService(options => options.ServiceName = "SDNCustom Server");
        builder.Services.AddSystemd();
        builder.Services.Configure<ServerSettings>(builder.Configuration.GetSection("SdnCustom"));
        builder.Services.PostConfigure<ServerSettings>(settings => configure?.Invoke(settings));

        var settings = builder.Configuration.GetSection("SdnCustom").Get<ServerSettings>() ?? new ServerSettings();
        configure?.Invoke(settings);
        var dataDirectory = Path.GetFullPath(settings.DataDirectory, AppContext.BaseDirectory);
        Directory.CreateDirectory(dataDirectory);
        var databasePath = Path.Combine(dataDirectory, "sdncustom.db");
        var certificate = settings.UseTls
            ? EnsureServerCertificate(dataDirectory, settings)
            : null;

        builder.WebHost.ConfigureKestrel(options =>
        {
            options.AddServerHeader = false;
            options.ListenAnyIP(settings.GrpcPort, listen =>
            {
                listen.Protocols = HttpProtocols.Http2;
                if (certificate is not null)
                {
                    listen.UseHttps(certificate);
                }
            });
            options.ListenLocalhost(settings.HttpPort, listen =>
            {
                listen.Protocols = HttpProtocols.Http1;
            });
        });

        builder.Services.AddDbContextFactory<ServerDbContext>(options =>
            options.UseSqlite(new SqliteConnectionStringBuilder
            {
                DataSource = databasePath,
                DefaultTimeout = 30
            }.ToString()));
        builder.Services.AddGrpc(options =>
        {
            options.MaxReceiveMessageSize = 16 * 1024 * 1024;
            options.MaxSendMessageSize = 16 * 1024 * 1024;
        });

        builder.Services.AddSingleton<IClientSystemStore, ClientSystemStore>();
        builder.Services.AddSingleton<IBusinessSystemStore, BusinessSystemStore>();
        builder.Services.AddSingleton<FuelSystemSampleSeeder>();
        builder.Services.AddSingleton<ILatestValueStore, LatestValueStore>();
        builder.Services.AddSingleton<IAgentRegistry, AgentRegistry>();
        builder.Services.AddSingleton<IServerCommunicationGate, ServerCommunicationGate>();
        builder.Services.AddSingleton<IClientCommunicationStateStore, ClientCommunicationStateStore>();
        builder.Services.AddSingleton<IPointRouter, PointRouter>();
        builder.Services.AddSingleton<PointLinkCatalog>();
        builder.Services.AddSingleton<IServerLogStore, ServerLogStore>();
        builder.Services.AddSingleton<InfluxHistoryStore>();
        builder.Services.AddSingleton<IHistoryStore>(provider => provider.GetRequiredService<InfluxHistoryStore>());
        builder.Services.AddHostedService(provider => provider.GetRequiredService<InfluxHistoryStore>());
        builder.Services.AddSingleton<ITelemetryService, TelemetryService>();
        builder.Services.AddSingleton<IGasMasterTableService, GasMasterTableService>();
        builder.Services.AddSingleton<IServerControlService, ServerControlService>();
        builder.Services.AddSingleton<IAdminFacade, AdminFacade>();

        var app = builder.Build();
        app.MapGrpcService<AgentGatewayService>();
        app.MapGet("/health", () => Results.Ok(new
        {
            status = "ok",
            service = "sdncustom",
            time = DateTimeOffset.UtcNow
        }));
        app.MapGet("/api/status", async (IAdminFacade admin, CancellationToken cancellationToken) =>
            Results.Ok(await admin.GetSnapshotAsync(cancellationToken)));
        app.MapGet("/api/system/settings", async (
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
            Results.Ok(await admin.GetSystemSettingsAsync(cancellationToken)));
        app.MapGet("/api/clients", async (
            IAdminFacade admin,
            IServerCommunicationGate communicationGate,
            CancellationToken cancellationToken) =>
        {
            if (!communicationGate.IsRunning)
            {
                return Results.Conflict(new
                {
                    message = "服务端通讯未启动，禁止客户端连接。"
                });
            }

            var snapshot = await admin.GetSnapshotAsync(cancellationToken);
            return Results.Ok(snapshot.Clients
                .Select(client => new ClientDirectoryItem(
                    client.ClientCode,
                    client.ClientName,
                    client.SystemCode,
                    client.SystemName,
                    client.Online))
                .ToArray());
        });
        app.MapGet("/api/logs", async (
            int? count,
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
            Results.Ok(await admin.GetLogsAsync(count ?? 500, cancellationToken)));
        app.MapPost("/api/clients", async (
            CreateClientRequest request,
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
        {
            await admin.CreateClientAsync(
                request.ClientCode,
                request.ClientName,
                cancellationToken,
                request.SystemCode,
                request.Protocol);
            return Results.Created($"/api/clients/{request.ClientCode}", request);
        });
        app.MapPost("/api/systems/import-gas", async (
            ImportGasMasterRequest request,
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
            Results.Ok(await admin.ImportGasMasterAsync(
                request.SystemCode,
                request.SystemName,
                request.Csv,
                cancellationToken)));
        app.MapGet("/api/systems/{systemCode}/export-gas", async (
            string systemCode,
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
        {
            var csv = await admin.ExportGasMasterAsync(systemCode, cancellationToken);
            return Results.File(
                System.Text.Encoding.UTF8.GetBytes(csv),
                "text/csv; charset=utf-8",
                $"{systemCode}-gas-master.csv");
        });
        app.MapPut("/api/clients/{clientCode}", async (
            string clientCode,
            UpdateClientRequest request,
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
        {
            var protocol = request.Protocol;
            if (protocol is null)
            {
                protocol = (await admin.GetSnapshotAsync(cancellationToken))
                    .Clients.SingleOrDefault(client => client.ClientCode == clientCode)
                    ?.Protocol;
            }

            if (protocol is null)
            {
                return Results.NotFound();
            }

            await admin.UpdateClientAsync(
                clientCode,
                request.ClientName,
                protocol.Value,
                cancellationToken);
            return Results.NoContent();
        });
        app.MapDelete("/api/clients/{clientCode}", async (
            string clientCode,
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
        {
            await admin.DeleteClientAsync(clientCode, cancellationToken);
            return Results.NoContent();
        });
        app.MapPost("/api/communication/start", async (
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
        {
            await admin.StartCommunicationAsync(cancellationToken);
            return Results.NoContent();
        });
        app.MapPost("/api/communication/stop", async (
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
        {
            await admin.StopCommunicationAsync(cancellationToken);
            return Results.NoContent();
        });
        app.MapPost("/api/clients/{clientCode}/write", async (
            string clientCode,
            WritePointRequest request,
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
        {
            await admin.WritePointAsync(clientCode, request.PointId, JsonValue(request.Value), cancellationToken);
            return Results.NoContent();
        });
        app.MapPut("/api/clients/{clientCode}/points", async (
            string clientCode,
            List<PointDefinition> points,
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
        {
            await admin.SaveClientPointsAsync(clientCode, points, cancellationToken);
            return Results.NoContent();
        });
        app.MapGet("/api/history", async (
            string clientCode,
            string pointId,
            PointDirection direction,
            DateTimeOffset from,
            DateTimeOffset to,
            int? limit,
            IAdminFacade admin,
            CancellationToken cancellationToken) =>
        {
            return Results.Ok(await admin.QueryHistoryAsync(
                clientCode,
                pointId,
                direction,
                from,
                to,
                limit ?? 1000,
                cancellationToken));
        });
        app.MapGet("/api/certificate", () =>
        {
            var certificatePath = Path.Combine(dataDirectory, "certs", "server.cer");
            return File.Exists(certificatePath)
                ? Results.File(certificatePath, "application/x-x509-ca-cert", "sdncustom-server.cer")
                : Results.NotFound(new { message = "TLS is disabled or the certificate has not been generated." });
        });

        app.Services.GetRequiredService<ILoggerFactory>()
            .CreateLogger("SDNCustom")
            .LogInformation(
                "Server data directory: {DataDirectory}; gRPC: {GrpcPort}; admin: {HttpPort}; TLS: {Tls}",
                dataDirectory,
                settings.GrpcPort,
                settings.HttpPort,
                settings.UseTls);
        return app;
    }

    private static object? JsonValue(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.Null or JsonValueKind.Undefined => null,
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        JsonValueKind.Number when value.TryGetInt64(out var integer) => integer,
        JsonValueKind.Number => value.GetDouble(),
        JsonValueKind.String => value.GetString(),
        _ => value.GetRawText()
    };

    public static async Task InitializeAsync(WebApplication app, CancellationToken cancellationToken = default)
    {
        await app.Services.GetRequiredService<IClientSystemStore>()
            .InitializeAsync(cancellationToken).ConfigureAwait(false);
        var settings = app.Services.GetRequiredService<IOptions<ServerSettings>>().Value;
        if (settings.SeedFuelSystemSample)
        {
            await app.Services.GetRequiredService<FuelSystemSampleSeeder>()
                .SeedAsync(cancellationToken).ConfigureAwait(false);
        }
        await app.Services.GetRequiredService<PointLinkCatalog>()
            .ReloadAsync(cancellationToken).ConfigureAwait(false);
        await app.Services.GetRequiredService<ILatestValueStore>()
            .InitializeAsync(cancellationToken).ConfigureAwait(false);
    }

    private static X509Certificate2 EnsureServerCertificate(string dataDirectory, ServerSettings settings)
    {
        if (!string.IsNullOrWhiteSpace(settings.CertificatePath))
        {
            return X509CertificateLoader.LoadPkcs12FromFile(
                Path.GetFullPath(settings.CertificatePath, AppContext.BaseDirectory),
                settings.CertificatePassword,
                X509KeyStorageFlags.DefaultKeySet);
        }

        var certDirectory = Path.Combine(dataDirectory, "certs");
        Directory.CreateDirectory(certDirectory);
        var pfxPath = Path.Combine(certDirectory, "server.pfx");
        var cerPath = Path.Combine(certDirectory, "server.cer");
        if (File.Exists(pfxPath))
        {
            return X509CertificateLoader.LoadPkcs12FromFile(
                pfxPath,
                string.Empty,
                X509KeyStorageFlags.DefaultKeySet);
        }

        using var rsa = RSA.Create(3072);
        var request = new CertificateRequest(
            "CN=SDNCustom Server",
            rsa,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment,
            false));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new("1.3.6.1.5.5.7.3.1") },
            false));
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName("localhost");
        san.AddIpAddress(IPAddress.Loopback);
        san.AddIpAddress(IPAddress.Any);
        request.CertificateExtensions.Add(san.Build());

        using var certificate = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1),
            DateTimeOffset.UtcNow.AddYears(5));
        File.WriteAllBytes(pfxPath, certificate.Export(X509ContentType.Pfx));
        File.WriteAllBytes(cerPath, certificate.Export(X509ContentType.Cert));
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(pfxPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        }

        return X509CertificateLoader.LoadPkcs12FromFile(
            pfxPath,
            string.Empty,
            X509KeyStorageFlags.DefaultKeySet);
    }
}
