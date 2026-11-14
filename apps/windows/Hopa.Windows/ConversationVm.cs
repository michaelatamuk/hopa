using System.ComponentModel;

namespace Hopa.Windows;

/// <summary>One row in the sidebar.</summary>
public sealed class ConversationVm : INotifyPropertyChanged
{
    private string title = "";
    private string updatedLabel = "";

    public event PropertyChangedEventHandler? PropertyChanged;

    public string Id { get; init; } = "";

    public string SearchText { get; init; } = "";

    public int MessageCount { get; set; }

    public string Title
    {
        get => title;
        set
        {
            if (title == value)
            {
                return;
            }

            title = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Title)));
        }
    }

    public string UpdatedLabel
    {
        get => updatedLabel;
        set
        {
            if (updatedLabel == value)
            {
                return;
            }

            updatedLabel = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(UpdatedLabel)));
        }
    }
}
