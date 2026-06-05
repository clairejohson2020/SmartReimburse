async function call(action, payload) {
  const { result } = await wx.cloud.callFunction({
    name: "dataApi",
    data: Object.assign({ action }, payload || {})
  })

  if (!result || !result.ok) {
    throw new Error((result && result.message) || "操作失败")
  }
  return result.data
}

module.exports = {
  call
}
