using System.Windows;

namespace Hopa.Windows;

public partial class RenameWindow : Window
{
    public RenameWindow(string title)
    {
        InitializeComponent();
        TitleBox.Text = title;
        TitleBox.SelectAll();
        TitleBox.Focus();
    }

    public string TitleValue => TitleBox.Text.Trim();

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        if (TitleValue.Length == 0)
        {
            return;
        }

        DialogResult = true;
    }
}
