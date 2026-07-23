const test = require("node:test")
const assert = require("node:assert/strict")
const { bearerToken, hashSecret, pairingCode, randomToken, secretsEqual } = require("../cloudfunctions/syncApi/auth")

test("device tokens are random, hashed, and compared without plaintext storage", () => {
  const token = randomToken("sr_")
  assert.match(token, /^sr_[A-Za-z0-9_-]{40,}$/)
  const digest = hashSecret(token)
  assert.notEqual(digest, token)
  assert.equal(secretsEqual(token, digest), true)
  assert.equal(secretsEqual(`${token}x`, digest), false)
})

test("bearer parsing and pairing codes are strict", () => {
  assert.equal(bearerToken({ Authorization: "Bearer abc" }), "abc")
  assert.equal(bearerToken({ Authorization: "Basic abc" }), "")
  assert.match(pairingCode(), /^\d{6}$/)
})
