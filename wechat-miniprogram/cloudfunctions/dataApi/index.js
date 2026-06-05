const cloud = require("wx-server-sdk")

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

const db = cloud.database()

exports.main = async event => {
  try {
    const user = await requireUser()
    await ensureDefaultProject(user.userId)

    switch (event.action) {
      case "listProjects":
        return ok(await listProjects(user.userId))
      case "saveProject":
        return ok(await saveProject(user.userId, event))
      case "deleteProject":
        return ok(await deleteProject(user.userId, event.projectId))
      case "updateAdvanceFund":
        return ok(await updateAdvanceFund(user.userId, event))
      case "listExpenses":
        return ok(await listExpenses(user.userId, event))
      case "getExpense":
        return ok(await getExpense(user.userId, event.expenseId))
      case "saveExpense":
        return ok(await saveExpense(user.userId, event))
      case "deleteExpense":
        return ok(await deleteExpense(user.userId, event.expenseId))
      default:
        throw new Error("未知操作")
    }
  } catch (error) {
    return {
      ok: false,
      message: error.message || "操作失败"
    }
  }
}

function ok(data) {
  return { ok: true, data }
}

async function requireUser() {
  const wxContext = cloud.getWXContext()
  const openid = wxContext.OPENID
  if (!openid) {
    throw new Error("未登录")
  }

  const mappingResult = await db.collection("user_openids")
    .where({ openid })
    .limit(1)
    .get()
  if (mappingResult.data.length === 0) {
    throw new Error("账号未初始化")
  }

  const userResult = await db.collection("users")
    .where({ userId: mappingResult.data[0].userId })
    .limit(1)
    .get()
  if (userResult.data.length === 0) {
    throw new Error("账号不存在")
  }

  return userResult.data[0]
}

async function ensureDefaultProject(userId) {
  const countResult = await db.collection("projects")
    .where({ userId })
    .count()
  if (countResult.total > 0) return

  const now = db.serverDate()
  await db.collection("projects").add({
    data: {
      userId,
      name: "默认项目",
      advanceFund: 0,
      createdAt: now,
      updatedAt: now
    }
  })
}

async function listProjects(userId) {
  const result = await db.collection("projects")
    .where({ userId })
    .orderBy("updatedAt", "desc")
    .get()
  return { projects: result.data }
}

async function saveProject(userId, event) {
  const name = String(event.name || "").trim()
  if (!name) {
    throw new Error("项目名称不能为空")
  }

  const now = db.serverDate()
  if (event.projectId) {
    const project = await requireProject(userId, event.projectId)
    await db.collection("projects").doc(project._id).update({
      data: {
        name,
        updatedAt: now
      }
    })
    return { projectId: project._id }
  }

  const result = await db.collection("projects").add({
    data: {
      userId,
      name,
      advanceFund: Number(event.advanceFund || 0),
      createdAt: now,
      updatedAt: now
    }
  })
  return { projectId: result._id }
}

async function updateAdvanceFund(userId, event) {
  const project = await requireProject(userId, event.projectId)
  await db.collection("projects").doc(project._id).update({
    data: {
      advanceFund: Number(event.advanceFund || 0),
      updatedAt: db.serverDate()
    }
  })
  return { projectId: project._id }
}

async function deleteProject(userId, projectId) {
  const project = await requireProject(userId, projectId)
  const countResult = await db.collection("projects")
    .where({ userId })
    .count()
  if (countResult.total <= 1) {
    throw new Error("不能删除最后一个项目")
  }

  const attachments = await queryAll("attachments", { userId, projectId: project._id })
  await deleteCloudFiles(attachments.map(item => item.fileID))

  await removeWhere("attachments", { userId, projectId: project._id })
  await removeWhere("expenses", { userId, projectId: project._id })
  await db.collection("projects").doc(project._id).remove()
  return { projectId: project._id }
}

async function listExpenses(userId, event) {
  const project = await requireProject(userId, event.projectId)
  const allExpenses = await queryAll("expenses", { userId, projectId: project._id }, "date", "desc")
  const allAttachments = await queryAll("attachments", { userId, projectId: project._id })
  const attachmentsByExpense = groupBy(allAttachments, "expenseId")
  const keyword = String(event.keyword || "").trim().toLowerCase()
  const invoiceFilter = event.invoiceFilter || "all"

  const filtered = allExpenses
    .filter(item => {
      if (!keyword) return true
      const haystack = [
        item.name,
        item.model,
        item.invoiceNumber,
        item.onlineLink,
        item.notes
      ].join(" ").toLowerCase()
      return haystack.includes(keyword)
    })
    .filter(item => {
      if (invoiceFilter === "with") return !!item.hasInvoice
      if (invoiceFilter === "without") return !item.hasInvoice
      return true
    })
    .map(item => Object.assign({}, item, {
      attachments: attachmentsByExpense[item._id] || []
    }))

  const spent = allExpenses.reduce((sum, item) => sum + Number(item.totalAmount || 0), 0)
  const advanceFund = Number(project.advanceFund || 0)

  return {
    expenses: filtered,
    stats: {
      advanceFund,
      spent,
      remaining: advanceFund - spent,
      count: allExpenses.length
    }
  }
}

async function getExpense(userId, expenseId) {
  if (!expenseId) {
    throw new Error("缺少支出 ID")
  }

  const expenseResult = await db.collection("expenses").doc(expenseId).get()
  const expense = expenseResult.data
  if (!expense || expense.userId !== userId) {
    throw new Error("支出不存在")
  }

  return {
    expense,
    attachments: await queryAll("attachments", { userId, expenseId })
  }
}

async function saveExpense(userId, event) {
  const payload = normalizeExpense(event.expense)
  let expenseId = event.expenseId || ""
  let projectId = event.projectId || ""

  if (expenseId) {
    const existing = await getExpense(userId, expenseId)
    projectId = existing.expense.projectId
    await db.collection("expenses").doc(expenseId).update({
      data: Object.assign({}, payload, {
        updatedAt: db.serverDate()
      })
    })
  } else {
    const project = await requireProject(userId, projectId)
    projectId = project._id
    const result = await db.collection("expenses").add({
      data: Object.assign({}, payload, {
        userId,
        projectId,
        createdAt: db.serverDate(),
        updatedAt: db.serverDate()
      })
    })
    expenseId = result._id
  }

  await replaceAttachments(userId, projectId, expenseId, event.attachments || [])
  return { expenseId }
}

function normalizeExpense(expense) {
  const source = expense || {}
  const name = String(source.name || "").trim()
  if (!name) {
    throw new Error("名称不能为空")
  }

  return {
    name,
    model: String(source.model || "").trim(),
    quantity: Math.max(1, Number(source.quantity || 1)),
    totalAmount: Number(source.totalAmount || 0),
    date: Number(source.date || Date.now()),
    hasInvoice: !!source.hasInvoice,
    invoiceNumber: source.hasInvoice ? String(source.invoiceNumber || "").trim() : "",
    onlineLink: String(source.onlineLink || "").trim(),
    notes: String(source.notes || "").trim()
  }
}

async function replaceAttachments(userId, projectId, expenseId, nextAttachments) {
  const current = await queryAll("attachments", { userId, expenseId })
  const keepIds = new Set(nextAttachments.map(item => item._id).filter(Boolean))
  const removed = current.filter(item => !keepIds.has(item._id))
  await deleteCloudFiles(removed.map(item => item.fileID))

  for (const item of removed) {
    await db.collection("attachments").doc(item._id).remove()
  }

  for (const item of nextAttachments) {
    const data = {
      userId,
      projectId,
      expenseId,
      type: item.type || "other",
      fileID: item.fileID,
      fileName: item.fileName || "attachment",
      size: Number(item.size || 0),
      updatedAt: db.serverDate()
    }

    if (!data.fileID) continue

    if (item._id && keepIds.has(item._id)) {
      await db.collection("attachments").doc(item._id).update({ data })
    } else {
      await db.collection("attachments").add({
        data: Object.assign({}, data, {
          createdAt: db.serverDate()
        })
      })
    }
  }
}

async function deleteExpense(userId, expenseId) {
  const data = await getExpense(userId, expenseId)
  await deleteCloudFiles(data.attachments.map(item => item.fileID))
  await removeWhere("attachments", { userId, expenseId })
  await db.collection("expenses").doc(data.expense._id).remove()
  return { expenseId }
}

async function requireProject(userId, projectId) {
  if (!projectId) {
    throw new Error("缺少项目 ID")
  }

  const projectResult = await db.collection("projects").doc(projectId).get()
  const project = projectResult.data
  if (!project || project.userId !== userId) {
    throw new Error("项目不存在")
  }
  return project
}

async function deleteCloudFiles(fileIDs) {
  const fileList = fileIDs.filter(Boolean)
  if (fileList.length === 0) return
  try {
    await cloud.deleteFile({ fileList })
  } catch (error) {
    console.warn("deleteFile failed", error)
  }
}

async function removeWhere(collectionName, condition) {
  const items = await queryAll(collectionName, condition)
  for (const item of items) {
    await db.collection(collectionName).doc(item._id).remove()
  }
}

async function queryAll(collectionName, condition, orderField, orderDirection) {
  const pageSize = 100
  let offset = 0
  let all = []

  while (true) {
    let query = db.collection(collectionName)
      .where(condition)
    if (orderField) {
      query = query.orderBy(orderField, orderDirection || "asc")
    }
    const result = await query
      .skip(offset)
      .limit(pageSize)
      .get()

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
