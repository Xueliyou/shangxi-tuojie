const { app, BrowserWindow, ipcMain, shell, clipboard, Menu, dialog } = require('electron')
const path = require('path')
const fs = require('fs')
const createConfig = require('./native/config')
const createBridge = require('./native/bridge')

let win = null
let config = null
let bridge = null
let blob = null

function sendJs(js) {
  if (!win || win.isDestroyed()) return
  win.webContents.executeJavaScript(js).catch(() => {})
}

function sendCb(id, ok, payload) {
  sendJs('window.__cb(' + JSON.stringify(String(id || '')) + ',' + (ok ? 'true' : 'false') + ',' +
    JSON.stringify(String(payload == null ? '' : payload)) + ')')
}

function sendDl(payload) {
  sendJs('window.__dl(' + JSON.stringify(JSON.stringify(payload)) + ')')
}

function createWindow() {
  win = new BrowserWindow({
    width: 480,
    height: 940,
    minWidth: 380,
    minHeight: 640,
    title: '熵析拓解',
    backgroundColor: '#FFFFFF',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'ui', 'app_icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      spellcheck: false
    }
  })
  Menu.setApplicationMenu(null)
  win.loadFile(path.join(__dirname, 'ui', 'index.html'))

  if (process.env.DSH_SMOKE) {
    win.webContents.on('console-message', (e, level, message) => {
      try { console.log('[renderer]', message) } catch (err) {}
    })
    win.webContents.on('did-finish-load', () => {
      setTimeout(async () => {
        try {
          const img = await win.webContents.capturePage()
          fs.writeFileSync(process.env.DSH_SMOKE, img.toPNG())
          console.log('[smoke] screenshot saved')
        } catch (err) { console.log('[smoke] capture failed', err.message) }
        app.quit()
      }, 12000)
    })
  }

  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('file://')) {
      e.preventDefault()
      if (/^https?:/i.test(url)) shell.openExternal(url)
    }
  })
  win.on('closed', () => { win = null })
}

app.commandLine.appendSwitch('allow-running-insecure-content')
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')

const one = app.requestSingleInstanceLock()
if (!one) app.quit()

app.on('second-instance', () => {
  if (win) { if (win.isMinimized()) win.restore(); win.focus() }
})

app.whenReady().then(() => {
  app.setAppUserModelId('com.whale.netease.desktop')
  config = createConfig(app.getPath('userData'))
  bridge = createBridge({ config, sendDl, app })

  ipcMain.on('native-call', (e, method, paramsJson, id) => {
    let params = {}
    try { params = JSON.parse(paramsJson || '{}') } catch (err) { params = {} }
    Promise.resolve()
      .then(() => bridge.dispatch(method, params))
      .then(out => sendCb(id, true, out == null ? 'null' : out))
      .catch(err => sendCb(id, false, (err && err.message) ? err.message : String(err)))
  })

  ipcMain.on('state-load', (e) => { e.returnValue = config.getWebState() })
  ipcMain.on('state-save', (e, s) => { config.setWebState(s) })

  ipcMain.on('blob-begin', (e, name, mime, size) => {
    try {
      const dir = path.join(app.getPath('downloads'))
      fs.mkdirSync(dir, { recursive: true })
      const safe = String(name || 'download').replace(/[\\/:*?"<>|]/g, '_') || 'download'
      const file = path.join(dir, safe)
      blob = { file, fd: fs.openSync(file, 'w') }
    } catch (err) { blob = null }
  })
  ipcMain.on('blob-chunk', (e, b64) => {
    if (!blob) return
    try { fs.writeSync(blob.fd, Buffer.from(b64, 'base64')) } catch (err) {}
  })
  ipcMain.on('blob-end', () => {
    if (!blob) return
    try { fs.closeSync(blob.fd) } catch (err) {}
    const f = blob.file
    blob = null
    if (win) dialog.showMessageBox(win, { type: 'info', title: '下载完成', message: '已保存到：\n' + f })
  })
  ipcMain.on('blob-fail', (e, msg) => {
    if (!blob) return
    try { fs.closeSync(blob.fd) } catch (err) {}
    try { fs.unlinkSync(blob.file) } catch (err) {}
    blob = null
  })

  createWindow()

  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
})

app.on('window-all-closed', () => { app.quit() })
