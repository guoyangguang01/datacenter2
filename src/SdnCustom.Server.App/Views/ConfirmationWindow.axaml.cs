using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;

namespace SdnCustom.Server.App.Views;

public sealed partial class ConfirmationWindow : Window
{
    private readonly TextBlock _messageText;
    private readonly Button _confirmButton;

    public ConfirmationWindow()
    {
        InitializeComponent();
        _messageText = this.FindControl<TextBlock>("MessageText")
                       ?? throw new InvalidOperationException("MessageText control is missing.");
        _confirmButton = this.FindControl<Button>("ConfirmButton")
                         ?? throw new InvalidOperationException("ConfirmButton control is missing.");
    }

    public ConfirmationWindow(string message, string confirmText = "确定")
        : this()
    {
        _messageText.Text = message;
        _confirmButton.Content = confirmText;
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    private void Confirm_OnClick(object? sender, RoutedEventArgs e) => Close(true);

    private void Cancel_OnClick(object? sender, RoutedEventArgs e) => Close(false);
}
