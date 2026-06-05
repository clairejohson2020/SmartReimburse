const cloud = require("wx-server-sdk")

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

exports.main = async event => {
  try {
    if (!event.fileID) {
      throw new Error("缺少待识别图片")
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
