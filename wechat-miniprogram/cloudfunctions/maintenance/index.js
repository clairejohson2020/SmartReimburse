const cloud = require("wx-server-sdk")

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })
const db = cloud.database()
const _ = db.command

exports.main = async event => {
  const startedAt = Date.now()
  try {
    requireMaintenanceSecret(event || {})
    const results = {
      cleanupTasks: await processCleanupTasks(),
      exports: await cleanupTrackedFiles("export_files", 50),
      pendingUploads: await cleanupTrackedFiles("pending_uploads", 50),
      tokens: await removeExpired("api_tokens", "expiresAt", 100),
      pairings: await removeExpired("device_pairings", "expiresAt", 100),
      mutations: await removeOlderThan("sync_mutations", "createdAt", 30, 100),
      tombstones: await removeOlderThan("sync_tombstones", "deletedAt", 180, 100),
      mergeOperations: await removeOlderThan("merge_operations", "createdAt", 30, 100)
    }
    console.log(JSON.stringify({ name: "maintenance_success", results, durationMs: Date.now() - startedAt }))
    return { ok: true, data: results }
  } catch (error) {
    console.error(JSON.stringify({ name: "maintenance_failure", error: error.message || "unknown", durationMs: Date.now() - startedAt }))
    return { ok: false, message: "维护任务执行失败" }
  }
}

function requireMaintenanceSecret(event) {
  const expected = process.env.MAINTENANCE_SECRET
  if (!expected || String(event.secret || "") !== expected) throw new Error("unauthorized maintenance invocation")
}

async function processCleanupTasks() {
  const result = await db.collection("maintenance_tasks").where({ status: "pending" }).limit(20).get()
  let completed = 0
  for (const task of result.data) {
    try {
      if (task.type === "delete_files" && Array.isArray(task.fileIDs) && task.fileIDs.length > 0) {
        await cloud.deleteFile({ fileList: task.fileIDs })
      }
      await db.collection("maintenance_tasks").doc(task._id).update({
        data: { status: "completed", completedAt: db.serverDate(), updatedAt: db.serverDate() }
      })
      completed += 1
    } catch (error) {
      await db.collection("maintenance_tasks").doc(task._id).update({
        data: {
          attempts: Number(task.attempts || 0) + 1,
          status: Number(task.attempts || 0) >= 9 ? "dead_letter" : "pending",
          lastError: String(error.message || "cleanup failed").slice(0, 500),
          updatedAt: db.serverDate()
        }
      })
    }
  }
  return { scanned: result.data.length, completed }
}

async function cleanupTrackedFiles(collectionName, limit) {
  const result = await db.collection(collectionName).where({ expiresAt: _.lt(Date.now()) }).limit(limit).get()
  const fileList = result.data.map(item => item.fileID).filter(Boolean)
  if (fileList.length > 0) await cloud.deleteFile({ fileList })
  for (const item of result.data) await db.collection(collectionName).doc(item._id).remove()
  return result.data.length
}

async function removeExpired(collectionName, field, limit) {
  const result = await db.collection(collectionName).where({ [field]: _.lt(Date.now()) }).limit(limit).get()
  for (const item of result.data) await db.collection(collectionName).doc(item._id).remove()
  return result.data.length
}

async function removeOlderThan(collectionName, field, days, limit) {
  const threshold = new Date(Date.now() - days * 24 * 60 * 60 * 1000)
  const result = await db.collection(collectionName).where({ [field]: _.lt(threshold) }).limit(limit).get()
  for (const item of result.data) await db.collection(collectionName).doc(item._id).remove()
  return result.data.length
}
