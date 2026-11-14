using SdnCustom.Client.Core.Configuration;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class ClientConfigurationTests
{
    [Fact]
    public async Task SavesAndLoadsConfigurationAtomically()
    {
        var directory = Path.Combine(Path.GetTempPath(), "sdncustom-tests", Guid.NewGuid().ToString("N"));
        var path = Path.Combine(directory, "client.json");
        try
        {
            var store = new ClientConfigStore(path);
            var configuration = new ClientConfiguration
            {
                ClientCode = "CLIENT-01",
                ClientName = "Fuel system",
                Server = new ServerConnectionOptions { Host = "10.0.0.2", Port = 7443, UseTls = true },
                Device = new DeviceOptions { Protocol = ClientProtocol.CustomTcp, Host = "10.0.0.3", Port = 9002 },
                Points =
                [
                    new PointDefinition
                    {
                        PointId = "TEMP",
                        PointName = "Temperature",
                        Address = "40001",
                        DataType = PointDataType.Float64
                    }
                ]
            };

            await store.SaveAsync(configuration);
            var loaded = await store.LoadAsync();

            Assert.True(File.Exists(path));
            Assert.False(File.Exists(path + ".tmp"));
            Assert.Equal("CLIENT-01", loaded.ClientCode);
            Assert.Single(loaded.Points);
        }
        finally
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, true);
            }
        }
    }

    [Fact]
    public void AllowsOutputEndpointWithoutRouteSource()
    {
        var configuration = new ClientConfiguration
        {
            ClientCode = "CLIENT-01",
            Points =
            [
                new PointDefinition
                {
                    PointId = "OUT",
                    PointName = "Output",
                    Address = "A",
                    Direction = PointDirection.Output
                }
            ]
        };

        var errors = ClientConfigurationValidator.Validate(configuration);

        Assert.DoesNotContain(errors, error => error.Contains("来源引用", StringComparison.Ordinal));
    }

    [Fact]
    public void RejectsEndpointWithRouteSource()
    {
        var configuration = new ClientConfiguration
        {
            ClientCode = "CLIENT-01",
            Points =
            [
                new PointDefinition
                {
                    PointId = "OUT-1",
                    PointName = "First output",
                    Address = "A",
                    Direction = PointDirection.Output,
                    SourceInputPointId = "OUT-2"
                },
                new PointDefinition
                {
                    PointId = "OUT-2",
                    PointName = "Second output",
                    Address = "B",
                    Direction = PointDirection.Output,
                    SourceInputPointId = "IN"
                },
                new PointDefinition
                {
                    PointId = "IN",
                    PointName = "Input",
                    Address = "C",
                    Direction = PointDirection.Input
                }
            ]
        };

        var errors = ClientConfigurationValidator.Validate(configuration);

        Assert.Contains(
            errors,
            error => error.Contains("OUT-1", StringComparison.Ordinal) &&
                     error.Contains("PointLink", StringComparison.Ordinal));
    }

    [Fact]
    public void RejectsAnyEndpointWithRouteSource()
    {
        var configuration = new ClientConfiguration
        {
            ClientCode = "CLIENT-01",
            Points =
            [
                new PointDefinition
                {
                    PointId = "OUT",
                    PointName = "Output",
                    Address = "A",
                    Direction = PointDirection.Output,
                    SourceInputPointId = "MISSING"
                }
            ]
        };

        var errors = ClientConfigurationValidator.Validate(configuration);

        Assert.Contains(
            errors,
            error => error.Contains("OUT", StringComparison.Ordinal) &&
                     error.Contains("PointLink", StringComparison.Ordinal));
    }

    [Fact]
    public void RequiresPinnedCertificateWhenAutomaticTrustIsDisabled()
    {
        var configuration = new ClientConfiguration
        {
            Server = new ServerConnectionOptions
            {
                UseTls = true,
                AllowUntrustedServerCertificate = false,
                ServerCertificateThumbprint = string.Empty
            }
        };

        Assert.Contains(
            ClientConfigurationValidator.Validate(configuration),
            error => error.Contains("证书指纹", StringComparison.Ordinal));
    }
}
