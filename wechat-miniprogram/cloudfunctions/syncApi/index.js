const cloud = require("wx-server-sdk")
const {
  bearerToken,
  hashSecret,
  pairingCode,
  randomToken,
  secretsEqual
} = require("./auth")
const {
  centsFrom,
  normalizeAttachments,
  normalizeExpense,
  validateRequiredAttachments
} = require("./domain")

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })
const db = cloud.database()
const _ = db.command
const TOKEN_TTL_MS = 90 * 24 * 60 * 60 * 1000
const PAIRING_TTL_MS = 10 * 60 * 1000

exports.main = async event => {
  const startedAt = Date.now()
  try {
    const request = parseRequest(event || {})
    const result = await route(request)
    log("info", "sync_api_success", { method: request.method, path: request.path, durationMs: Date.now() - startedAt })
    return response(200, { ok: true, data: result })
  } catch (error) {
    const statusCode = Number(error.statusCode || 400)
    log("error", "sync_api_failure", {
      statusCode,
      error: error.message || "unknown",
      durationMs: Date.now() - startedAt
    })
    return response(statusCode, { ok: false, message: safeMessage(error) })
  }
}

async function route(request) {
  if (request.method === "POST" && request.path.endsWith("/pairings")) {
    return createPairing(request.body)
  }
  if (request.method === "POST" && request.path.endsWith("/pairings/exchange")) {
    return exchangePairing(request.body)
  }

  const session = await requireToken(request.headers)
  if (request.method === "GET" && request.path.endsWith("/sync/pull")) {
    return pullChanges(session.userId, request.query)
  }
  if (request.method === "POST" && request.path.endsWith("/sync/push")) {
    return pushChanges(session.userId, request.body)
  }
  if (request.method === "POST" && request.path.endsWith("/attachments")) {
    return uploadAttachment(session.userId, request.body)
  }
  if (request.method === "GET" && request.path.endsWith("/attachments/url")) {
    return attachmentDownloadUrl(session.userId, request.query)
  }
  if (request.method === "DELETE" && request.path.endsWith("/tokens/current")) {
    await db.collection("api_tokens").doc(session._id).update({
      data: { status: "revoked", revokedAt: db.serverDate(), updatedAt: db.serverDate() }
    })
    return { revoked: true }
  }
  throw httpError(404, "接口不存在")
}

function parseRequest(event) {
  const method = String(event.httpMethod || event.requestContext?.httpMethod || "POST").toUpperCase()
  const path = String(event.path || event.requestContext?.path || "/")
  let body = event.body || {}
  if (event.isBase64Encoded && typeof body === "string") {
    body = Buffer.from(body, "base64").toString("utf8")
  }
  if (typeof body === "string") {
    try { body = JSON.parse(body || "{}") } catch (_) { throw httpError(400, "请求 JSON 无效") }
  }
  return {
    method,
    path,
    headers: event.headers || {},
    query: event.queryStringParameters || {},
    body: body || {}
  }
}

function response(statusCode, body) {
  return {
    statusCode,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
    body: JSON.stringify(body)
  }
}

function httpError(statusCode, message) {
  const error = new Error(message)
  error.statusCode = statusCode
  return error
}

function safeMessage(error) {
  const message = String(error.message || "操作失败")
  return message.length <= 120 ? message : "操作失败"
}

function log(level, name, fields) {
  const payload = JSON.stringify(Object.assign({ name, timestamp: Date.now() }, fields))
  if (level === "error") console.error(payload)
  else console.log(payload)
}

async function createPairing(body) {
  const deviceId = String(body.deviceId || "").trim()
  const deviceName = String(body.deviceName || "Android 设备").trim().slice(0, 80)
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(deviceId)) throw httpError(400, "设备标识无效")

  const recent = await db.collection("device_pairings")
    .where({ deviceId, createdAt: _.gt(new Date(Date.now() - 60 * 1000)) })
    .limit(1)
    .get()
  if (recent.data.length > 0) throw httpError(429, "请求过于频繁，请稍后重试")

  const requestSecret = randomToken("pair_")
  const code = await uniquePairingCode()
  const now = Date.now()
  const result = await db.collection("device_pairings").add({
    data: {
      code,
      requestSecretHash: hashSecret(requestSecret),
      deviceId,
      deviceName,
      status: "pending",
      expiresAt: now + PAIRING_TTL_MS,
      createdAt: db.serverDate(),
      updatedAt: db.serverDate()
    }
  })
  const pairing = await db.collection("device_pairings").doc(result._id).get()
  return {
    pairingId: result._id,
    code: pairing.data.code,
    requestSecret,
    expiresAt: now + PAIRING_TTL_MS
  }
}

async function uniquePairingCode() {
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const code = pairingCode()
    const existing = await db.collection("device_pairings")
      .where({ code, status: "pending" })
      .limit(1)
      .get()
    if (existing.data.length === 0) return code
  }
  throw httpError(503, "暂时无法生成配对码，请稍后重试")
}

async function exchangePairing(body) {
  const pairingId = String(body.pairingId || "")
  const requestSecret = String(body.requestSecret || "")
  if (!pairingId || !requestSecret) throw httpError(400, "配对信息不完整")
  const token = randomToken("sr_")
  const expiresAt = Date.now() + TOKEN_TTL_MS

  return db.runTransaction(async transaction => {
    const result = await transaction.collection("device_pairings").doc(pairingId).get()
    const pairing = result.data
    if (!pairing || !secretsEqual(requestSecret, pairing.requestSecretHash)) throw httpError(403, "配对信息无效")
    if (pairing.status !== "approved" || !pairing.userId) throw httpError(409, "尚未在小程序中批准配对")
    if (Number(pairing.expiresAt || 0) < Date.now()) throw httpError(410, "配对码已过期")

    const tokenResult = await transaction.collection("api_tokens").add({
      data: {
        userId: pairing.userId,
        deviceId: pairing.deviceId,
        deviceName: pairing.deviceName,
        tokenHash: hashSecret(token),
        status: "active",
        expiresAt,
        createdAt: db.serverDate(),
        updatedAt: db.serverDate()
      }
    })
    await transaction.collection("device_pairings").doc(pairingId).update({
      data: { status: "consumed", tokenId: tokenResult._id, consumedAt: db.serverDate(), updatedAt: db.serverDate() }
    })
    return { accessToken: token, expiresAt, userId: pairing.userId }
  })
}

async function requireToken(headers) {
  const token = bearerToken(headers)
  if (!token) throw httpError(401, "缺少设备令牌")
  const result = await db.collection("api_tokens")
    .where({ tokenHash: hashSecret(token), status: "active" })
    .limit(1)
    .get()
  if (result.data.length === 0) throw httpError(401, "设备令牌无效")
  const session = result.data[0]
  if (Number(session.expiresAt || 0) < Date.now()) throw httpError(401, "设备令牌已过期")
  await db.collection("api_tokens").doc(session._id).update({
    data: { lastUsedAt: db.serverDate(), updatedAt: db.serverDate() }
  })
  return session
}

async function pullChanges(userId, query) {
  const collections = {
    projects: { collection: "projects", timeField: "updatedAt" },
    expenses: { collection: "expenses", timeField: "updatedAt" },
    attachments: { collection: "attachments", timeField: "updatedAt" },
    tombstones: { collection: "sync_tombstones", timeField: "deletedAt" }
  }
  const selected = collections[String(query.entity || "")]
  if (!selected) throw httpError(400, "同步实体无效")
  const since = Math.max(0, Number(query.since || 0))
  const until = Math.min(Date.now(), Number(query.until || Date.now()))
  const offset = Math.max(0, Number(query.offset || 0))
  const pageSize = Math.min(100, Math.max(1, Number(query.pageSize || 50)))
  if (![since, until, offset, pageSize].every(Number.isSafeInteger)) throw httpError(400, "同步游标无效")

  const timeRange = _.gt(new Date(since)).and(_.lte(new Date(until)))
  const result = await db.collection(selected.collection)
    .where({ userId, [selected.timeField]: timeRange })
    .orderBy(selected.timeField, "asc")
    .skip(offset)
    .limit(pageSize)
    .get()
  return {
    entity: query.entity,
    items: result.data.map(serializeDates),
    snapshotUntil: until,
    nextOffset: offset + result.data.length,
    hasMore: result.data.length === pageSize
  }
}

function serializeDates(item) {
  const output = Object.assign({}, item)
  for (const key of ["createdAt", "updatedAt", "deletedAt"]) {
    if (output[key] instanceof Date) output[key] = output[key].getTime()
  }
  return output
}

async function pushChanges(userId, body) {
  const mutations = Array.isArray(body.mutations) ? body.mutations : []
  if (mutations.length === 0 || mutations.length > 50) throw httpError(400, "每次需要提交 1 到 50 个变更")
  const results = []
  for (const mutation of mutations) {
    const clientMutationId = String(mutation.clientMutationId || "")
    if (!/^[A-Za-z0-9_-]{8,128}$/.test(clientMutationId)) {
      results.push({ clientMutationId, ok: false, message: "变更标识无效" })
      continue
    }
    const prior = await db.collection("sync_mutations").where({ userId, clientMutationId }).limit(1).get()
    if (prior.data.length > 0) {
      results.push(prior.data[0].result)
      continue
    }
    let result
    try {
      result = await applyMutation(userId, mutation)
      result = Object.assign({ clientMutationId, ok: true }, result)
    } catch (error) {
      result = { clientMutationId, ok: false, conflict: error.statusCode === 409, message: safeMessage(error) }
    }
    // Only successful mutations are durable idempotency records. Persisting a
    // timeout or another retryable failure would poison this mutation ID and
    // make every later retry return the stale failure forever.
    if (result.ok) {
      await db.collection("sync_mutations").add({
        data: { userId, clientMutationId, result, createdAt: db.serverDate() }
      })
    }
    results.push(result)
  }
  return { results }
}

async function applyMutation(userId, mutation) {
  const entity = String(mutation.entity || "")
  const operation = String(mutation.operation || "upsert")
  if (entity === "project") {
    return operation === "delete"
      ? deleteProject(userId, mutation.remoteId, mutation.baseVersion)
      : upsertProject(userId, mutation)
  }
  if (entity === "expense") {
    return operation === "delete"
      ? deleteExpense(userId, mutation.remoteId, mutation.baseVersion)
      : upsertExpense(userId, mutation)
  }
  throw httpError(400, "不支持的同步实体")
}

async function upsertProject(userId, mutation) {
  const data = mutation.data || {}
  const name = String(data.name || "").trim()
  if (!name || name.length > 100) throw httpError(400, "项目名称无效")
  const advanceFundCents = centsFrom(data, "advanceFundCents", "advanceFund", "备用金")
  if (!mutation.remoteId) {
    const result = await db.collection("projects").add({
      data: {
        userId, name, advanceFundCents, advanceFund: advanceFundCents / 100,
        spentCents: 0, expenseCount: 0, version: 1,
        createdAt: db.serverDate(), updatedAt: db.serverDate()
      }
    })
    return { entity: "project", remoteId: result._id, version: 1 }
  }
  const project = await requireOwned("projects", userId, mutation.remoteId, "项目不存在")
  requireVersion(project, mutation.baseVersion)
  let version
  await db.runTransaction(async transaction => {
    const latestResult = await transaction.collection("projects").doc(project._id).get()
    const latest = latestResult.data
    if (!latest || latest.userId !== userId) throw httpError(404, "项目不存在")
    requireVersion(latest, mutation.baseVersion)
    version = Number(latest.version || 0) + 1
    await transaction.collection("projects").doc(project._id).update({
      data: { name, advanceFundCents, advanceFund: advanceFundCents / 100, version, updatedAt: db.serverDate() }
    })
  })
  return { entity: "project", remoteId: project._id, version }
}

async function deleteProject(userId, remoteId, baseVersion) {
  const project = await requireOwned("projects", userId, remoteId, "项目不存在")
  requireVersion(project, baseVersion)
  const count = await db.collection("projects").where({ userId }).count()
  if (count.total <= 1) throw httpError(400, "不能删除最后一个项目")
  const expenses = await queryAll("expenses", { userId, projectId: project._id })
  const attachments = await queryAll("attachments", { userId, projectId: project._id })
  await db.runTransaction(async transaction => {
    const latestProjectResult = await transaction.collection("projects").doc(project._id).get()
    const latestProject = latestProjectResult.data
    if (!latestProject || latestProject.userId !== userId) throw httpError(404, "项目不存在")
    requireVersion(latestProject, baseVersion)
    const deletedAt = db.serverDate()
    for (const item of attachments) {
      await transaction.collection("sync_tombstones").add({ data: tombstone(userId, "attachment", item, deletedAt) })
      await transaction.collection("attachments").doc(item._id).remove()
    }
    for (const item of expenses) {
      await transaction.collection("sync_tombstones").add({ data: tombstone(userId, "expense", item, deletedAt) })
      await transaction.collection("expenses").doc(item._id).remove()
    }
    await transaction.collection("sync_tombstones").add({ data: tombstone(userId, "project", project, deletedAt) })
    await transaction.collection("projects").doc(project._id).remove()
  })
  await deleteFilesOrQueue(userId, attachments.map(item => item.fileID), "sync_delete_project")
  return { entity: "project", remoteId: project._id, deleted: true }
}

async function upsertExpense(userId, mutation) {
  const payload = normalizeExpense(mutation.data)
  const attachments = normalizeAttachments(mutation.data && mutation.data.attachments, userId)
  validateRequiredAttachments(payload.hasInvoice, attachments)
  const project = await ensureProjectCounters(
    userId,
    await requireOwned("projects", userId, mutation.data.projectId, "项目不存在")
  )
  let current = null
  let currentAttachments = []
  if (mutation.remoteId) {
    current = await requireOwned("expenses", userId, mutation.remoteId, "支出不存在")
    requireVersion(current, mutation.baseVersion)
    if (current.projectId !== project._id) throw httpError(400, "不能跨项目移动支出")
    currentAttachments = await queryAll("attachments", { userId, expenseId: current._id })
  }
  const currentById = new Map(currentAttachments.map(item => [item._id, item]))
  for (const item of attachments) {
    if (item._id) {
      const owned = currentById.get(item._id)
      if (!owned || owned.fileID !== item.fileID) throw httpError(403, "附件不存在或无权修改")
    }
  }
  const keepIds = new Set(attachments.map(item => item._id).filter(Boolean))
  const removed = currentAttachments.filter(item => !keepIds.has(item._id))
  let remoteId = current && current._id
  let version = current ? Number(current.version || 0) + 1 : 1

  await db.runTransaction(async transaction => {
    const now = db.serverDate()
    const latestProjectResult = await transaction.collection("projects").doc(project._id).get()
    const latestProject = latestProjectResult.data
    if (!latestProject || latestProject.userId !== userId) throw httpError(404, "项目不存在")
    let latestExpense = null
    if (current) {
      const latestExpenseResult = await transaction.collection("expenses").doc(current._id).get()
      latestExpense = latestExpenseResult.data
      if (!latestExpense || latestExpense.userId !== userId) throw httpError(404, "支出不存在")
      requireVersion(latestExpense, mutation.baseVersion)
      version = Number(latestExpense.version || 0) + 1
      await transaction.collection("expenses").doc(current._id).update({ data: Object.assign({}, payload, { version, updatedAt: now }) })
    } else {
      const added = await transaction.collection("expenses").add({
        data: Object.assign({}, payload, { userId, projectId: project._id, version, createdAt: now, updatedAt: now })
      })
      remoteId = added._id
    }
    const oldAmount = latestExpense ? amountCents(latestExpense) : 0
    if (!current || oldAmount !== payload.amountCents) {
      await transaction.collection("projects").doc(project._id).update({
        data: {
          spentCents: Number(latestProject.spentCents || 0) - oldAmount + payload.amountCents,
          expenseCount: Number(latestProject.expenseCount || 0) + (current ? 0 : 1),
          version: Number(latestProject.version || 0) + 1,
          updatedAt: now
        }
      })
    }
    for (const item of removed) {
      await transaction.collection("sync_tombstones").add({ data: tombstone(userId, "attachment", item, now) })
      await transaction.collection("attachments").doc(item._id).remove()
    }
    for (const item of attachments) {
      const prior = item._id ? currentById.get(item._id) : null
      const attachmentData = {
        userId, projectId: project._id, expenseId: remoteId,
        type: item.type, fileID: item.fileID, fileName: item.fileName, size: item.size,
        version: prior ? Number(prior.version || 0) + 1 : 1, updatedAt: now
      }
      if (prior) await transaction.collection("attachments").doc(prior._id).update({ data: attachmentData })
      else await transaction.collection("attachments").add({ data: Object.assign({}, attachmentData, { createdAt: now }) })
    }
  })
  await deleteFilesOrQueue(userId, removed.map(item => item.fileID), "sync_replace_attachments")
  await clearPendingUploads(userId, attachments.map(item => item.fileID))
  const savedAttachments = await queryAll("attachments", { userId, expenseId: remoteId })
  return {
    entity: "expense",
    remoteId,
    version,
    attachments: savedAttachments.map(item => ({
      remoteId: item._id,
      fileID: item.fileID,
      version: Number(item.version || 1)
    }))
  }
}

async function deleteExpense(userId, remoteId, baseVersion) {
  const expense = await requireOwned("expenses", userId, remoteId, "支出不存在")
  requireVersion(expense, baseVersion)
  const project = await requireOwned("projects", userId, expense.projectId, "项目不存在")
  const attachments = await queryAll("attachments", { userId, expenseId: expense._id })
  await db.runTransaction(async transaction => {
    const latestExpenseResult = await transaction.collection("expenses").doc(expense._id).get()
    const latestExpense = latestExpenseResult.data
    if (!latestExpense || latestExpense.userId !== userId) throw httpError(404, "支出不存在")
    requireVersion(latestExpense, baseVersion)
    const latestProjectResult = await transaction.collection("projects").doc(project._id).get()
    const latestProject = latestProjectResult.data
    if (!latestProject || latestProject.userId !== userId) throw httpError(404, "项目不存在")
    const deletedAt = db.serverDate()
    for (const item of attachments) {
      await transaction.collection("sync_tombstones").add({ data: tombstone(userId, "attachment", item, deletedAt) })
      await transaction.collection("attachments").doc(item._id).remove()
    }
    await transaction.collection("sync_tombstones").add({ data: tombstone(userId, "expense", expense, deletedAt) })
    await transaction.collection("expenses").doc(expense._id).remove()
    await transaction.collection("projects").doc(project._id).update({
      data: {
        spentCents: Math.max(0, Number(latestProject.spentCents || 0) - amountCents(latestExpense)),
        expenseCount: Math.max(0, Number(latestProject.expenseCount || 0) - 1),
        version: Number(latestProject.version || 0) + 1,
        updatedAt: deletedAt
      }
    })
  })
  await deleteFilesOrQueue(userId, attachments.map(item => item.fileID), "sync_delete_expense")
  return { entity: "expense", remoteId: expense._id, deleted: true }
}

async function uploadAttachment(userId, body) {
  const fileName = safeFileName(body.fileName || "attachment.jpg")
  const mimeType = String(body.mimeType || "image/jpeg")
  if (!/^image\/(jpeg|png|webp)$/.test(mimeType)) throw httpError(400, "仅支持 JPEG、PNG 或 WebP 图片")
  const buffer = Buffer.from(String(body.contentBase64 || ""), "base64")
  if (buffer.length === 0 || buffer.length > 5 * 1024 * 1024) throw httpError(400, "附件必须小于 5MB")
  const cloudPath = `attachments/${userId}/android_${Date.now()}_${fileName}`
  const uploaded = await cloud.uploadFile({ cloudPath, fileContent: buffer })
  await db.collection("pending_uploads").add({
    data: {
      userId,
      fileID: uploaded.fileID,
      size: buffer.length,
      expiresAt: Date.now() + 24 * 60 * 60 * 1000,
      createdAt: db.serverDate()
    }
  })
  return { fileID: uploaded.fileID, fileName, size: buffer.length, mimeType }
}

async function clearPendingUploads(userId, fileIDs) {
  for (const fileID of fileIDs.filter(Boolean)) {
    const result = await db.collection("pending_uploads").where({ userId, fileID }).limit(10).get()
    for (const item of result.data) await db.collection("pending_uploads").doc(item._id).remove()
  }
}

async function attachmentDownloadUrl(userId, query) {
  const fileID = String(query.fileID || "")
  if (!fileID) throw httpError(400, "缺少附件文件标识")
  const owned = await db.collection("attachments").where({ userId, fileID }).limit(1).get()
  if (owned.data.length === 0) throw httpError(404, "附件不存在")
  const result = await cloud.getTempFileURL({ fileList: [fileID] })
  const item = result.fileList && result.fileList[0]
  if (!item || !item.tempFileURL) throw httpError(500, "附件下载地址生成失败")
  return { fileID, url: item.tempFileURL, expiresInSeconds: 3600 }
}

async function requireOwned(collection, userId, id, notFoundMessage) {
  if (!id) throw httpError(400, notFoundMessage)
  const result = await db.collection(collection).doc(String(id)).get()
  if (!result.data || result.data.userId !== userId) throw httpError(404, notFoundMessage)
  return result.data
}

function requireVersion(entity, baseVersion) {
  if (Number(baseVersion) !== Number(entity.version || 0)) throw httpError(409, "数据已在另一端更新，请先同步")
}

function amountCents(expense) {
  return Number.isSafeInteger(expense.amountCents)
    ? expense.amountCents
    : Math.round(Number(expense.totalAmount || 0) * 100)
}

async function ensureProjectCounters(userId, project) {
  if (Number.isSafeInteger(project.spentCents) && Number.isSafeInteger(project.expenseCount)) return project
  const expenses = await queryAll("expenses", { userId, projectId: project._id })
  const spentCents = expenses.reduce((sum, expense) => sum + amountCents(expense), 0)
  await db.collection("projects").doc(project._id).update({
    data: { spentCents, expenseCount: expenses.length, updatedAt: db.serverDate() }
  })
  return Object.assign({}, project, { spentCents, expenseCount: expenses.length })
}

function tombstone(userId, entityType, entity, deletedAt) {
  return { userId, entityType, entityId: entity._id, version: Number(entity.version || 0) + 1, deletedAt }
}

async function deleteFilesOrQueue(userId, fileIDs, reason) {
  const fileList = Array.from(new Set(fileIDs.filter(Boolean)))
  if (fileList.length === 0) return
  try {
    await cloud.deleteFile({ fileList })
  } catch (error) {
    await db.collection("maintenance_tasks").add({
      data: {
        type: "delete_files", userId, fileIDs: fileList, reason,
        status: "pending", attempts: 0,
        lastError: String(error.message || "deleteFile failed").slice(0, 500),
        createdAt: db.serverDate(), updatedAt: db.serverDate()
      }
    })
  }
}

async function queryAll(collectionName, condition) {
  let offset = 0
  let all = []
  while (true) {
    const result = await db.collection(collectionName).where(condition).skip(offset).limit(100).get()
    all = all.concat(result.data)
    if (result.data.length < 100) return all
    offset += 100
  }
}

function safeFileName(value) {
  return String(value).replace(/[\\/:*?"<>|]/g, "_").slice(0, 100) || "attachment.jpg"
}

exports._test = { parseRequest, requireVersion, safeFileName }
