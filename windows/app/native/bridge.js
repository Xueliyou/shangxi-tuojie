const { shell, clipboard, app } = require('electron')

const LEVELS = [
  { key: 'standard', name: '标准 128K' },
  { key: 'exhigh', name: '极高 320K' },
  { key: 'lossless', name: '无损 FLAC' },
  { key: 'hires', name: 'Hi-Res' },
  { key: 'jyeffect', name: '高清环绕声' },
  { key: 'sky', name: '沉浸环绕声' },
  { key: 'jymaster', name: '超清母带' },
  { key: 'dolby', name: '杜比全景声' }
]

module.exports = function createBridge({ config, sendDl, app }) {
  const downloads = require('./download')({ config, sendDl, app })
  const json = v => JSON.stringify(v == null ? {} : v)

  function api() {
    const { NeteaseApi } = require('./netease')
    return new NeteaseApi(config.getCookie())
  }

  const routes = {
    async search(p) { return json(await api().search(p.kw, p.limit || 100)) },
    async song(p) { return json(await api().songDetail(Number(p.id))) },
    async url(p) { return json(await api().songUrl(Number(p.id), p.level || config.getLevel())) },
    async probe(p) {
      const ids = Array.isArray(p.ids) ? p.ids.map(Number) : []
      if (!ids.length) return '[]'
      return json(await api().probeLevels(ids))
    },
    async toplist(p) { return json(await api().playlistTop(Number(p.id), p.limit || 3)) },
    async audioInfo(p) { return json(await api().audioHeader(p.url || '')) },
    async lyric(p) { return json(await api().lyric(Number(p.id))) },
    async playlist(p) { return json(await api().playlist(Number(p.id))) },
    async album(p) { return json(await api().album(Number(p.id))) },
    async parse(p) {
      const link = api().parseLink(p.text || '')
      if (!link) throw new Error('没有识别出链接里的歌曲/歌单/专辑 ID')
      return json({ type: link.type, id: link.id })
    },

    async getConfig() { return json(config.json()) },
    async setConfig(p) {
      if (Object.prototype.hasOwnProperty.call(p, 'cookie')) config.setCookie(p.cookie)
      if (Object.prototype.hasOwnProperty.call(p, 'level')) config.setLevel(p.level)
      if (Object.prototype.hasOwnProperty.call(p, 'dir')) config.setDir(p.dir)
      return json(config.json())
    },
    async levels() { return json(LEVELS) },
    async testCookie() { return json(await api().testCookie(config.getCookie())) },

    async qrCreate() {
      return json(await api().qrCreate())
    },
    async qrCheck(p) {
      return json(await api().qrCheck(String(p.key || '').trim()))
    },
    async sendLoginCode(p) {
      const phone = String(p.phone || '').trim()
      if (!phone) throw new Error('请先填写手机号')
      return json(await api().sendLoginCode(phone))
    },
    async loginByCode(p) {
      const phone = String(p.phone || '').trim()
      const code = String(p.code || '').trim()
      if (!phone) throw new Error('请先填写手机号')
      if (!code) throw new Error('请先填写验证码')
      return json(await api().loginByCode(phone, code))
    },

    async download(p) { return json({ taskId: downloads.start(p) }) },
    async cancel(p) { downloads.cancel(p.taskId); return json({ canceled: true }) },

    async getShare() { return json({ text: '' }) },
    async openUrl(p) {
      const url = String(p.url || '')
      if (!url) throw new Error('缺少链接')
      if (/^https?:/i.test(url)) shell.openExternal(url)
      return json({})
    },
    async copyText(p) {
      clipboard.writeText(String(p.text || ''))
      return json({})
    },
    async toast() { return json({}) },
    async exit() { setTimeout(() => app.quit(), 50); return json({}) },
    async setBars() { return json({}) },
    async mediaUpdate() { return json({}) },
    async mediaStop() { return json({}) },
    async isInstalled() { return json({ installed: false, pkg: '' }) },
    async appIcon() { return json({ ok: false, reason: 'not-installed' }) },
    async openApp() { return json({ ok: false, reason: 'not-installed' }) },
    async lrcPerm() { return json({ need: false, granted: true }) },
    async requestLrcPerm() { return json({ ok: true }) }
  }

  return {
    async dispatch(method, params) {
      const fn = routes[method]
      if (!fn) throw new Error('未知方法：' + method)
      return fn(params || {})
    }
  }
}
