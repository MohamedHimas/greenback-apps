/* Greenback Terminal for Windows.
   A real desktop program that opens the live website in its own window, so every feature added
   to the website shows up here automatically. Security: no Node access for web pages, sandboxed. */
const { app, BrowserWindow, Menu, shell, session } = require('electron');
const path = require('path');

const HOST = 'greenbackterminal.com';
const HOME = 'https://' + HOST + '/';
const SCHEME = 'greenbackterminal';
let win = null;

if (!app.requestSingleInstanceLock()) { app.quit(); }

if (process.defaultApp && process.argv.length >= 2) {
  app.setAsDefaultProtocolClient(SCHEME, process.execPath, [path.resolve(process.argv[1])]);
} else {
  app.setAsDefaultProtocolClient(SCHEME);
}

function isOurs(u) {
  try { const h = new URL(u).hostname; return h === HOST || h.endsWith('.' + HOST); } catch (e) { return false; }
}
function isOauthStart(u) {
  try { const x = new URL(u); return x.pathname.startsWith('/api/oauth.php') && x.searchParams.has('go'); } catch (e) { return false; }
}
function openOutside(u) {
  try { const p = new URL(u).protocol; if (p === 'https:' || p === 'http:' || p === 'mailto:') shell.openExternal(u); } catch (e) {}
}
function deepLinkUrl(argv) {
  const link = (argv || []).find(a => typeof a === 'string' && a.startsWith(SCHEME + '://'));
  if (!link) return null;
  try {
    const d = new URL(link);
    const q = new URLSearchParams({ action: 'app_login' });
    for (const k of ['t', 'r', 'm']) if (d.searchParams.has(k)) q.set(k, d.searchParams.get(k));
    return HOME + 'api/auth.php?' + q.toString();
  } catch (e) { return null; }
}

function create() {
  win = new BrowserWindow({
    width: 1400, height: 900, minWidth: 380, minHeight: 500,
    backgroundColor: '#0b0e14', autoHideMenuBar: true, show: false,
    title: 'Greenback Terminal',
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false, spellcheck: false }
  });
  Menu.setApplicationMenu(null);

  const ua = win.webContents.getUserAgent().replace(/\sElectron\/\S+/, '').replace(/\sgreenback-terminal\/\S+/i, '');
  win.webContents.setUserAgent(ua + ' GreenbackApp/windows/' + app.getVersion());

  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isOurs(url) && !isOauthStart(url)) { win.loadURL(url); return { action: 'deny' }; }
    openOutside(url); return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (url.startsWith('file:')) return;
    if (!isOurs(url) || isOauthStart(url)) { e.preventDefault(); openOutside(url); }
  });
  win.webContents.on('did-fail-load', (e, code, desc, url, isMain) => {
    if (isMain && code !== -3) win.loadFile(path.join(__dirname, 'offline.html'));
  });
  win.webContents.on('before-input-event', (e, i) => {
    if (i.type !== 'keyDown') return;
    if (i.key === 'F5' || (i.control && i.key.toLowerCase() === 'r')) { win.webContents.reload(); e.preventDefault(); }
    else if (i.key === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    else if (i.alt && i.key === 'ArrowLeft' && win.webContents.navigationHistory.canGoBack()) { win.webContents.navigationHistory.goBack(); e.preventDefault(); }
  });
  win.webContents.session.on('will-download', () => {}); // normal downloads are saved by Windows' Save dialog
  win.once('ready-to-show', () => win.show());

  win.loadURL(deepLinkUrl(process.argv) || HOME);
}

app.on('second-instance', (e, argv) => {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.focus();
  const u = deepLinkUrl(argv);
  if (u) win.loadURL(u);
});

app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(perm === 'notifications' || perm === 'clipboard-sanitized-write' || perm === 'fullscreen'));
  create();
});
app.on('window-all-closed', () => app.quit());
