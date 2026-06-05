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
  const addResult = await db.collection("users").add({ data: user })
  await db.collection("user_openids").add({
    data: { openid, userId, createdAt: now, updatedAt: now }
  })
  return Object.assign({ _id: addResult._id }, user)
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

  await db.collection("user_openids")
    .where({ userId: sourceUserId })
    .update({ data: { userId: targetUserId, updatedAt: now } })

  await updateUserId("projects", sourceUserId, targetUserId, now)
  await updateUserId("expenses", sourceUserId, targetUserId, now)
  await updateUserId("attachments", sourceUserId, targetUserId, now)

  await db.collection("users").doc(targetUser._id).update({
    data: {
      openids,
      phoneNumber,
      phoneVerifiedAt: now,
      updatedAt: now
    }
  })
  await db.collection("users").doc(sourceUser._id).remove()

  return Object.assign({}, targetUser, {
    openids,
    phoneNumber,
    phoneVerifiedAt: Date.now()
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
