const config = require("./env")
const auth = require("./services/auth")

App({
  globalData: {
    user: null,
    cloudReady: false
  },

  onLaunch() {
    if (!wx.cloud) {
      wx.showModal({
        title: "初始化失败",
        content: "当前基础库不支持云开发，请升级微信或开发者工具。",
        showCancel: false
      })
      return
    }

    wx.cloud.init({
      env: config.cloudEnv || undefined,
      traceUser: true
    })
    this.globalData.cloudReady = true
  },

  async ensureLogin() {
    if (this.globalData.user) {
      return this.globalData.user
    }
    const user = await auth.login()
    this.globalData.user = user
    return user
  },

  setUser(user) {
    this.globalData.user = user
  }
})
