using System.Text.Json;
using System.Text.Json.Serialization;

namespace SdnCustom.Client.Core.Configuration;

public sealed class ClientConfigStore : IClientConfigStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        Converters =
        {
            new ClientProtocolJsonConverter(),
            new JsonStringEnumConverter()
        }
    };

    public ClientConfigStore(string? configPath = null)
    {
        ConfigPath = configPath ?? GetDefaultPath();
    }

    public string ConfigPath { get; }

    public async Task<ClientConfiguration> LoadAsync(CancellationToken cancellationToken = default)
    {
        if (!File.Exists(ConfigPath))
        {
            return new ClientConfiguration();
        }

        await using var stream = File.OpenRead(ConfigPath);
        return await JsonSerializer.DeserializeAsync<ClientConfiguration>(
                   stream,
                   SerializerOptions,
                   cancellationToken)
               .ConfigureAwait(false)
               ?? new ClientConfiguration();
    }

    public async Task SaveAsync(ClientConfiguration configuration, CancellationToken cancellationToken = default)
    {
        var errors = ClientConfigurationValidator.Validate(configuration);
        if (errors.Count > 0)
        {
            throw new InvalidOperationException(string.Join(Environment.NewLine, errors));
        }

        var directory = Path.GetDirectoryName(ConfigPath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var temporaryPath = ConfigPath + ".tmp";
        await using (var stream = new FileStream(
                         temporaryPath,
                         FileMode.Create,
                         FileAccess.Write,
                         FileShare.None,
                         16 * 1024,
                         FileOptions.Asynchronous | FileOptions.WriteThrough))
        {
            await JsonSerializer.SerializeAsync(stream, configuration, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        File.Move(temporaryPath, ConfigPath, true);
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(ConfigPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        }
    }

    public static string GetDefaultPath()
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(root, "SdnCustom", "client.json");
    }
}
