const api = require("../../services/api")
const auth = require("../../services/auth")
const { formatMoney, formatDate } = require("../../utils/format")

const invoiceValues = ["all", "with", "without"]

Page({
  data: {
    loading: true,
    exporting: false,
    user: null,
    phoneNumber: "",
    projects: [],
    currentProjectId: "",
    currentProject: null,
    currentProjectName: "默认项目",
    expenses: [],
    keyword: "",
    invoiceIndex: 0,
    invoiceLabel: "全部",
    invoiceLabels: ["全部", "有发票", "无发票"],
    showProjectPanel: false,
    newProjectName: "",
    renameProjectId: "",
    renameProjectName: "",
    advanceInput: "0",
    spentText: "¥0.00",
    advanceText: "¥0.00",
    remainingText: "¥0.00",
    totalCount: 0,
    pairingCode: "",
    page: 1,
    hasMore: false,
    loadingMore: false
  },

  async onShow() {
    await this.bootstrap()
  },

  async bootstrap() {
    try {
      const app = getApp()
      if (!app.globalData.cloudReady) {
        this.setData({
          projects: [],
          expenses: [],
          currentProjectId: "",
          currentProject: null,
          currentProjectName: "请配置云开发",
          spentText: formatMoney(0),
          advanceText: formatMoney(0),
          remainingText: formatMoney(0),
          totalCount: 0
        })
        wx.showModal({
          title: "云开发未配置",
          content: "请先填写 miniprogram/env.js 中的 cloudEnv，然后重新编译。",
          showCancel: false
        })
        return
      }
      const user = await app.ensureLogin()
      this.setData({
        user,
        phoneNumber: user.phoneNumber || ""
      })
      await this.loadProjects()
    } catch (error) {
      this.showError(error)
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadProjects() {
    const data = await api.call("listProjects")
    const projects = data.projects || []
    const storedId = wx.getStorageSync("smartreimburse_current_project")
    const currentProject = projects.find(item => item._id === storedId) || projects[0] || null
    const currentProjectId = currentProject ? currentProject._id : ""

    if (currentProjectId) {
      wx.setStorageSync("smartreimburse_current_project", currentProjectId)
    }

    this.setData({
      projects,
      currentProjectId,
      currentProject,
      currentProjectName: currentProject ? currentProject.name : "默认项目",
      advanceInput: currentProject ? String(currentProject.advanceFund || 0) : "0"
    })
    await this.loadExpenses()
  },

  async loadExpenses(reset = true) {
    if (!this.data.currentProjectId) {
      this.setData({ expenses: [], totalCount: 0 })
      return
    }

    const page = reset ? 1 : this.data.page + 1
    const data = await api.call("listExpenses", {
      projectId: this.data.currentProjectId,
      keyword: this.data.keyword,
      invoiceFilter: invoiceValues[this.data.invoiceIndex],
      page,
      pageSize: 20
    })

    const expenses = (data.expenses || []).map(item => ({
      ...item,
      amountText: formatMoney(item.totalAmount),
      dateText: formatDate(item.date),
      invoiceText: item.hasInvoice ? (item.invoiceNumber || "有发票") : "无发票",
      attachmentCount: (item.attachments || []).length
    }))

    this.setData({
      expenses: reset ? expenses : this.data.expenses.concat(expenses),
      page,
      hasMore: !!(data.pagination && data.pagination.hasMore),
      spentText: formatMoney(data.stats && data.stats.spent),
      advanceText: formatMoney(data.stats && data.stats.advanceFund),
      remainingText: formatMoney(data.stats && data.stats.remaining),
      totalCount: data.stats ? data.stats.count : expenses.length
    })
  },

  async onReachBottom() {
    if (!this.data.hasMore || this.data.loadingMore) return
    try {
      this.setData({ loadingMore: true })
      await this.loadExpenses(false)
    } catch (error) {
      this.showError(error)
    } finally {
      this.setData({ loadingMore: false })
    }
  },

  onPairingCodeInput(event) {
    this.setData({ pairingCode: String(event.detail.value || "").replace(/\D/g, "").slice(0, 6) })
  },

  async approveDevicePair() {
    if (!/^\d{6}$/.test(this.data.pairingCode)) {
      wx.showToast({ title: "请输入 6 位配对码", icon: "none" })
      return
    }
    try {
      wx.showLoading({ title: "正在绑定" })
      const result = await api.call("approveDevicePair", { code: this.data.pairingCode })
      this.setData({ pairingCode: "" })
      wx.showModal({
        title: "设备已绑定",
        content: `${result.deviceName || "Android 设备"}现在可以同步当前账号数据。`,
        showCancel: false
      })
    } catch (error) {
      this.showError(error)
    } finally {
      wx.hideLoading()
    }
  },

  async onGetPhoneNumber(event) {
    if (!event.detail || event.detail.errMsg !== "getPhoneNumber:ok") {
      wx.showToast({ title: "未授权手机号", icon: "none" })
      return
    }

    try {
      wx.showLoading({ title: "绑定中" })
      const user = await auth.bindPhone(event.detail.code)
      getApp().setUser(user)
      this.setData({
        user,
        phoneNumber: user.phoneNumber || ""
      })
      await this.loadProjects()
      wx.showToast({ title: "已绑定" })
    } catch (error) {
      this.showError(error)
    } finally {
      wx.hideLoading()
    }
  },

  onKeywordInput(event) {
    this.setData({ keyword: event.detail.value })
  },

  async onSearchConfirm() {
    await this.loadExpenses()
  },

  async onInvoiceChange(event) {
    const invoiceIndex = Number(event.detail.value || 0)
    this.setData({
      invoiceIndex,
      invoiceLabel: this.data.invoiceLabels[invoiceIndex]
    })
    await this.loadExpenses()
  },

  openProjectPanel() {
    this.setData({ showProjectPanel: true })
  },

  closeProjectPanel() {
    this.setData({ showProjectPanel: false, renameProjectId: "", renameProjectName: "" })
  },

  async selectProject(event) {
    const projectId = event.currentTarget.dataset.id
    const currentProject = this.data.projects.find(item => item._id === projectId)
    wx.setStorageSync("smartreimburse_current_project", projectId)
    this.setData({
      currentProjectId: projectId,
      currentProject,
      currentProjectName: currentProject ? currentProject.name : "默认项目",
      advanceInput: currentProject ? String(currentProject.advanceFund || 0) : "0",
      showProjectPanel: false
    })
    await this.loadExpenses()
  },

  onNewProjectName(event) {
    this.setData({ newProjectName: event.detail.value })
  },

  async createProject() {
    const name = this.data.newProjectName.trim()
    if (!name) {
      wx.showToast({ title: "请输入项目名称", icon: "none" })
      return
    }

    try {
      const data = await api.call("saveProject", { name })
      this.setData({ newProjectName: "" })
      wx.setStorageSync("smartreimburse_current_project", data.projectId)
      await this.loadProjects()
    } catch (error) {
      this.showError(error)
    }
  },

  beginRename(event) {
    const projectId = event.currentTarget.dataset.id
    const project = this.data.projects.find(item => item._id === projectId)
    this.setData({
      renameProjectId: projectId,
      renameProjectName: project ? project.name : ""
    })
  },

  onRenameProjectName(event) {
    this.setData({ renameProjectName: event.detail.value })
  },

  async renameProject() {
    const name = this.data.renameProjectName.trim()
    if (!name || !this.data.renameProjectId) {
      wx.showToast({ title: "请输入项目名称", icon: "none" })
      return
    }

    try {
      await api.call("saveProject", {
        projectId: this.data.renameProjectId,
        name
      })
      this.setData({ renameProjectId: "", renameProjectName: "" })
      await this.loadProjects()
    } catch (error) {
      this.showError(error)
    }
  },

  onAdvanceInput(event) {
    this.setData({ advanceInput: event.detail.value })
  },

  async updateAdvanceFund() {
    try {
      const amount = Number(this.data.advanceInput)
      if (!Number.isFinite(amount) || amount < 0) {
        wx.showToast({ title: "请输入有效备用金", icon: "none" })
        return
      }
      await api.call("updateAdvanceFund", {
        projectId: this.data.currentProjectId,
        advanceFundCents: Math.round(amount * 100),
        advanceFund: amount
      })
      await this.loadProjects()
      wx.showToast({ title: "已更新" })
    } catch (error) {
      this.showError(error)
    }
  },

  deleteProject(event) {
    const projectId = event.currentTarget.dataset.id
    const project = this.data.projects.find(item => item._id === projectId)
    if (!project) return

    wx.showModal({
      title: "删除项目",
      content: `删除“${project.name}”会同步删除支出和附件，是否继续？`,
      confirmText: "删除",
      confirmColor: "#dc2626",
      success: async result => {
        if (!result.confirm) return
        try {
          await api.call("deleteProject", { projectId })
          if (projectId === this.data.currentProjectId) {
            wx.removeStorageSync("smartreimburse_current_project")
          }
          await this.loadProjects()
        } catch (error) {
          this.showError(error)
        }
      }
    })
  },

  addExpense() {
    if (!this.data.currentProjectId) return
    wx.navigateTo({
      url: `/pages/expense-form/expense-form?projectId=${this.data.currentProjectId}`
    })
  },

  openExpense(event) {
    const expenseId = event.currentTarget.dataset.id
    wx.navigateTo({
      url: `/pages/expense-detail/expense-detail?id=${expenseId}`
    })
  },

  async exportExcel() {
    if (!this.data.currentProjectId || this.data.exporting) return

    try {
      this.setData({ exporting: true })
      wx.showLoading({ title: "导出中" })
      const { result } = await wx.cloud.callFunction({
        name: "exportExcel",
        data: { projectId: this.data.currentProjectId }
      })
      if (!result || !result.ok) {
        throw new Error((result && result.message) || "导出失败")
      }

      const download = await wx.cloud.downloadFile({ fileID: result.fileID })
      wx.hideLoading()

      if (wx.shareFileMessage) {
        await wx.shareFileMessage({
          filePath: download.tempFilePath,
          fileName: result.fileName
        })
      } else {
        await wx.openDocument({
          filePath: download.tempFilePath,
          fileType: "xlsx",
          showMenu: true
        })
      }
    } catch (error) {
      wx.hideLoading()
      this.showError(error)
    } finally {
      this.setData({ exporting: false })
    }
  },

  showError(error) {
    wx.showToast({
      title: error && error.message ? error.message : "操作失败",
      icon: "none"
    })
  },

  noop() {
  }
})
