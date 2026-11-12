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
    public void RejectsInputPointWithoutReference()
    {
        var configuration = new ClientConfiguration
        {
            ClientCode = "CLIENT-01",
            Points =
            [
                new PointDefinition
                {
                    PointId = "IN",
                    PointName = "Input",
                    Address = "A",
                    Direction = PointDirection.Input
                }
            ]
        };

        var errors = ClientConfigurationValidator.Validate(configuration);

        Assert.Contains(errors, error => error.Contains("必须引用 OUTPUT", StringComparison.Ordinal));
    }

    [Fact]
    public void RejectsLocalInputReferenceThatDoesNotPointToOutput()
    {
        var configuration = new ClientConfiguration
        {
            ClientCode = "CLIENT-01",
            Points =
            [
                new PointDefinition
                {
                    PointId = "IN-1",
                    PointName = "First input",
                    Address = "A",
                    Direction = PointDirection.Input,
                    SourceOutputPointId = "IN-2"
                },
                new PointDefinition
                {
                    PointId = "IN-2",
                    PointName = "Second input",
                    Address = "B",
                    Direction = PointDirection.Input,
                    SourceOutputPointId = "OUT"
                },
                new PointDefinition
                {
                    PointId = "OUT",
                    PointName = "Output",
                    Address = "C",
                    Direction = PointDirection.Output
                }
            ]
        };

        var errors = ClientConfigurationValidator.Validate(configuration);

        Assert.Contains(
            errors,
            error => error.Contains("IN-1", StringComparison.Ordinal) &&
                     error.Contains("不能引用", StringComparison.Ordinal));
    }

    [Fact]
    public void RejectsMissingLocalOutputReference()
    {
        var configuration = new ClientConfiguration
        {
            ClientCode = "CLIENT-01",
            Points =
            [
                new PointDefinition
                {
                    PointId = "IN",
                    PointName = "Input",
                    Address = "A",
                    Direction = PointDirection.Input,
                    SourceOutputPointId = "MISSING"
                }
            ]
        };

        var errors = ClientConfigurationValidator.Validate(configuration);

        Assert.Contains(
            errors,
            error => error.Contains("MISSING", StringComparison.Ordinal) &&
                     error.Contains("不存在", StringComparison.Ordinal));
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
