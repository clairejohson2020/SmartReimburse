const cloud = require("wx-server-sdk")
const {
  isOwnedAttachmentFile,
  normalizeAttachment,
  normalizeExpense,
  normalizePage,
  prepareAttachmentReplacement,
  toCents,
  validateRequiredAttachments
} = require("./domain")

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })
const db = cloud.database()

exports.main = async event => {
  const startedAt = Date.now()
  const requestId = cloud.getWXContext().REQUESTID || `req_${startedAt}`
  let userId = ""
  try {
    const user = await requireUser()
    userId = user.userId
    await ensureDefaultProject(userId)
    const data = await dispatch(userId, event || {})
    logEvent("info", "data_api_success", { requestId, userId, action: event.action, durationMs: Date.now() - startedAt })
    return ok(data)
  } catch (error) {
    logEvent("error", "data_api_failure", {
      requestId,
      userId,
      action: event && event.action,
      durationMs: Date.now() - startedAt,
      error: error.message || "unknown"
    })
    return { ok: false, message: publicErrorMessage(error) }
  }
}

async function dispatch(userId, event) {
  switch (event.action) {
    case "listProjects": return listProjects(userId)
    case "saveProject": return saveProject(userId, event)
    case "deleteProject": return deleteProject(userId, event.projectId)
    case "updateAdvanceFund": return updateAdvanceFund(userId, event)
    case "listExpenses": return listExpenses(userId, event)
    case "getExpense": return getExpense(userId, event.expenseId)
    case "saveExpense": return saveExpense(userId, event)
    case "deleteExpense": return deleteExpense(userId, event.expenseId)
    case "registerPendingUpload": return registerPendingUpload(userId, event)
    case "getAttachmentDownloadUrl": return getAttachmentDownloadUrl(userId, event)
    case "approveDevicePair": return approveDevicePair(userId, event)
    default: throw new Error("未知操作")
  }
}

function ok(data) {
  return { ok: true, data }
}

function publicErrorMessage(error) {
  const message = String(error && error.message || "操作失败")
  return message.length <= 120 ? message : "操作失败"
}

function logEvent(level, name, fields) {
  const payload = JSON.stringify(Object.assign({ name, timestamp: Date.now() }, fields))
  if (level === "error") console.error(payload)
  else console.log(payload)
}

async function requireUser() {
  const openid = cloud.getWXContext().OPENID
  if (!openid) throw new Error("未登录")
  const mappingResult = await db.collection("user_openids").where({ openid }).limit(1).get()
  if (mappingResult.data.length === 0) throw new Error("账号未初始化")
  const userResult = await db.collection("users")
    .where({ userId: mappingResult.data[0].userId })
    .limit(1)
    .get()
  if (userResult.data.length === 0) throw new Error("账号不存在")
  return userResult.data[0]
}

async function ensureDefaultProject(userId) {
  const countResult = await db.collection("projects").where({ userId }).count()
  if (countResult.total > 0) return
  await db.runTransaction(async transaction => {
    const latest = await transaction.collection("projects").where({ userId }).limit(1).get()
    if (latest.data.length > 0) return
    const now = db.serverDate()
    await transaction.collection("projects").add({
      data: {
        userId,
        name: "默认项目",
        advanceFund: 0,
        advanceFundCents: 0,
        expenseCount: 0,
        spentCents: 0,
        version: 1,
        createdAt: now,
        updatedAt: now
      }
    })
  })
}

async function listProjects(userId) {
  const projects = await queryAll("projects", { userId }, "updatedAt", "desc")
  return { projects: projects.map(withLegacyProjectMoney) }
}

function withLegacyProjectMoney(project) {
  const cents = Number.isSafeInteger(project.advanceFundCents)
    ? project.advanceFundCents
    : Math.round(Number(project.advanceFund || 0) * 100)
  return Object.assign({}, project, { advanceFundCents: cents, advanceFund: cents / 100 })
}

async function saveProject(userId, event) {
  const name = String(event.name || "").trim()
  if (!name) throw new Error("项目名称不能为空")
  if (name.length > 100) throw new Error("项目名称不能超过 100 个字符")
  if (event.projectId) {
    const project = await requireProject(userId, event.projectId)
    await db.collection("projects").doc(project._id).update({
      data: { name, version: Number(project.version || 0) + 1, updatedAt: db.serverDate() }
    })
    return { projectId: project._id }
  }

  const advanceFundCents = event.advanceFundCents !== undefined
    ? validateCents(event.advanceFundCents, "备用金")
    : toCents(event.advanceFund || 0, "备用金")
  const now = db.serverDate()
  const result = await db.collection("projects").add({
    data: {
      userId,
      name,
      advanceFundCents,
      advanceFund: advanceFundCents / 100,
      expenseCount: 0,
      spentCents: 0,
      version: 1,
      createdAt: now,
      updatedAt: now
    }
  })
  return { projectId: result._id }
}

async function updateAdvanceFund(userId, event) {
  const project = await requireProject(userId, event.projectId)
  const cents = event.advanceFundCents !== undefined
    ? validateCents(event.advanceFundCents, "备用金")
    : toCents(event.advanceFund || 0, "备用金")
  await db.collection("projects").doc(project._id).update({
    data: {
      advanceFundCents: cents,
      advanceFund: cents / 100,
      version: Number(project.version || 0) + 1,
      updatedAt: db.serverDate()
    }
  })
  return { projectId: project._id }
}

function validateCents(value, fieldName) {
  const cents = Number(value)
  if (!Number.isSafeInteger(cents) || cents < 0) throw new Error(`${fieldName}无效`)
  return cents
}

async function deleteProject(userId, projectId) {
  const project = await requireProject(userId, projectId)
  const countResult = await db.collection("projects").where({ userId }).count()
  if (countResult.total <= 1) throw new Error("不能删除最后一个项目")

  const expenses = await queryAll("expenses", { userId, projectId: project._id })
  const attachments = await queryAll("attachments", { userId, projectId: project._id })
  await db.runTransaction(async transaction => {
    const deletedAt = db.serverDate()
    for (const item of attachments) {
      await transaction.collection("sync_tombstones").add({
        data: { userId, entityType: "attachment", entityId: item._id, version: Number(item.version || 1) + 1, deletedAt }
      })
    }
    for (const item of expenses) {
      await transaction.collection("sync_tombstones").add({
        data: { userId, entityType: "expense", entityId: item._id, version: Number(item.version || 1) + 1, deletedAt }
      })
    }
    await transaction.collection("sync_tombstones").add({
      data: { userId, entityType: "project", entityId: project._id, version: Number(project.version || 1) + 1, deletedAt }
    })
    for (const item of attachments) await transaction.collection("attachments").doc(item._id).remove()
    for (const item of expenses) await transaction.collection("expenses").doc(item._id).remove()
    await transaction.collection("projects").doc(project._id).remove()
  })
  await deleteCloudFilesOrQueue(userId, attachments.map(item => item.fileID), "delete_project")
  return { projectId: project._id }
}

async function listExpenses(userId, event) {
  const project = await requireProject(userId, event.projectId)
  const { page, pageSize, offset } = normalizePage(event)
  const invoiceFilter = event.invoiceFilter || "all"
  const condition = { userId, projectId: project._id }
  if (invoiceFilter === "with") condition.hasInvoice = true
  if (invoiceFilter === "without") condition.hasInvoice = false

  let query = db.collection("expenses").where(condition).orderBy("date", "desc")
  const pageResult = await query.skip(offset).limit(pageSize).get()
  const keyword = String(event.keyword || "").trim().toLowerCase()
  const expenses = pageResult.data.filter(item => !keyword || expenseHaystack(item).includes(keyword))
  const attachments = await queryAttachmentsForExpenses(userId, expenses.map(item => item._id))
  const attachmentsByExpense = groupBy(attachments, "expenseId")
  const stats = await projectStats(userId, project)

  return {
    expenses: expenses.map(item => Object.assign({}, withLegacyExpenseMoney(item), {
      attachments: attachmentsByExpense[item._id] || []
    })),
    stats,
    pagination: {
      page,
      pageSize,
      hasMore: pageResult.data.length === pageSize,
      keywordPageScoped: !!keyword
    }
  }
}

function expenseHaystack(item) {
  return [item.name, item.model, item.invoiceNumber, item.onlineLink, item.notes]
    .filter(Boolean)
    .join(" ")
    .toLowerCase()
}

async function projectStats(userId, project) {
  const normalized = await ensureProjectCounters(userId, project)
  const advanceFundCents = withLegacyProjectMoney(normalized).advanceFundCents
  return moneyStats(advanceFundCents, normalized.spentCents, normalized.expenseCount)
}

async function ensureProjectCounters(userId, project) {
  if (Number.isSafeInteger(project.spentCents) && Number.isSafeInteger(project.expenseCount)) return project
  const all = await queryAll("expenses", { userId, projectId: project._id })
  const spentCents = all.reduce((sum, item) => sum + expenseAmountCents(item), 0)
  await db.collection("projects").doc(project._id).update({
    data: { spentCents, expenseCount: all.length, updatedAt: db.serverDate() }
  })
  return Object.assign({}, project, { spentCents, expenseCount: all.length })
}

function moneyStats(advanceFundCents, spentCents, count) {
  return {
    advanceFundCents,
    spentCents,
    remainingCents: advanceFundCents - spentCents,
    advanceFund: advanceFundCents / 100,
    spent: spentCents / 100,
    remaining: (advanceFundCents - spentCents) / 100,
    count
  }
}

async function getExpense(userId, expenseId) {
  const expense = await requireExpense(userId, expenseId)
  return {
    expense: withLegacyExpenseMoney(expense),
    attachments: await queryAll("attachments", { userId, expenseId: expense._id })
  }
}

async function requireExpense(userId, expenseId) {
  if (!expenseId) throw new Error("缺少支出 ID")
  const result = await db.collection("expenses").doc(expenseId).get()
  const expense = result.data
  if (!expense || expense.userId !== userId) throw new Error("支出不存在")
  return expense
}

function withLegacyExpenseMoney(expense) {
  const amountCents = expenseAmountCents(expense)
  const priceCents = Number.isSafeInteger(expense.priceCents)
    ? expense.priceCents
    : Math.round(Number(expense.price || 0) * 100)
  return Object.assign({}, expense, {
    amountCents,
    priceCents,
    totalAmount: amountCents / 100,
    price: priceCents / 100
  })
}

function expenseAmountCents(expense) {
  return Number.isSafeInteger(expense.amountCents)
    ? expense.amountCents
    : Math.round(Number(expense.totalAmount || 0) * 100)
}

async function saveExpense(userId, event) {
  const payload = normalizeExpense(event.expense)
  const requestedAttachments = (event.attachments || []).map(normalizeAttachment)
  let expenseId = event.expenseId || ""
  let project
  let existing = null
  let currentAttachments = []

  if (expenseId) {
    existing = await requireExpense(userId, expenseId)
    project = await requireProject(userId, existing.projectId)
    currentAttachments = await queryAll("attachments", { userId, expenseId })
  } else {
    project = await requireProject(userId, event.projectId)
  }
  project = await ensureProjectCounters(userId, project)

  const replacement = prepareAttachmentReplacement(userId, expenseId, currentAttachments, requestedAttachments)
  validateRequiredAttachments(payload.hasInvoice, replacement.next)

  await db.runTransaction(async transaction => {
    const now = db.serverDate()
    const latestProjectResult = await transaction.collection("projects").doc(project._id).get()
    const latestProject = latestProjectResult.data
    if (!latestProject || latestProject.userId !== userId) throw new Error("项目不存在")
    let latestExpense = null
    if (existing) {
      const latestExpenseResult = await transaction.collection("expenses").doc(expenseId).get()
      latestExpense = latestExpenseResult.data
      if (!latestExpense || latestExpense.userId !== userId) throw new Error("支出不存在")
      await transaction.collection("expenses").doc(expenseId).update({
        data: Object.assign({}, payload, {
          version: Number(latestExpense.version || 0) + 1,
          updatedAt: now
        })
      })
    } else {
      const result = await transaction.collection("expenses").add({
        data: Object.assign({}, payload, {
          userId,
          projectId: project._id,
          version: 1,
          createdAt: now,
          updatedAt: now
        })
      })
      expenseId = result._id
      await transaction.collection("projects").doc(project._id).update({
        data: {
          spentCents: Number(latestProject.spentCents || 0) + payload.amountCents,
          expenseCount: Number(latestProject.expenseCount || 0) + 1,
          version: Number(latestProject.version || 0) + 1,
          updatedAt: now
        }
      })
    }

    if (latestExpense && payload.amountCents !== expenseAmountCents(latestExpense)) {
      await transaction.collection("projects").doc(project._id).update({
        data: {
          spentCents: Number(latestProject.spentCents || 0) - expenseAmountCents(latestExpense) + payload.amountCents,
          version: Number(latestProject.version || 0) + 1,
          updatedAt: now
        }
      })
    }

    for (const item of replacement.removed) {
      await transaction.collection("sync_tombstones").add({
        data: { userId, entityType: "attachment", entityId: item._id, version: Number(item.version || 1) + 1, deletedAt: now }
      })
      await transaction.collection("attachments").doc(item._id).remove()
    }
    for (const item of replacement.next) {
      const data = {
        userId,
        projectId: project._id,
        expenseId,
        type: item.type,
        fileID: item.fileID,
        fileName: item.fileName,
        size: item.size,
        version: item._id ? Number(currentAttachments.find(current => current._id === item._id)?.version || 0) + 1 : 1,
        updatedAt: now
      }
      if (item._id) {
        await transaction.collection("attachments").doc(item._id).update({ data })
      } else {
        await transaction.collection("attachments").add({ data: Object.assign({}, data, { createdAt: now }) })
      }
    }
  })

  await deleteCloudFilesOrQueue(userId, replacement.removed.map(item => item.fileID), "replace_attachments")
  await clearPendingUploads(userId, replacement.next.map(item => item.fileID))
  return { expenseId }
}

async function registerPendingUpload(userId, event) {
  const fileID = String(event.fileID || "")
  if (!isOwnedAttachmentFile(fileID, userId)) throw new Error("附件文件不属于当前用户")
  const size = Number(event.size || 0)
  if (!Number.isSafeInteger(size) || size < 0 || size > 5 * 1024 * 1024) throw new Error("附件大小无效")
  const existing = await db.collection("pending_uploads").where({ userId, fileID }).limit(1).get()
  if (existing.data.length === 0) {
    await db.collection("pending_uploads").add({
      data: {
        userId,
        fileID,
        size,
        expiresAt: Date.now() + 24 * 60 * 60 * 1000,
        createdAt: db.serverDate()
      }
    })
  }
  return { fileID }
}

async function getAttachmentDownloadUrl(userId, event) {
  let attachment = null
  if (event.attachmentId) {
    const result = await db.collection("attachments").doc(String(event.attachmentId)).get()
    attachment = result.data
  } else if (event.fileID) {
    const pending = await db.collection("pending_uploads")
      .where({ userId, fileID: String(event.fileID) })
      .limit(1)
      .get()
    attachment = pending.data[0]
  }
  if (!attachment || attachment.userId !== userId) throw new Error("附件不存在")
  const urls = await cloud.getTempFileURL({ fileList: [attachment.fileID] })
  const file = urls.fileList && urls.fileList[0]
  if (!file || !file.tempFileURL) throw new Error("附件下载地址生成失败")
  return { url: file.tempFileURL, expiresInSeconds: 3600 }
}

async function clearPendingUploads(userId, fileIDs) {
  for (const fileID of fileIDs.filter(Boolean)) {
    const result = await db.collection("pending_uploads").where({ userId, fileID }).limit(10).get()
    for (const item of result.data) await db.collection("pending_uploads").doc(item._id).remove()
  }
}

async function deleteExpense(userId, expenseId) {
  const expense = await requireExpense(userId, expenseId)
  const project = await ensureProjectCounters(userId, await requireProject(userId, expense.projectId))
  const attachments = await queryAll("attachments", { userId, expenseId: expense._id })
  await db.runTransaction(async transaction => {
    const latestExpenseResult = await transaction.collection("expenses").doc(expense._id).get()
    const latestExpense = latestExpenseResult.data
    if (!latestExpense || latestExpense.userId !== userId) throw new Error("支出不存在")
    const latestProjectResult = await transaction.collection("projects").doc(project._id).get()
    const latestProject = latestProjectResult.data
    if (!latestProject || latestProject.userId !== userId) throw new Error("项目不存在")
    const deletedAt = db.serverDate()
    for (const item of attachments) {
      await transaction.collection("sync_tombstones").add({
        data: { userId, entityType: "attachment", entityId: item._id, version: Number(item.version || 1) + 1, deletedAt }
      })
    }
    await transaction.collection("sync_tombstones").add({
      data: { userId, entityType: "expense", entityId: expense._id, version: Number(expense.version || 1) + 1, deletedAt }
    })
    for (const item of attachments) await transaction.collection("attachments").doc(item._id).remove()
    await transaction.collection("expenses").doc(expense._id).remove()
    await transaction.collection("projects").doc(project._id).update({
      data: {
        spentCents: Math.max(0, Number(latestProject.spentCents || 0) - expenseAmountCents(latestExpense)),
        expenseCount: Math.max(0, Number(latestProject.expenseCount || 0) - 1),
        version: Number(latestProject.version || 0) + 1,
        updatedAt: db.serverDate()
      }
    })
  })
  await deleteCloudFilesOrQueue(userId, attachments.map(item => item.fileID), "delete_expense")
  return { expenseId }
}

async function requireProject(userId, projectId) {
  if (!projectId) throw new Error("缺少项目 ID")
  const result = await db.collection("projects").doc(projectId).get()
  const project = result.data
  if (!project || project.userId !== userId) throw new Error("项目不存在")
  return project
}

async function approveDevicePair(userId, event) {
  const code = String(event.code || "").trim()
  if (!/^\d{6}$/.test(code)) throw new Error("配对码无效")
  const result = await db.collection("device_pairings")
    .where({ code, status: "pending" })
    .limit(1)
    .get()
  if (result.data.length === 0) throw new Error("配对码不存在或已失效")
  const pairing = result.data[0]
  await db.runTransaction(async transaction => {
    const latestResult = await transaction.collection("device_pairings").doc(pairing._id).get()
    const latest = latestResult.data
    if (!latest || latest.status !== "pending") throw new Error("配对码已被使用")
    if (Number(latest.expiresAt || 0) < Date.now()) throw new Error("配对码已过期")
    await transaction.collection("device_pairings").doc(pairing._id).update({
      data: { userId, status: "approved", approvedAt: db.serverDate(), updatedAt: db.serverDate() }
    })
  })
  return { pairingId: pairing._id, deviceName: pairing.deviceName || "Android 设备" }
}

async function deleteCloudFilesOrQueue(userId, fileIDs, reason) {
  const fileList = Array.from(new Set(fileIDs.filter(Boolean)))
  if (fileList.length === 0) return
  try {
    await cloud.deleteFile({ fileList })
  } catch (error) {
    await db.collection("maintenance_tasks").add({
      data: {
        type: "delete_files",
        userId,
        fileIDs: fileList,
        reason,
        status: "pending",
        attempts: 0,
        lastError: String(error.message || "deleteFile failed").slice(0, 500),
        createdAt: db.serverDate(),
        updatedAt: db.serverDate()
      }
    })
    logEvent("error", "cloud_file_cleanup_queued", { userId, reason, fileCount: fileList.length })
  }
}

async function queryAttachmentsForExpenses(userId, expenseIds) {
  if (expenseIds.length === 0) return []
  return queryAll("attachments", { userId, expenseId: db.command.in(expenseIds) })
}

async function queryAll(collectionName, condition, orderField, orderDirection) {
  const pageSize = 100
  let offset = 0
  let all = []
  while (true) {
    let query = db.collection(collectionName).where(condition)
    if (orderField) query = query.orderBy(orderField, orderDirection || "asc")
    const result = await query.skip(offset).limit(pageSize).get()
    all = all.concat(result.data)
    if (result.data.length < pageSize) break
    offset += pageSize
  }
  return all
}

function groupBy(items, key) {
  return items.reduce((groups, item) => {
    const value = item[key]
    groups[value] = groups[value] || []
    groups[value].push(item)
    return groups
  }, {})
}

exports._test = {
  withLegacyExpenseMoney,
  withLegacyProjectMoney
}
