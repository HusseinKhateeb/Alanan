using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

internal static class ControlPanelLauncher
{
    [STAThread]
    private static void Main()
    {
        try
        {
            var root = Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", ".."));
            var script = Path.Combine(root, "scripts", "deploy", "Open-ControlPanel.ps1");
            if (!File.Exists(script))
            {
                MessageBox.Show("Control panel script was not found:\n" + script, "Al Anan Control", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            var info = new ProcessStartInfo
            {
                FileName = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "System32", "WindowsPowerShell", "v1.0", "powershell.exe"),
                Arguments = "-NoProfile -STA -ExecutionPolicy Bypass -File \"" + script + "\"",
                WorkingDirectory = root,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            Process.Start(info);
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "Al Anan Control", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}
