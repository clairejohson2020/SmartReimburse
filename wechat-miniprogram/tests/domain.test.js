const test = require("node:test")
const assert = require("node:assert/strict")
const {
  normalizeAttachment,
  normalizeExpense,
  prepareAttachmentReplacement,
  toCents,
  validateRequiredAttachments
} = require("../cloudfunctions/dataApi/domain")

test("money is normalized to integer cents", () => {
  assert.equal(toCents("0.105"), 11)
  assert.equal(normalizeExpense({ name: "墨盒", quantity: 2, totalAmount: "12.34" }).amountCents, 1234)
  assert.throws(() => normalizeExpense({ name: "墨盒", quantity: 1.5, totalAmount: 10 }), /正整数/)
  assert.throws(() => normalizeExpense({ name: "墨盒", quantity: 1, totalAmount: -1 }), /无效/)
})

test("invoice and non-invoice attachment requirements match Android", () => {
  assert.doesNotThrow(() => validateRequiredAttachments(true, [{ type: "invoice" }]))
  assert.doesNotThrow(() => validateRequiredAttachments(false, [{ type: "payment" }]))
  assert.throws(() => validateRequiredAttachments(true, [{ type: "payment" }]), /发票原图/)
  assert.throws(() => validateRequiredAttachments(false, [{ type: "invoice" }]), /付款截图/)
})

test("foreign attachment ids cannot be rebound to the caller expense", () => {
  const requested = [normalizeAttachment({
    _id: "foreign_attachment",
    type: "invoice",
    fileID: "cloud://env/attachments/user_b/invoice.jpg",
    fileName: "invoice.jpg"
  })]
  assert.throws(
    () => prepareAttachmentReplacement("user_a", "expense_a", [], requested),
    /无权修改/
  )
})

test("existing attachment file id is immutable and new files must be user-owned", () => {
  const current = [{
    _id: "attachment_a",
    userId: "user_a",
    expenseId: "expense_a",
    fileID: "cloud://env/attachments/user_a/original.jpg"
  }]
  assert.throws(
    () => prepareAttachmentReplacement("user_a", "expense_a", current, [{
      _id: "attachment_a",
      type: "invoice",
      fileID: "cloud://env/attachments/user_a/replaced.jpg"
    }]),
    /不能替换/
  )
  assert.throws(
    () => prepareAttachmentReplacement("user_a", "expense_a", current, [{
      type: "invoice",
      fileID: "cloud://env/attachments/user_b/foreign.jpg"
    }]),
    /不属于当前用户/
  )
  assert.throws(
    () => prepareAttachmentReplacement("user_a", "expense_a", current, [{
      type: "invoice",
      fileID: "cloud://env/attachments/user_a/../user_b/foreign.jpg"
    }]),
    /不属于当前用户/
  )
})
