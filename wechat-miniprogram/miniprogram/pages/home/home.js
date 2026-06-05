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
    totalCount: 0
  },

  async onShow() {
    await this.bootstrap()
  },

  async bootstrap() {
    try {
      const app = getApp()
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

  async loadExpenses() {
    if (!this.data.currentProjectId) {
      this.setData({ expenses: [], totalCount: 0 })
      return
    }

    const data = await api.call("listExpenses", {
      projectId: this.data.currentProjectId,
      keyword: this.data.keyword,
      invoiceFilter: invoiceValues[this.data.invoiceIndex]
    })

    const expenses = (data.expenses || []).map(item => ({
      ...item,
      amountText: formatMoney(item.totalAmount),
      dateText: formatDate(item.date),
      invoiceText: item.hasInvoice ? (item.invoiceNumber || "有发票") : "无发票",
      attachmentCount: (item.attachments || []).length
    }))

    this.setData({
      expenses,
      spentText: formatMoney(data.stats && data.stats.spent),
      advanceText: formatMoney(data.stats && data.stats.advanceFund),
      remainingText: formatMoney(data.stats && data.stats.remaining),
      totalCount: data.stats ? data.stats.count : expenses.length
    })
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
      await api.call("updateAdvanceFund", {
        projectId: this.data.currentProjectId,
        advanceFund: Number(this.data.advanceInput || 0)
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
