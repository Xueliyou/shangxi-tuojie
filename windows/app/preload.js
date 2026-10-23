const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('Native', {
  call: (method, params, id) => ipcRenderer.send('native-call', String(method || ''), String(params == null ? '{}' : params), String(id == null ? '' : id))
})

contextBridge.exposeInMainWorld('StateBridge', {
  load: () => ipcRenderer.sendSync('state-load'),
  save: (s) => ipcRenderer.send('state-save', String(s == null ? '' : s))
})

contextBridge.exposeInMainWorld('DownloadBridge', {
  begin: (name, mime, size) => ipcRenderer.send('blob-begin', String(name || ''), String(mime || ''), Number(size) || 0),
  chunk: (b64) => ipcRenderer.send('blob-chunk', String(b64 || '')),
  end: () => ipcRenderer.send('blob-end'),
  fail: (err) => ipcRenderer.send('blob-fail', String(err || ''))
})
