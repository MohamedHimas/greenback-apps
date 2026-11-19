using System;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace GreenbackTerminal
{
    internal static class Browser
    {
        /// Edge WebView2 is part of Windows 10/11. If it is missing, offer the free Microsoft download.
        public static bool RuntimeAvailable()
        {
            try
            {
                CoreWebView2Environment.SetLoaderDllFolderPath(Program.InstallDir);
                var v = CoreWebView2Environment.GetAvailableBrowserVersionString();
                if (!string.IsNullOrEmpty(v)) return true;
            }
            catch { }
            if (MessageBox.Show("Greenback Terminal needs the free Microsoft Edge WebView2 component, which is missing on this PC.\n\nOpen the Microsoft download page now?",
                Program.AppName, MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
                Program.OpenInBrowser("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
            return false;
        }

        public static bool IsOurs(Uri u)
        {
            return u != null && (u.Host == Program.Host || u.Host.EndsWith("." + Program.Host));
        }

        public static bool IsOauthStart(Uri u)
        {
            return u != null && u.AbsolutePath.StartsWith("/api/oauth.php") && u.Query.Contains("go=");
        }
    }

    internal class MainForm : Form
    {
        readonly WebView2 web = new WebView2();
        readonly string startLink;
        readonly NotifyIcon tray = new NotifyIcon();
        readonly Timer pulse = new Timer { Interval = 6000 };
        bool quitting, startHidden, toldTray;

        [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
        struct FLASHWINFO { public uint cbSize; public IntPtr hwnd; public uint dwFlags; public uint uCount; public uint dwTimeout; }
        [System.Runtime.InteropServices.DllImport("user32.dll")] static extern bool FlashWindowEx(ref FLASHWINFO pwfi);

        const string OfflineHtml = @"<!doctype html><html><head><meta charset='utf-8'><style>
html,body{margin:0;height:100%;background:#0b0e14;color:#e6e9ef;font-family:Segoe UI,system-ui,sans-serif}
.w{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:24px;box-sizing:border-box}
h1{font-size:22px;margin:16px 0 6px}p{color:#8a93a6;margin:0 0 22px;max-width:360px;line-height:1.5}
a{background:#22c55e;color:#04110a;border-radius:10px;padding:12px 26px;font-size:15px;font-weight:700;text-decoration:none}
</style></head><body><div class='w'><h1>You're offline</h1>
<p>Greenback Terminal needs an internet connection. Check your connection and try again.</p>
<a href='https://greenbackterminal.com/'>Try again</a></div></body></html>";

        public MainForm(string link, bool hidden = false)
        {
            startLink = link;
            startHidden = hidden;
            Text = Program.AppName;
            BackColor = ColorTranslator.FromHtml("#0b0e14");
            StartPosition = FormStartPosition.CenterScreen;
            Size = new Size(1400, 900);
            MinimumSize = new Size(420, 560);
            KeyPreview = true;
            try
            {
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("icon.ico"))
                    if (s != null) Icon = new Icon(s);
            }
            catch { }
            web.Dock = DockStyle.Fill;
            web.DefaultBackgroundColor = ColorTranslator.FromHtml("#0b0e14");
            Controls.Add(web);
            Load += async (s, e) => await Init();

            // tray icon: closing the window keeps Greenback running here, so calls still ring
            tray.Icon = Icon ?? SystemIcons.Application;
            tray.Text = Program.AppName;
            tray.Visible = true;
            var menu = new ContextMenuStrip();
            menu.Items.Add("Open Greenback Terminal", null, (s, e) => ShowApp());
            var auto = new ToolStripMenuItem("Start with Windows (calls can ring)") { Checked = Program.StartsWithWindows, CheckOnClick = true };
            auto.CheckedChanged += (s, e) => Program.StartsWithWindows = auto.Checked;
            menu.Items.Add(auto);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Quit (calls won't ring)", null, (s, e) => { quitting = true; Close(); });
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += (s, e) => ShowApp();
            tray.BalloonTipClicked += (s, e) => ShowApp();
            FormClosing += (s, e) =>
            {
                if (quitting || e.CloseReason == CloseReason.WindowsShutDown || e.CloseReason == CloseReason.TaskManagerClosing) { tray.Visible = false; return; }
                e.Cancel = true;
                Hide();
                if (!toldTray) { toldTray = true; tray.ShowBalloonTip(6000, "Greenback is still running", "Calls will still ring. Right-click this icon to quit.", ToolTipIcon.Info); }
            };
            // while hidden in the tray, check for calls every few seconds
            pulse.Tick += (s, e) => { if (!Visible && web.CoreWebView2 != null) try { web.CoreWebView2.ExecuteScriptAsync("window.GB&&GB.pulseNow&&GB.pulseNow()"); } catch { } };
            pulse.Start();
            if (startHidden) { WindowState = FormWindowState.Minimized; Shown += (s, e) => { Hide(); toldTray = true; }; }
        }

        void ShowApp()
        {
            if (!Visible) Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
        }

        /// A call is ringing (the page tells us): bring the window up and flash it.
        void Ring(string from)
        {
            ShowApp();
            TopMost = true; TopMost = false;
            var f = new FLASHWINFO { hwnd = Handle, dwFlags = 3 | 12, uCount = 0, dwTimeout = 0 }; // all + until focused
            f.cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf(f);
            try { FlashWindowEx(ref f); } catch { }
            tray.ShowBalloonTip(8000, "📞 Incoming call", (string.IsNullOrEmpty(from) ? "A member" : from) + " is calling you on Greenback", ToolTipIcon.Info);
        }

        async System.Threading.Tasks.Task Init()
        {
            try
            {
                Directory.CreateDirectory(Program.DataDir);
                // keep the page fully awake in the tray (calls), and let the ringtone play without a click first
                var opts = new CoreWebView2EnvironmentOptions("--autoplay-policy=no-user-gesture-required --disable-background-timer-throttling --disable-renderer-backgrounding --disable-backgrounding-occluded-windows");
                var env = await CoreWebView2Environment.CreateAsync(null, Program.DataDir, opts);
                await web.EnsureCoreWebView2Async(env);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Could not start the browser component:\n\n" + ex.Message, Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
                Close();
                return;
            }

            var core = web.CoreWebView2;
            var st = core.Settings;
            st.UserAgent = st.UserAgent + " GreenbackApp/windows/" + Program.Version;
            st.AreDevToolsEnabled = false;
            st.IsStatusBarEnabled = false;
            st.AreDefaultContextMenusEnabled = true;
            st.IsZoomControlEnabled = true;

            core.DocumentTitleChanged += (s, e) =>
            {
                var t = core.DocumentTitle;
                Text = string.IsNullOrWhiteSpace(t) ? Program.AppName : t;
            };

            // Links to other websites (and Google/Facebook sign-in) open in the normal browser.
            core.NewWindowRequested += (s, e) =>
            {
                e.Handled = true;
                Uri u;
                if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out u)) return;
                if (Browser.IsOurs(u) && !Browser.IsOauthStart(u)) core.Navigate(e.Uri);
                else Program.OpenInBrowser(e.Uri);
            };
            core.NavigationStarting += (s, e) =>
            {
                Uri u;
                if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out u)) return;
                if (u.Scheme == Program.Scheme) { e.Cancel = true; HandleExternal(e.Uri); return; }
                if (u.Scheme == "data" || u.Scheme == "about") return;
                if (!Browser.IsOurs(u) || Browser.IsOauthStart(u)) { e.Cancel = true; Program.OpenInBrowser(e.Uri); }
            };
            core.NavigationCompleted += (s, e) =>
            {
                if (!e.IsSuccess && e.WebErrorStatus != CoreWebView2WebErrorStatus.OperationCanceled)
                    core.NavigateToString(OfflineHtml);
            };
            core.PermissionRequested += (s, e) =>
            {
                // the microphone is asked by the website (calls, voice messages); Windows' own privacy switch still applies
                e.State = e.PermissionKind == CoreWebView2PermissionKind.Notifications || e.PermissionKind == CoreWebView2PermissionKind.ClipboardRead
                    || e.PermissionKind == CoreWebView2PermissionKind.Microphone || e.PermissionKind == CoreWebView2PermissionKind.Camera
                    ? CoreWebView2PermissionState.Allow : CoreWebView2PermissionState.Deny;
            };
            // messages from the website: {"gb":"call","from":"name"} when someone rings
            core.WebMessageReceived += (s, e) =>
            {
                try
                {
                    var m = e.TryGetWebMessageAsString() ?? "";
                    if (m.StartsWith("call:")) Ring(m.Substring(5));
                }
                catch { }
            };

            string first = startLink != null ? Program.DeepLinkToUrl(startLink) : null;
            core.Navigate(first ?? Program.Home);
        }

        /// Called when the app is started again (for example by the sign-in link from the browser).
        public void HandleExternal(string msg)
        {
            if (msg == "tray") return; // started again by Windows sign-in while already running
            if (!Visible) Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
            if (msg != null && msg.StartsWith(Program.Scheme + ":", StringComparison.OrdinalIgnoreCase) && web.CoreWebView2 != null)
            {
                var u = Program.DeepLinkToUrl(msg);
                if (u != null) web.CoreWebView2.Navigate(u);
            }
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            if (web.CoreWebView2 != null)
            {
                if (keyData == Keys.F5 || keyData == (Keys.Control | Keys.R)) { web.CoreWebView2.Reload(); return true; }
                if (keyData == Keys.F11)
                {
                    if (FormBorderStyle == FormBorderStyle.None) { FormBorderStyle = FormBorderStyle.Sizable; WindowState = FormWindowState.Normal; }
                    else { FormBorderStyle = FormBorderStyle.None; WindowState = FormWindowState.Maximized; }
                    return true;
                }
                if (keyData == (Keys.Alt | Keys.Left) && web.CoreWebView2.CanGoBack) { web.CoreWebView2.GoBack(); return true; }
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }
    }
}
