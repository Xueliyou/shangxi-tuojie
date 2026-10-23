const fs = require('fs')
const path = require('path')

module.exports = function createConfig(baseDir) {
  const file = path.join(baseDir, 'config.json')
  let data = {}
  try { data = JSON.parse(fs.readFileSync(file, 'utf8')) || {} } catch (e) {}

  function save() {
    try {
      fs.mkdirSync(baseDir, { recursive: true })
      fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf8')
    } catch (e) {}
  }

  return {
    getCookie() { return String(data.cookie || '') },
    setCookie(v) { data.cookie = String(v == null ? '' : v); save() },
    getLevel() { return String(data.level || 'exhigh') },
    setLevel(v) { data.level = String(v || 'exhigh'); save() },
    getDir() { return String(data.dir || 'NeteaseMusic') },
    setDir(v) { data.dir = (String(v == null ? '' : v).trim() || 'NeteaseMusic'); save() },
    getWebState() { return String(data.webState || '') },
    setWebState(v) { data.webState = String(v == null ? '' : v); save() },
    json() {
      const cookie = this.getCookie()
      const m = /MUSIC_U=([^;]+)/.exec(cookie)
      const musicU = m ? m[1] : ''
      return {
        level: this.getLevel(),
        dir: this.getDir(),
        customCookie: !!cookie,
        musicU: musicU,
        musicUMasked: musicU.length > 12 ? musicU.slice(0, 6) + '…' + musicU.slice(-4) : musicU
      }
    }
  }
}
