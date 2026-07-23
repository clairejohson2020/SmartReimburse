const ATTACHMENT_TYPES = new Set(["invoice", "payment", "receipt", "other"])
const MAX_PAGE_SIZE = 50

function toCents(value, fieldName = "金额") {
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0) {
    throw new Error(`${fieldName}无效`)
  }
  const cents = Math.round(number * 100)
  if (!Number.isSafeInteger(cents)) {
    throw new Error(`${fieldName}超出范围`)
  }
  return cents
}

function centsFrom(source, centsKey, legacyKey, fieldName) {
  if (source[centsKey] !== undefined && source[centsKey] !== null && source[centsKey] !== "") {
    const cents = Number(source[centsKey])
    if (!Number.isSafeInteger(cents) || cents < 0) {
      throw new Error(`${fieldName}无效`)
    }
    return cents
  }
  return toCents(source[legacyKey] || 0, fieldName)
}

function normalizeExpense(expense) {
  const source = expense || {}
  const name = String(source.name || "").trim()
  if (!name) throw new Error("名称不能为空")
  if (name.length > 100) throw new Error("名称不能超过 100 个字符")

  const quantity = Number(source.quantity || 1)
  if (!Number.isSafeInteger(quantity) || quantity <= 0 || quantity > 1000000) {
    throw new Error("数量必须是正整数")
  }

  const priceCents = centsFrom(source, "priceCents", "price", "单价")
  const amountCents = centsFrom(source, "amountCents", "totalAmount", "金额")
  if (amountCents <= 0) throw new Error("金额必须大于 0")

  const date = Number(source.date || Date.now())
  if (!Number.isSafeInteger(date) || date <= 0) throw new Error("日期无效")

  const hasInvoice = !!source.hasInvoice
  return {
    name,
    model: String(source.model || "").trim().slice(0, 100),
    quantity,
    priceCents,
    amountCents,
    price: priceCents / 100,
    totalAmount: amountCents / 100,
    date,
    hasInvoice,
    invoiceNumber: hasInvoice ? String(source.invoiceNumber || "").trim().slice(0, 100) : "",
    onlineLink: String(source.onlineLink || "").trim().slice(0, 2000),
    notes: String(source.notes || "").trim().slice(0, 2000),
    isReimbursed: !!source.isReimbursed
  }
}

function normalizeAttachment(item) {
  const source = item || {}
  const type = String(source.type || "other")
  if (!ATTACHMENT_TYPES.has(type)) throw new Error("附件类型无效")
  const size = Number(source.size || 0)
  if (!Number.isSafeInteger(size) || size < 0 || size > 5 * 1024 * 1024) {
    throw new Error("附件大小无效")
  }
  return {
    _id: source._id ? String(source._id) : "",
    type,
    fileID: String(source.fileID || ""),
    fileName: String(source.fileName || "attachment").trim().slice(0, 255) || "attachment",
    size
  }
}

function validateRequiredAttachments(hasInvoice, attachments) {
  const types = new Set(attachments.map(item => item.type))
  if (hasInvoice && !types.has("invoice")) {
    throw new Error("有发票记录需要上传发票原图")
  }
  if (!hasInvoice && !types.has("payment")) {
    throw new Error("无发票记录必须上传付款截图")
  }
}

function isOwnedAttachmentFile(fileID, userId) {
  if (!fileID || !userId) return false
  const value = String(fileID)
  const marker = `/attachments/${userId}/`
  const markerIndex = value.indexOf(marker)
  const suffix = markerIndex >= 0 ? value.slice(markerIndex + marker.length) : ""
  return value.startsWith("cloud://") && markerIndex > "cloud://".length && !!suffix && !suffix.includes("..")
}

function normalizePage(event) {
  const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, Number(event.pageSize || 20)))
  const page = Math.max(1, Number(event.page || 1))
  if (!Number.isSafeInteger(page) || !Number.isSafeInteger(pageSize)) {
    throw new Error("分页参数无效")
  }
  return { page, pageSize, offset: (page - 1) * pageSize }
}

function prepareAttachmentReplacement(userId, expenseId, current, requested) {
  const currentById = new Map(current.map(item => [item._id, item]))
  const seen = new Set()
  const next = requested.map(item => {
    if (item._id) {
      if (seen.has(item._id)) throw new Error("附件重复")
      seen.add(item._id)
      const owned = currentById.get(item._id)
      if (!owned || owned.userId !== userId || owned.expenseId !== expenseId) {
        throw new Error("附件不存在或无权修改")
      }
      if (item.fileID !== owned.fileID) throw new Error("不能替换已有附件文件标识")
      return item
    }
    if (!isOwnedAttachmentFile(item.fileID, userId)) throw new Error("附件文件不属于当前用户")
    return item
  })
  return {
    next,
    removed: current.filter(item => !seen.has(item._id))
  }
}

module.exports = {
  ATTACHMENT_TYPES,
  MAX_PAGE_SIZE,
  isOwnedAttachmentFile,
  normalizeAttachment,
  normalizeExpense,
  normalizePage,
  prepareAttachmentReplacement,
  toCents,
  validateRequiredAttachments
}
