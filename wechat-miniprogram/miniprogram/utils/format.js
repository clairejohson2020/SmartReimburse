function formatMoney(value) {
  const number = Number(value || 0)
  return `¥${number.toFixed(2)}`
}

function formatDate(timestamp) {
  const date = new Date(Number(timestamp || Date.now()))
  const pad = value => String(value).padStart(2, "0")
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function toDateInputValue(timestamp) {
  const date = new Date(Number(timestamp || Date.now()))
  const pad = value => String(value).padStart(2, "0")
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function fromDateInputValue(value) {
  if (!value) {
    return Date.now()
  }
  return new Date(`${value}T12:00:00`).getTime()
}

function safeFileName(name) {
  return String(name || "file")
    .replace(/[\\/:*?"<>|]/g, "_")
    .slice(0, 80)
}

module.exports = {
  formatMoney,
  formatDate,
  toDateInputValue,
  fromDateInputValue,
  safeFileName
}
