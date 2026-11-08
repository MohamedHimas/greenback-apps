// Greenback Terminal for Windows (small edition).
// One .exe that installs itself (no admin rights needed) and shows the live website in its own
// window using Microsoft Edge WebView2, which is built into Windows 10 and 11.
// Website updates therefore appear in the app automatically.
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace GreenbackTerminal
{
    internal static class Program
    {
        public const string Host = "greenbackterminal.com";
        public const string Home = "https://" + Host + "/";
        public const string Scheme = "greenbackterminal";
        public const string AppName = "Greenback Terminal";
        const string PipeName = "GreenbackTerminal.Pipe.v1";
        const string MutexName = "GreenbackTerminal.Single.v1";

        public static string Version
        {
            get { var v = Assembly.GetExecutingAssembly().GetName().Version; return v.Major + "." + v.Minor + "." + v.Build; }
        }
        public static string InstallDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "GreenbackTerminal"); }
        }
        public static string DataDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GreenbackTerminal", "WebData"); }
        }
        public static string InstalledExe { get { return Path.Combine(InstallDir, "GreenbackTerminal.exe"); } }

        [STAThread]
        static void Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            if (args.Contains("--uninstall")) { Installer.Uninstall(); return; }

            string self = Assembly.GetExecutingAssembly().Location;
            bool installed = string.Equals(Path.GetFullPath(self), Path.GetFullPath(InstalledExe), StringComparison.OrdinalIgnoreCase);
            if (!installed)
            {
                // Running the downloaded file: install (or update) and start the installed copy.
                if (Installer.Install(self))
                {
                    try { Process.Start(new ProcessStartInfo(InstalledExe) { UseShellExecute = true }); } catch { }
                }
                return;
            }

            string link = args.FirstOrDefault(a => a.StartsWith(Scheme + ":", StringComparison.OrdinalIgnoreCase));
            RunApp(link);
        }

        // Kept separate so the WebView2 files are only loaded once the app runs from its install folder.
        [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
        static void RunApp(string link)
        {
            // Only one window: a second start (e.g. the sign-in link) is handed to the first one.
            bool first;
            using (var mutex = new Mutex(true, MutexName, out first))
            {
                if (!first)
                {
                    try
                    {
                        using (var c = new NamedPipeClientStream(".", PipeName, PipeDirection.Out))
                        {
                            c.Connect(3000);
                            using (var w = new StreamWriter(c)) w.Write(link ?? "focus");
                        }
                    }
                    catch { }
                    return;
                }
                if (!Browser.RuntimeAvailable()) return;
                var form = new MainForm(link);
                var listener = new Thread(() => ListenForLinks(form)) { IsBackground = true };
                listener.Start();
                Application.Run(form);
            }
        }

        static void ListenForLinks(MainForm form)
        {
            while (true)
            {
                try
                {
                    using (var s = new NamedPipeServerStream(PipeName, PipeDirection.In))
                    {
                        s.WaitForConnection();
                        string msg;
                        using (var r = new StreamReader(s)) msg = r.ReadToEnd();
                        form.BeginInvoke((Action)(() => form.HandleExternal(msg)));
                    }
                }
                catch { Thread.Sleep(500); }
            }
        }

        public static void OpenInBrowser(string url)
        {
            try
            {
                var u = new Uri(url);
                if (u.Scheme == "https" || u.Scheme == "http" || u.Scheme == "mailto")
                    Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch { }
        }

        /// greenbackterminal://login?t=..&r=..&m=..  ->  website sign-in hand-off address
        public static string DeepLinkToUrl(string link)
        {
            try
            {
                var u = new Uri(link);
                var parts = new System.Collections.Generic.List<string> { "action=app_login" };
                foreach (var pair in u.Query.TrimStart('?').Split('&'))
                {
                    int eq = pair.IndexOf('=');
                    if (eq <= 0) continue;
                    string k = pair.Substring(0, eq);
                    string v = Uri.UnescapeDataString(pair.Substring(eq + 1).Replace('+', ' '));
                    if (k == "t" || k == "r" || k == "m") parts.Add(k + "=" + Uri.EscapeDataString(v));
                }
                return Home + "api/auth.php?" + string.Join("&", parts);
            }
            catch { return null; }
        }
    }

    internal static class Installer
    {
        static string StartMenuLnk { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), Program.AppName + ".lnk"); } }
        static string DesktopLnk { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Program.AppName + ".lnk"); } }
        const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\GreenbackTerminal";

        public static bool Install(string sourceExe)
        {
            try
            {
                // Close a running copy so it can be updated.
                foreach (var p in Process.GetProcessesByName("GreenbackTerminal"))
                {
                    try
                    {
                        if (p.Id != Process.GetCurrentProcess().Id && string.Equals(p.MainModule.FileName, Program.InstalledExe, StringComparison.OrdinalIgnoreCase))
                        { p.CloseMainWindow(); if (!p.WaitForExit(3000)) p.Kill(); }
                    }
                    catch { }
                }
                Directory.CreateDirectory(Program.InstallDir);
                File.Copy(sourceExe, Program.InstalledExe, true);

                var asm = Assembly.GetExecutingAssembly();
                foreach (var name in asm.GetManifestResourceNames().Where(n => n.StartsWith("payload.")))
                {
                    using (var src = asm.GetManifestResourceStream(name))
                    using (var dst = File.Create(Path.Combine(Program.InstallDir, name.Substring("payload.".Length))))
                        src.CopyTo(dst);
                }

                MakeShortcut(StartMenuLnk);
                MakeShortcut(DesktopLnk);

                using (var k = Registry.CurrentUser.CreateSubKey(UninstallKey))
                {
                    k.SetValue("DisplayName", Program.AppName);
                    k.SetValue("DisplayVersion", Program.Version);
                    k.SetValue("Publisher", Program.AppName);
                    k.SetValue("DisplayIcon", Program.InstalledExe);
                    k.SetValue("InstallLocation", Program.InstallDir);
                    k.SetValue("URLInfoAbout", Program.Home);
                    k.SetValue("UninstallString", "\"" + Program.InstalledExe + "\" --uninstall");
                    k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                    k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                    k.SetValue("EstimatedSize", 3000, RegistryValueKind.DWord);
                }
                using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\" + Program.Scheme))
                {
                    k.SetValue("", "URL:" + Program.AppName);
                    k.SetValue("URL Protocol", "");
                    using (var ic = k.CreateSubKey("DefaultIcon")) ic.SetValue("", "\"" + Program.InstalledExe + "\",0");
                    using (var c = k.CreateSubKey(@"shell\open\command")) c.SetValue("", "\"" + Program.InstalledExe + "\" \"%1\"");
                }
                return true;
            }
            catch (Exception e)
            {
                MessageBox.Show("Greenback Terminal could not be installed:\n\n" + e.Message, Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return false;
            }
        }

        static void MakeShortcut(string path)
        {
            try
            {
                var shellType = Type.GetTypeFromProgID("WScript.Shell");
                object shell = Activator.CreateInstance(shellType);
                object lnk = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { path });
                var t = lnk.GetType();
                t.InvokeMember("TargetPath", BindingFlags.SetProperty, null, lnk, new object[] { Program.InstalledExe });
                t.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, lnk, new object[] { Program.InstallDir });
                t.InvokeMember("IconLocation", BindingFlags.SetProperty, null, lnk, new object[] { Program.InstalledExe + ",0" });
                t.InvokeMember("Description", BindingFlags.SetProperty, null, lnk, new object[] { Program.AppName });
                t.InvokeMember("Save", BindingFlags.InvokeMethod, null, lnk, null);
            }
            catch { }
        }

        public static void Uninstall()
        {
            if (MessageBox.Show("Remove Greenback Terminal from this computer?", Program.AppName, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            try { File.Delete(StartMenuLnk); } catch { }
            try { File.Delete(DesktopLnk); } catch { }
            try { Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false); } catch { }
            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\" + Program.Scheme, false); } catch { }
            // Remove the program folder after this process has closed.
            try
            {
                Process.Start(new ProcessStartInfo("cmd.exe", "/c timeout /t 2 /nobreak >nul & rmdir /s /q \"" + Program.InstallDir + "\"")
                { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
            }
            catch { }
            MessageBox.Show("Greenback Terminal has been removed.", Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }
}
