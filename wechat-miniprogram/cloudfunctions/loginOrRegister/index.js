const cloud = require("wx-server-sdk")

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

const db = cloud.database()

exports.main = async () => {
  try {
    const wxContext = cloud.getWXContext()
    const openid = wxContext.OPENID
    if (!openid) {
      throw new Error("无法获取微信登录身份")
    }

    const user = await ensureUser(openid)
    await ensureDefaultProject(user.userId)

    return { ok: true, user }
  } catch (error) {
    return {
      ok: false,
      message: error.message || "登录失败"
    }
  }
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
      await transaction.collection("user_openids").add({
        data: { openid, userId, createdAt: now, updatedAt: now }
      })
    }
    return Object.assign({ _id: addResult._id }, user)
  })
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
