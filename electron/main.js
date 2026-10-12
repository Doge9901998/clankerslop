// Desktop wrapper (Windows / Linux). Serves ./public on a fixed localhost port so the
// origin (and therefore localStorage / IndexedDB library) stays the same between launches.
const { app, BrowserWindow, session, shell } = require('electron');
const http = require('http'), fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', 'public');
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.png': 'image/png', '.webmanifest': 'application/manifest+json', '.json': 'application/json' };
const PORT = 47777;

function serve() {
  const srv = http.createServer((q, r) => {
    let u = decodeURIComponent(q.url.split('?')[0]); if (u === '/') u = '/index.html';
    const f = path.join(root, path.normalize(u));
    if (!f.startsWith(root)) { r.writeHead(403); return r.end(); }
    fs.readFile(f, (e, d) => {
      if (e) { r.writeHead(404); return r.end(); }
      r.writeHead(200, { 'Content-Type': mime[path.extname(f)] || 'application/octet-stream' }); r.end(d);
    });
  });
  return new Promise(res => {
    srv.once('error', () => srv.listen(0, '127.0.0.1', () => res(srv.address().port))); // port busy: fall back
    srv.listen(PORT, '127.0.0.1', () => res(PORT));
  });
}

app.whenReady().then(async () => {
  const port = await serve();
  session.defaultSession.setPermissionRequestHandler((_w, perm, cb) => cb(perm === 'media'));
  const win = new BrowserWindow({
    width: 560, height: 980, minWidth: 400, minHeight: 600, autoHideMenuBar: true, backgroundColor: '#161616', title: 'TP-7',
    webPreferences: { webSecurity: false, autoplayPolicy: 'no-user-gesture-required', contextIsolation: true } // webSecurity off: lets the level meter read audio from any URL
  });
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.loadURL('http://127.0.0.1:' + port + '/');
});
app.on('window-all-closed', () => app.quit());
