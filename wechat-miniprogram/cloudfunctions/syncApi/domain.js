const ATTACHMENT_TYPES = new Set(["invoice", "payment", "receipt", "other"])

function centsFrom(source, centsKey, legacyKey, label) {
  if (source[centsKey] !== undefined && source[centsKey] !== null && source[centsKey] !== "") {
    const cents = Number(source[centsKey])
    if (!Number.isSafeInteger(cents) || cents < 0) throw new Error(`${label}无效`)
    return cents
  }
  const value = Number(source[legacyKey] || 0)
  const cents = Math.round(value * 100)
  if (!Number.isFinite(value) || !Number.isSafeInteger(cents) || cents < 0) throw new Error(`${label}无效`)
  return cents
}

function normalizeExpense(source = {}) {
  const name = String(source.name || "").trim()
  if (!name || name.length > 100) throw new Error("名称不能为空且不能超过 100 个字符")
  const quantity = Number(source.quantity || 1)
  if (!Number.isSafeInteger(quantity) || quantity <= 0 || quantity > 1000000) throw new Error("数量必须是正整数")
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

function normalizeAttachments(items, userId) {
  return (items || []).map(item => {
    const type = String(item.type || "other")
    const fileID = String(item.fileID || "")
    if (!ATTACHMENT_TYPES.has(type)) throw new Error("附件类型无效")
    const marker = `/attachments/${userId}/`
    const markerIndex = fileID.indexOf(marker)
    const suffix = markerIndex >= 0 ? fileID.slice(markerIndex + marker.length) : ""
    if (!fileID.startsWith("cloud://") || markerIndex <= "cloud://".length || !suffix || suffix.includes("..")) {
      throw new Error("附件文件不属于当前用户")
    }
    const size = Number(item.size || 0)
    if (!Number.isSafeInteger(size) || size < 0 || size > 5 * 1024 * 1024) throw new Error("附件大小无效")
    return {
      _id: item._id ? String(item._id) : "",
      type,
      fileID,
      fileName: String(item.fileName || "attachment").trim().slice(0, 255) || "attachment",
      size
    }
  })
}

function validateRequiredAttachments(hasInvoice, attachments) {
  const types = new Set(attachments.map(item => item.type))
  if (hasInvoice && !types.has("invoice")) throw new Error("有发票记录需要上传发票原图")
  if (!hasInvoice && !types.has("payment")) throw new Error("无发票记录必须上传付款截图")
}

module.exports = { centsFrom, normalizeAttachments, normalizeExpense, validateRequiredAttachments }
