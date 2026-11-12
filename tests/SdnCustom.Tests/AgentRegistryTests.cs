using Grpc.Core;
using SdnCustom.Contracts.Agent.V1;
using SdnCustom.Server.Runtime;

namespace SdnCustom.Tests;

public sealed class AgentRegistryTests
{
    [Fact]
    public void RejectsDuplicateOnlineClientCode()
    {
        var registry = new AgentRegistry();
        var first = new AgentSession("CLIENT-1", "one", new CaptureWriter());
        var second = new AgentSession("CLIENT-1", "two", new CaptureWriter());

        Assert.True(registry.TryRegister(first, out _));
        Assert.False(registry.TryRegister(second, out var reason));
        Assert.Contains("已在线", reason);
    }

    [Fact]
    public async Task SendsOnlyToCurrentInstance()
    {
        var registry = new AgentRegistry();
        var writer = new CaptureWriter();
        var session = new AgentSession("CLIENT-1", "one", writer);
        registry.TryRegister(session, out _);

        var sent = await registry.SendAsync("CLIENT-1", new ServerMessage
        {
            StartCommunication = new StartCommunication { CommandId = "start" }
        });

        Assert.True(sent);
        Assert.Single(writer.Messages);
        Assert.NotNull(writer.Messages[0].StartCommunication);
    }

    private sealed class CaptureWriter : IServerStreamWriter<ServerMessage>
    {
        public WriteOptions? WriteOptions { get; set; }
        public List<ServerMessage> Messages { get; } = [];

        public Task WriteAsync(ServerMessage message)
        {
            Messages.Add(message);
            return Task.CompletedTask;
        }

        public Task WriteAsync(ServerMessage message, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Messages.Add(message);
            return Task.CompletedTask;
        }
    }
}
