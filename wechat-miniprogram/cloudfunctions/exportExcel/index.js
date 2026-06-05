const cloud = require("wx-server-sdk")
const ExcelJS = require("exceljs")

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

const db = cloud.database()

exports.main = async event => {
  try {
    const user = await requireUser()
    const project = await requireProject(user.userId, event.projectId)
    const expenses = await queryExpenses(user.userId, project._id)
    const attachments = await queryAttachments(user.userId, project._id)
    const attachmentGroups = groupBy(attachments, "expenseId")
    const workbook = buildWorkbook(project, expenses, attachmentGroups)
    const buffer = Buffer.from(await workbook.xlsx.writeBuffer())
    const fileName = `SmartReimburse_${safeFileName(project.name)}_${formatTimestamp(Date.now())}.xlsx`
    const cloudPath = `exports/${user.userId}/${project._id}/${fileName}`

    const upload = await cloud.uploadFile({
      cloudPath,
      fileContent: buffer
    })

    return {
      ok: true,
      fileID: upload.fileID,
      fileName,
      size: buffer.length
    }
  } catch (error) {
    return {
      ok: false,
      message: error.message || "导出失败"
    }
  }
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

async function queryExpenses(userId, projectId) {
  return queryAll("expenses", { userId, projectId }, "date", "desc")
}

async function queryAttachments(userId, projectId) {
  return queryAll("attachments", { userId, projectId })
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

function buildWorkbook(project, expenses, attachmentGroups) {
  const workbook = new ExcelJS.Workbook()
  workbook.creator = "SmartReimburse"
  workbook.created = new Date()
  const sheet = workbook.addWorksheet("报销明细")

  sheet.columns = [
    { header: "项目名称", key: "projectName", width: 18 },
    { header: "名称", key: "name", width: 20 },
    { header: "型号", key: "model", width: 18 },
    { header: "数量", key: "quantity", width: 10 },
    { header: "金额", key: "amount", width: 14 },
    { header: "日期", key: "date", width: 20 },
    { header: "发票号码/有无发票", key: "invoice", width: 24 },
    { header: "网购链接", key: "onlineLink", width: 32 },
    { header: "备注", key: "notes", width: 28 },
    { header: "附件文件名", key: "attachments", width: 42 }
  ]

  sheet.getRow(1).font = { bold: true }
  sheet.getRow(1).alignment = { vertical: "middle" }

  expenses.forEach(expense => {
    const attachments = attachmentGroups[expense._id] || []
    sheet.addRow({
      projectName: project.name,
      name: expense.name,
      model: expense.model,
      quantity: Number(expense.quantity || 1),
      amount: Number(expense.totalAmount || 0),
      date: formatDisplayDate(expense.date),
      invoice: expense.hasInvoice ? (expense.invoiceNumber || "有发票") : "无发票",
      onlineLink: expense.onlineLink || "",
      notes: expense.notes || "",
      attachments: attachments.map(item => item.fileName).join("; ")
    })
  })

  sheet.getColumn("amount").numFmt = "¥#,##0.00"
  sheet.eachRow(row => {
    row.eachCell(cell => {
      cell.alignment = { vertical: "top", wrapText: true }
    })
  })

  return workbook
}

function groupBy(items, key) {
  return items.reduce((groups, item) => {
    const value = item[key]
    groups[value] = groups[value] || []
    groups[value].push(item)
    return groups
  }, {})
}

function safeFileName(name) {
  return String(name || "项目")
    .replace(/[\\/:*?"<>|]/g, "_")
    .slice(0, 40)
}

function formatTimestamp(timestamp) {
  const date = new Date(timestamp)
  const pad = value => String(value).padStart(2, "0")
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}_${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
}

function formatDisplayDate(timestamp) {
  const date = new Date(Number(timestamp || Date.now()))
  const pad = value => String(value).padStart(2, "0")
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
