const crypto = require("crypto")

function randomToken(prefix = "") {
  return `${prefix}${crypto.randomBytes(32).toString("base64url")}`
}

function hashSecret(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex")
}

function secretsEqual(value, expectedHash) {
  const actual = Buffer.from(hashSecret(value), "hex")
  const expected = Buffer.from(String(expectedHash || ""), "hex")
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected)
}

function pairingCode() {
  return crypto.randomInt(0, 1000000).toString().padStart(6, "0")
}

function bearerToken(headers = {}) {
  const authorization = headers.authorization || headers.Authorization || ""
  const match = String(authorization).match(/^Bearer\s+(.+)$/i)
  return match ? match[1].trim() : ""
}

module.exports = { bearerToken, hashSecret, pairingCode, randomToken, secretsEqual }
