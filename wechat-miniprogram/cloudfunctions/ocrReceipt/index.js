const cloud = require("wx-server-sdk")

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

const db = cloud.database()

exports.main = async event => {
  try {
    const user = await requireUser()
    if (!event.fileID) {
      throw new Error("缺少待识别图片")
    }
    if (!isOwnedAttachmentFile(event.fileID, user.userId)) {
      throw new Error("图片不存在或无权访问")
    }

    // This entry is intentionally cloud-side so a real OCR provider can be
    // configured without exposing secrets to the mini program frontend.
    if (!process.env.OCR_PROVIDER) {
      return {
        ok: false,
        configured: false,
        message: "OCR 服务尚未配置，请手动补录。可在 ocrReceipt 云函数中接入腾讯云 OCR 或微信可用的 OCR 服务。"
      }
    }

    return {
      ok: false,
      configured: true,
      message: "OCR 服务入口已启用，但当前 provider 尚未实现解析逻辑，请先手动补录。"
    }
  } catch (error) {
    return {
      ok: false,
      message: error.message || "OCR 识别失败"
    }
  }
}

async function requireUser() {
  const openid = cloud.getWXContext().OPENID
  if (!openid) throw new Error("未登录")
  const mapping = await db.collection("user_openids").where({ openid }).limit(1).get()
  if (mapping.data.length === 0) throw new Error("账号未初始化")
  const users = await db.collection("users")
    .where({ userId: mapping.data[0].userId })
    .limit(1)
    .get()
  if (users.data.length === 0) throw new Error("账号不存在")
  return users.data[0]
}

function isOwnedAttachmentFile(fileID, userId) {
  const value = String(fileID || "")
  const marker = `/attachments/${userId}/`
  const markerIndex = value.indexOf(marker)
  const suffix = markerIndex >= 0 ? value.slice(markerIndex + marker.length) : ""
  return value.startsWith("cloud://") && markerIndex > "cloud://".length && !!suffix && !suffix.includes("..")
}
