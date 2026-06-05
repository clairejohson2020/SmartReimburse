const config = require("./env")
const auth = require("./services/auth")

App({
  globalData: {
    user: null,
    cloudReady: false,
    cloudEnv: config.cloudEnv || ""
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

    if (!config.cloudEnv) {
      this.globalData.cloudReady = false
      console.warn("SmartReimburse cloudEnv is empty. Configure miniprogram/env.js before using cloud data.")
      return
    }

    try {
      wx.cloud.init({
        env: config.cloudEnv,
        traceUser: true
      })
      this.globalData.cloudReady = true
    } catch (error) {
      this.globalData.cloudReady = false
      console.error("wx.cloud.init failed", error)
    }
  },

  async ensureLogin() {
    if (!this.globalData.cloudReady) {
      throw new Error("请先配置云开发环境 ID")
    }
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
