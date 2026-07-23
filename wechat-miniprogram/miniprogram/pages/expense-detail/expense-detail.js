const api = require("../../services/api")
const { formatMoney, formatDate } = require("../../utils/format")

const typeLabels = {
  invoice: "发票",
  payment: "付款截图",
  receipt: "收据/送货单",
  other: "其他"
}

Page({
  data: {
    id: "",
    loading: true,
    expense: null,
    attachments: []
  },

  async onLoad(options) {
    this.setData({ id: options.id || "" })
  },

  async onShow() {
    if (this.data.id) {
      await this.loadExpense()
    }
  },

  async loadExpense() {
    try {
      const data = await api.call("getExpense", { expenseId: this.data.id })
      const expense = data.expense
      this.setData({
        expense: {
          ...expense,
          amountText: formatMoney(expense.totalAmount),
          priceText: formatMoney(expense.price),
          dateText: formatDate(expense.date),
          invoiceText: expense.hasInvoice ? (expense.invoiceNumber || "有发票") : "无发票",
          reimbursedText: expense.isReimbursed ? "已报销" : "未报销"
        },
        attachments: (data.attachments || []).map(item => ({
          ...item,
          typeText: typeLabels[item.type] || "附件"
        }))
      })
    } catch (error) {
      this.showError(error)
    } finally {
      this.setData({ loading: false })
    }
  },

  editExpense() {
    wx.navigateTo({
      url: `/pages/expense-form/expense-form?id=${this.data.id}`
    })
  },

  deleteExpense() {
    wx.showModal({
      title: "删除支出",
      content: "删除后会同步清理附件文件，是否继续？",
      confirmText: "删除",
      confirmColor: "#dc2626",
      success: async result => {
        if (!result.confirm) return
        try {
          await api.call("deleteExpense", { expenseId: this.data.id })
          wx.navigateBack()
        } catch (error) {
          this.showError(error)
        }
      }
    })
  },

  async previewAttachment(event) {
    const index = Number(event.currentTarget.dataset.index)
    const attachment = this.data.attachments[index]
    if (!attachment) return

    try {
      const result = await api.call("getAttachmentDownloadUrl", { attachmentId: attachment._id })
      const url = result.url
      if (url) {
        wx.previewImage({ urls: [url] })
      }
    } catch (error) {
      this.showError(error)
    }
  },

  showError(error) {
    wx.showToast({
      title: error && error.message ? error.message : "操作失败",
      icon: "none"
    })
  }
})
