using System.ComponentModel;

namespace Hopa.Windows;

/// <summary>One row in the sidebar.</summary>
public sealed class ConversationVm : INotifyPropertyChanged
{
    private string title = "";
    private string updatedLabel = "";
    private bool pinned;
    private bool archived;

    public event PropertyChangedEventHandler? PropertyChanged;

    public string Id { get; init; } = "";

    public string SearchText { get; init; } = "";

    public int MessageCount { get; set; }

    public long UpdatedAt { get; set; }

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
            Raise(nameof(Title));
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
            Raise(nameof(UpdatedLabel));
        }
    }

    public bool Pinned
    {
        get => pinned;
        set
        {
            if (pinned == value)
            {
                return;
            }

            pinned = value;
            Raise(nameof(Pinned));
            Raise(nameof(PinLabel));
        }
    }

    public bool Archived
    {
        get => archived;
        set
        {
            if (archived == value)
            {
                return;
            }

            archived = value;
            Raise(nameof(Archived));
            Raise(nameof(ArchivedLabel));
        }
    }

    public string PinLabel => pinned ? "PIN" : "";

    public string ArchivedLabel => archived ? "archived" : "";

    private void Raise(string name) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
