const api = require("../../services/api")
const {
  safeFileName,
  toDateInputValue,
  fromDateInputValue
} = require("../../utils/format")

const typeLabels = {
  invoice: "发票",
  payment: "付款截图",
  receipt: "收据/送货单",
  other: "其他"
}

Page({
  data: {
    id: "",
    projectId: "",
    saving: false,
    ocrLoading: false,
    dateValue: toDateInputValue(Date.now()),
    typeLabels,
    form: {
      name: "",
      model: "",
      quantity: "1",
      price: "",
      totalAmount: "",
      hasInvoice: true,
      invoiceNumber: "",
      onlineLink: "",
      notes: "",
      isReimbursed: false
    },
    attachments: []
  },

  async onLoad(options) {
    const id = options.id || ""
    const projectId = options.projectId || ""
    this.setData({ id, projectId })

    if (id) {
      await this.loadExpense(id)
    }
  },

  async loadExpense(id) {
    try {
      wx.showLoading({ title: "加载中" })
      const data = await api.call("getExpense", { expenseId: id })
      const expense = data.expense
      this.setData({
        projectId: expense.projectId,
        dateValue: toDateInputValue(expense.date),
        form: {
          name: expense.name || "",
          model: expense.model || "",
          quantity: String(expense.quantity || 1),
          price: String(expense.price || ""),
          totalAmount: String(expense.totalAmount || ""),
          hasInvoice: !!expense.hasInvoice,
          invoiceNumber: expense.invoiceNumber || "",
          onlineLink: expense.onlineLink || "",
          notes: expense.notes || "",
          isReimbursed: !!expense.isReimbursed
        },
        attachments: (data.attachments || []).map(item => ({
          _id: item._id,
          fileID: item.fileID,
          fileName: item.fileName,
          size: item.size || 0,
          type: item.type || "other",
          typeText: typeLabels[item.type] || "附件",
          uploaded: true
        }))
      })
    } catch (error) {
      this.showError(error)
    } finally {
      wx.hideLoading()
    }
  },

  onFieldInput(event) {
    const field = event.currentTarget.dataset.field
    this.setData({
      [`form.${field}`]: event.detail.value
    })
  },

  onInvoiceSwitch(event) {
    this.setData({
      "form.hasInvoice": event.detail.value
    })
  },

  onReimbursedSwitch(event) {
    this.setData({ "form.isReimbursed": event.detail.value })
  },

  onDateChange(event) {
    this.setData({ dateValue: event.detail.value })
  },

  async chooseAttachment(event) {
    const type = event.currentTarget.dataset.type || "other"
    try {
      const result = await wx.chooseMedia({
        count: 9,
        mediaType: ["image"],
        sourceType: ["camera", "album"],
        sizeType: ["compressed"]
      })
      const next = result.tempFiles.map((file, index) => {
        const fallbackName = `${type}_${Date.now()}_${index}.jpg`
        return {
          localPath: file.tempFilePath,
          fileName: safeFileName(file.tempFilePath.split("/").pop() || fallbackName),
          size: file.size || 0,
          type,
          typeText: typeLabels[type] || "附件",
          uploaded: false
        }
      })
      this.setData({
        attachments: this.data.attachments.concat(next)
      })
    } catch (error) {
      if (!String(error.errMsg || "").includes("cancel")) {
        this.showError(error)
      }
    }
  },

  async previewAttachment(event) {
    const index = Number(event.currentTarget.dataset.index)
    const item = this.data.attachments[index]
    if (!item) return

    try {
      if (item.localPath) {
        wx.previewImage({ urls: [item.localPath] })
        return
      }
      const result = await api.call("getAttachmentDownloadUrl", {
        attachmentId: item._id || "",
        fileID: item.fileID || ""
      })
      const url = result.url
      if (url) {
        wx.previewImage({ urls: [url] })
      }
    } catch (error) {
      this.showError(error)
    }
  },

  removeAttachment(event) {
    const index = Number(event.currentTarget.dataset.index)
    const attachments = this.data.attachments.slice()
    attachments.splice(index, 1)
    this.setData({ attachments })
  },

  async runOcr() {
    const index = this.data.attachments.findIndex(item => item.type === "invoice" || item.type === "payment")
    if (index < 0) {
      wx.showToast({ title: "请先添加图片附件", icon: "none" })
      return
    }

    try {
      this.setData({ ocrLoading: true })
      wx.showLoading({ title: "识别中" })
      const uploaded = await this.ensureUploaded(this.data.attachments[index])
      const attachments = this.data.attachments.slice()
      attachments[index] = uploaded
      this.setData({ attachments })

      const { result } = await wx.cloud.callFunction({
        name: "ocrReceipt",
        data: { fileID: uploaded.fileID }
      })

      if (!result || !result.ok) {
        wx.showModal({
          title: "OCR 未完成",
          content: (result && result.message) || "请手动补录支出信息。",
          showCancel: false
        })
        return
      }

      const fields = result.fields || {}
      this.setData({
        "form.name": fields.name || this.data.form.name,
        "form.model": fields.model || this.data.form.model,
        "form.totalAmount": fields.totalAmount ? String(fields.totalAmount) : this.data.form.totalAmount,
        "form.invoiceNumber": fields.invoiceNumber || this.data.form.invoiceNumber,
        "form.hasInvoice": fields.invoiceNumber ? true : this.data.form.hasInvoice,
        dateValue: fields.date ? toDateInputValue(fields.date) : this.data.dateValue,
        "form.notes": fields.notes || this.data.form.notes
      })
      wx.showToast({ title: "已填充" })
    } catch (error) {
      this.showError(error)
    } finally {
      wx.hideLoading()
      this.setData({ ocrLoading: false })
    }
  },

  async saveExpense() {
    const form = this.data.form
    if (!form.name.trim()) {
      wx.showToast({ title: "请输入名称", icon: "none" })
      return
    }
    const quantity = Number(form.quantity)
    const price = Number(form.price || 0)
    const amount = Number(form.totalAmount)
    if (!Number.isSafeInteger(quantity) || quantity <= 0) {
      wx.showToast({ title: "数量必须是正整数", icon: "none" })
      return
    }
    if (!Number.isFinite(price) || price < 0) {
      wx.showToast({ title: "请输入有效单价", icon: "none" })
      return
    }
    if (!Number.isFinite(amount) || amount <= 0) {
      wx.showToast({ title: "请输入有效金额", icon: "none" })
      return
    }
    if (this.data.attachments.some(item => Number(item.size || 0) > 5 * 1024 * 1024)) {
      wx.showToast({ title: "单个附件不能超过 5MB", icon: "none" })
      return
    }
    const requiredType = form.hasInvoice ? "invoice" : "payment"
    if (!this.data.attachments.some(item => item.type === requiredType)) {
      wx.showToast({
        title: form.hasInvoice ? "请上传发票原图" : "请上传付款截图",
        icon: "none"
      })
      return
    }

    try {
      this.setData({ saving: true })
      wx.showLoading({ title: "保存中" })
      const attachments = []
      for (const item of this.data.attachments) {
        attachments.push(await this.ensureUploaded(item))
      }

      await api.call("saveExpense", {
        expenseId: this.data.id,
        projectId: this.data.projectId,
        expense: {
          name: form.name.trim(),
          model: form.model.trim(),
          quantity,
          priceCents: Math.round(price * 100),
          price,
          amountCents: Math.round(amount * 100),
          totalAmount: amount,
          date: fromDateInputValue(this.data.dateValue),
          hasInvoice: !!form.hasInvoice,
          invoiceNumber: form.hasInvoice ? form.invoiceNumber.trim() : "",
          onlineLink: form.onlineLink.trim(),
          notes: form.notes.trim(),
          isReimbursed: !!form.isReimbursed
        },
        attachments: attachments.map(item => ({
          _id: item._id || "",
          fileID: item.fileID,
          fileName: item.fileName,
          size: item.size || 0,
          type: item.type || "other"
        }))
      })

      wx.navigateBack()
    } catch (error) {
      this.showError(error)
    } finally {
      wx.hideLoading()
      this.setData({ saving: false })
    }
  },

  async ensureUploaded(item) {
    if (item.fileID) {
      return item
    }

    const app = getApp()
    const user = app.globalData.user || await app.ensureLogin()
    const cloudPath = `attachments/${user.userId}/${Date.now()}_${safeFileName(item.fileName)}`
    const result = await wx.cloud.uploadFile({
      cloudPath,
      filePath: item.localPath
    })
    await api.call("registerPendingUpload", {
      fileID: result.fileID,
      size: Number(item.size || 0)
    })

    return Object.assign({}, item, {
      fileID: result.fileID,
      localPath: "",
      typeText: typeLabels[item.type] || "附件",
      uploaded: true
    })
  },

  showError(error) {
    wx.showToast({
      title: error && error.message ? error.message : "操作失败",
      icon: "none"
    })
  }
})
