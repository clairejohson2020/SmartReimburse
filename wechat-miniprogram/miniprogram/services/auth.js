async function login() {
  const { result } = await wx.cloud.callFunction({
    name: "loginOrRegister",
    data: {}
  })
  if (!result || !result.ok) {
    throw new Error((result && result.message) || "登录失败")
  }
  return result.user
}

async function bindPhone(code) {
  if (!code) {
    throw new Error("没有收到手机号授权码")
  }

  const { result } = await wx.cloud.callFunction({
    name: "bindPhone",
    data: { code }
  })
  if (!result || !result.ok) {
    throw new Error((result && result.message) || "手机号绑定失败")
  }
  return result.user
}

module.exports = {
  login,
  bindPhone
}
