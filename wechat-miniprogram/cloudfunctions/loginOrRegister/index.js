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
    data: {
      openid,
      userId,
      createdAt: now,
      updatedAt: now
    }
  })

  return Object.assign({ _id: addResult._id }, user)
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
