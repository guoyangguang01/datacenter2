namespace SdnCustom.Client.Core.Configuration;

public interface IClientConfigStore
{
    string ConfigPath { get; }
    Task<ClientConfiguration> LoadAsync(CancellationToken cancellationToken = default);
    Task SaveAsync(ClientConfiguration configuration, CancellationToken cancellationToken = default);
}
