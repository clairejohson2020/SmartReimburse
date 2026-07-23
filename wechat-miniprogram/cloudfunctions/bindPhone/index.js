const cloud = require("wx-server-sdk")

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

const db = cloud.database()

exports.main = async event => {
  try {
    const wxContext = cloud.getWXContext()
    const openid = wxContext.OPENID
    if (!openid) {
      throw new Error("无法获取微信登录身份")
    }
    if (!event.code) {
      throw new Error("缺少手机号授权码")
    }

    const phoneNumber = await resolvePhoneNumber(event.code)
    const currentUser = await ensureUser(openid)
    const targetResult = await db.collection("users")
      .where({ phoneNumber })
      .limit(1)
      .get()

    let user
    if (targetResult.data.length > 0 && targetResult.data[0].userId !== currentUser.userId) {
      user = await mergeUsers(targetResult.data[0], currentUser, openid, phoneNumber)
    } else {
      user = await updateUserPhone(currentUser, openid, phoneNumber)
    }

    await ensureDefaultProject(user.userId)
    return { ok: true, user }
  } catch (error) {
    return {
      ok: false,
      message: error.message || "手机号绑定失败"
    }
  }
}

async function resolvePhoneNumber(code) {
  const result = await cloud.openapi.phonenumber.getPhoneNumber({ code })
  const info = result.phone_info || result.phoneInfo || {}
  const phoneNumber = info.phoneNumber || info.purePhoneNumber
  if (!phoneNumber) {
    throw new Error("无法读取微信授权手机号")
  }
  return phoneNumber
}

async function ensureUser(openid) {
  const mappingResult = await db.collection("user_openids")
    .where({ openid })
    .limit(1)
    .get()

  if (mappingResult.data.length > 0) {
    const userId = mappingResult.data[0].userId
    const userResult = await db.collection("users")
      .where({ userId })
      .limit(1)
      .get()
    if (userResult.data.length > 0) {
      return userResult.data[0]
    }
  }

  return db.runTransaction(async transaction => {
    const latestMapping = await transaction.collection("user_openids").where({ openid }).limit(1).get()
    if (latestMapping.data.length > 0) {
      const latestUsers = await transaction.collection("users")
        .where({ userId: latestMapping.data[0].userId })
        .limit(1)
        .get()
      if (latestUsers.data.length > 0) return latestUsers.data[0]
    }
    const now = db.serverDate()
    const userId = `u_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
    const user = {
      userId,
      openids: [openid],
      phoneNumber: "",
      phoneVerifiedAt: null,
      createdAt: now,
      updatedAt: now
    }
    const addResult = await transaction.collection("users").add({ data: user })
    if (latestMapping.data.length > 0) {
      await transaction.collection("user_openids").doc(latestMapping.data[0]._id).update({
        data: { userId, updatedAt: now }
      })
    } else {
      await transaction.collection("user_openids").add({ data: { openid, userId, createdAt: now, updatedAt: now } })
    }
    return Object.assign({ _id: addResult._id }, user)
  })
}

async function updateUserPhone(user, openid, phoneNumber) {
  const openids = Array.from(new Set([].concat(user.openids || [], openid)))
  const now = db.serverDate()
  await db.collection("users").doc(user._id).update({
    data: {
      openids,
      phoneNumber,
      phoneVerifiedAt: now,
      updatedAt: now
    }
  })
  await db.collection("user_openids")
    .where({ openid })
    .update({ data: { userId: user.userId, updatedAt: now } })

  return Object.assign({}, user, {
    openids,
    phoneNumber,
    phoneVerifiedAt: Date.now()
  })
}

async function mergeUsers(targetUser, sourceUser, openid, phoneNumber) {
  const targetUserId = targetUser.userId
  const sourceUserId = sourceUser.userId
  const now = db.serverDate()
  const openids = Array.from(new Set([]
    .concat(targetUser.openids || [])
    .concat(sourceUser.openids || [])
    .concat(openid)))

  const operation = await db.collection("merge_operations").add({
    data: {
      sourceUserId,
      targetUserId,
      status: "running",
      stage: "created",
      attempts: 1,
      createdAt: now,
      updatedAt: now
    }
  })

  try {
    // Data ownership is moved first. OpenID mappings are deliberately moved
    // last so a failed operation can be retried by the source account.
    await updateUserId("projects", sourceUserId, targetUserId, now)
    await markMergeStage(operation._id, "projects")
    await updateUserId("expenses", sourceUserId, targetUserId, now)
    await markMergeStage(operation._id, "expenses")
    await updateUserId("attachments", sourceUserId, targetUserId, now)
    await markMergeStage(operation._id, "attachments")

    await db.collection("users").doc(targetUser._id).update({
      data: {
        openids,
        phoneNumber,
        phoneVerifiedAt: now,
        updatedAt: now
      }
    })
    await markMergeStage(operation._id, "target_user")

    await db.collection("user_openids")
      .where({ userId: sourceUserId })
      .update({ data: { userId: targetUserId, updatedAt: now } })
    await markMergeStage(operation._id, "openid_mappings")

    await db.collection("users").doc(sourceUser._id).remove()
    await db.collection("merge_operations").doc(operation._id).update({
      data: { status: "completed", stage: "completed", completedAt: db.serverDate(), updatedAt: db.serverDate() }
    })
  } catch (error) {
    await db.collection("merge_operations").doc(operation._id).update({
      data: {
        status: "failed",
        lastError: String(error.message || "merge failed").slice(0, 500),
        updatedAt: db.serverDate()
      }
    })
    console.error(JSON.stringify({
      name: "user_merge_failed",
      operationId: operation._id,
      sourceUserId,
      targetUserId,
      error: error.message || "unknown"
    }))
    throw new Error("账号合并暂未完成，请稍后重试")
  }

  return Object.assign({}, targetUser, {
    openids,
    phoneNumber,
    phoneVerifiedAt: Date.now()
  })
}

async function markMergeStage(operationId, stage) {
  await db.collection("merge_operations").doc(operationId).update({
    data: { stage, updatedAt: db.serverDate() }
  })
}

async function updateUserId(collection, fromUserId, toUserId, now) {
  const items = await queryAll(collection, { userId: fromUserId })
  for (const item of items) {
    await db.collection(collection).doc(item._id).update({
      data: { userId: toUserId, updatedAt: now }
    })
  }
}

async function ensureDefaultProject(userId) {
  const countResult = await db.collection("projects")
    .where({ userId })
    .count()
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
        spentCents: 0,
        expenseCount: 0,
        version: 1,
        createdAt: now,
        updatedAt: now
      }
    })
  })
}

async function queryAll(collectionName, condition) {
  const pageSize = 100
  let offset = 0
  let all = []

  while (true) {
    const result = await db.collection(collectionName)
      .where(condition)
      .skip(offset)
      .limit(pageSize)
      .get()

    all = all.concat(result.data)
    if (result.data.length < pageSize) break
    offset += pageSize
  }

  return all
}
