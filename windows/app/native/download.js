const fs = require('fs')
const path = require('path')

const UA = 'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154'

function safeName(s) {
  return String(s == null ? '' : s)
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 120) || 'song'
}

function extOf(type, url) {
  const t = String(type || '').toLowerCase()
  if (t.includes('flac')) return 'flac'
  if (t.includes('mp3')) return 'mp3'
  if (t.includes('m4a') || t.includes('aac')) return 'm4a'
  const m = /\.([a-z0-9]{2,4})(?:\?|$)/i.exec(String(url || ''))
  return m ? m[1].toLowerCase() : 'mp3'
}

function coverUrl(picUrl, size) {
  if (!picUrl) return ''
  if (!/^https?:/i.test(picUrl)) return ''
  return picUrl.replace(/^http:/, 'https:') + '?param=' + size + 'y' + size
}

async function fetchBytes(url, max) {
  const res = await fetch(url, { headers: { 'User-Agent': UA, Referer: 'https://music.163.com/' } })
  if (!res.ok) throw new Error('HTTP ' + res.status)
  const buf = Buffer.from(await res.arrayBuffer())
  if (buf.length > max) throw new Error('图片过大')
  return buf
}

function uniquePath(p) {
  if (!fs.existsSync(p)) return p
  const ext = path.extname(p)
  const base = p.slice(0, -ext.length)
  for (let i = 1; i < 1000; i++) {
    const q = base + ' (' + i + ')' + ext
    if (!fs.existsSync(q)) return q
  }
  return p
}

module.exports = function createDownloads({ config, sendDl, app }) {
  let seq = 0
  const tasks = new Map()

  function emit(taskId, state, percent, done, total, message, filePath, name, ext) {
    const o = { taskId, state, percent, done, total, message: message || '' }
    if (filePath) o.path = filePath
    if (name) o.name = name
    if (ext) o.ext = ext
    try { sendDl(o) } catch (e) {}
  }

  async function run(taskId, task, id, level, p) {
    const { NeteaseApi } = require('./netease')
    const tags = require('./tags')
    const api = new NeteaseApi(config.getCookie())

    let detail = null
    try { detail = await api.songDetail(id) } catch (e) {}
    const title = (detail && detail.name) || p.name || 'song'
    const artist = (detail && detail.artists) || p.artist || ''
    const album = (detail && detail.album) || ''
    const picUrl = (detail && detail.picUrl) || ''
    const duration = (detail && detail.duration) || 0

    emit(taskId, 1, 0, 0, 0, '获取歌词…', null, title, null)
    let merged = ''
    try {
      const ly = await api.lyric(id)
      merged = tags.mergeTranslation(ly.lyric || '', ly.tlyric || '')
    } catch (e) {}

    let cover = null
    try {
      const cu = coverUrl(picUrl, 500)
      if (cu) {
        const img = await fetchBytes(cu, 3 * 1024 * 1024)
        if (img.length > 1024) cover = img
      }
    } catch (e) {}

    emit(taskId, 1, 0, 0, 0, '解析音质…', null, title, null)
    const info = await api.songUrl(id, level)
    const url = info && info.url
    if (!url) throw new Error('没有拿到播放地址（账号无权限或版权限制）')
    const ext = extOf(info.type || 'mp3', url)
    const fileName = safeName(artist ? title + ' - ' + artist : title)
    const dir = path.join(app.getPath('music'), config.getDir())
    fs.mkdirSync(dir, { recursive: true })
    const tmp = path.join(app.getPath('temp'), 'np_' + Date.now() + '_' + Math.random().toString(16).slice(2) + '.' + ext)

    const ctrl = new AbortController()
    task.controller = ctrl
    const res = await fetch(url, { headers: { 'User-Agent': UA, Referer: 'https://music.163.com/' }, signal: ctrl.signal })
    if (!res.ok) throw new Error('服务器返回 HTTP ' + res.status)
    const total = Number(res.headers.get('content-length') || 0)
    const out = fs.createWriteStream(tmp)
    let done = 0
    let last = 0
    for await (const chunk of res.body) {
      if (task.canceled) { ctrl.abort(); throw new Error('已取消') }
      done += chunk.length
      out.write(chunk)
      const now = Date.now()
      if (now - last > 250) {
        last = now
        emit(taskId, 2, total > 0 ? Math.min(88, Math.floor(done * 88 / total)) : 0, done, total, '', null, null, null)
      }
    }
    await new Promise((resolve, reject) => out.end(err => err ? reject(err) : resolve()))

    if (task.canceled) throw new Error('已取消')

    const meta = {
      title, artist, album, lyric: merged, durationMs: duration,
      cover, coverW: 0, coverH: 0, coverMime: 'image/jpeg', track: 0
    }
    emit(taskId, 1, 100, done, total, '写入封面与歌词…', null, fileName, ext)
    try { tags.write(tmp, ext, meta) } catch (e) {}

    emit(taskId, 1, 100, done, total, '保存到音乐库…', null, fileName, ext)
    const finalPath = uniquePath(path.join(dir, fileName + '.' + ext))
    try { fs.renameSync(tmp, finalPath) } catch (e) { fs.copyFileSync(tmp, finalPath); try { fs.unlinkSync(tmp) } catch (e2) {} }

    if (merged && merged.trim()) {
      try {
        const lrcPath = finalPath.replace(/\.[^.]+$/, '.lrc')
        fs.writeFileSync(lrcPath, merged, 'utf8')
      } catch (e) {}
    }

    emit(taskId, 3, 100, done, total, '已完成', finalPath, fileName, ext)
    tasks.delete(taskId)
  }

  return {
    start(p) {
      const id = Number(p.id)
      const level = p.level || config.getLevel()
      const fallbackName = safeName(p.name || 'song')
      const taskId = 't' + (++seq)
      const task = { canceled: false, controller: null }
      tasks.set(taskId, task)
      emit(taskId, 1, 0, 0, 0, '准备中…', null, fallbackName, null)
      setTimeout(() => {
        run(taskId, task, id, level, p).catch(err => {
          task.controller = null
          if (task.canceled) emit(taskId, 5, 0, 0, 0, '已取消', null, null, null)
          else emit(taskId, 4, 0, 0, 0, (err && err.message) || String(err), null, null, null)
          tasks.delete(taskId)
        })
      }, 0)
      return taskId
    },
    cancel(taskId) {
      const t = tasks.get(taskId)
      if (!t) return
      t.canceled = true
      try { t.controller && t.controller.abort() } catch (e) {}
    }
  }
}
