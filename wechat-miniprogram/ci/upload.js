const fs = require("fs")
const os = require("os")
const path = require("path")
const ci = require("miniprogram-ci")

const projectRoot = path.resolve(__dirname, "..")
const miniprogramRoot = path.join(projectRoot, "miniprogram")
const cloudfunctionsRoot = path.join(projectRoot, "cloudfunctions")
const projectConfigPath = path.join(projectRoot, "project.config.json")
const envConfigPath = path.join(miniprogramRoot, "env.js")

async function main() {
  const appid = requiredEnv("WECHAT_MINIPROGRAM_APPID")
  const privateKeyPath = resolvePrivateKeyPath()
  const cloudEnv = process.env.WECHAT_MINIPROGRAM_CLOUD_ENV || ""
  const version = resolveVersion()
  const desc = process.env.WECHAT_MINIPROGRAM_DESC || `SmartReimburse ${version}`
  const robot = Number(process.env.WECHAT_MINIPROGRAM_ROBOT || 1)
  const shouldUploadCloudFunctions = process.env.WECHAT_MINIPROGRAM_SKIP_CLOUD !== "true"

  const originals = patchLocalConfig({ appid, cloudEnv })
  try {
    const project = new ci.Project({
      appid,
      type: "miniProgram",
      projectPath: projectRoot,
      privateKeyPath,
      ignores: [
        "node_modules/**/*",
        "miniprogram/miniprogram_npm/**/*",
        "cloudfunctions/**/node_modules/**/*",
        "cloudfunctions/**/package-lock.json",
        "ci/**/*"
      ]
    })

    if (shouldUploadCloudFunctions) {
      await uploadCloudFunctions(project, cloudEnv)
    }

    console.log(`Uploading mini program ${appid} version ${version}.`)
    await ci.upload({
      project,
      version,
      desc,
      robot,
      setting: {
        es6: true,
        es7: true,
        minify: true,
        autoPrefixWXSS: true,
        minifyJS: true,
        minifyWXML: true,
        minifyWXSS: true
      },
      onProgressUpdate: info => {
        if (info && info.message) {
          console.log(info.message)
        }
      }
    })

    console.log("Mini program code uploaded. Submit review or release it from WeChat Mini Program Admin if formal publishing is required.")
  } finally {
    restoreLocalConfig(originals)
  }
}

async function uploadCloudFunctions(project, cloudEnv) {
  if (!cloudEnv) {
    console.log("WECHAT_MINIPROGRAM_CLOUD_ENV is empty; skipping cloud function upload.")
    return
  }

  const functionNames = fs.readdirSync(cloudfunctionsRoot, { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => entry.name)
    .filter(name => fs.existsSync(path.join(cloudfunctionsRoot, name, "index.js")))

  if (functionNames.length === 0) {
    console.log("No cloud functions found.")
    return
  }

  const uploader = resolveCloudFunctionUploader()
  if (!uploader) {
    throw new Error("The installed miniprogram-ci version does not expose a cloud function upload API.")
  }

  for (const name of functionNames) {
    const functionPath = path.join(cloudfunctionsRoot, name)
    console.log(`Uploading cloud function ${name}.`)
    await uploader({
      project,
      env: cloudEnv,
      name,
      path: functionPath,
      remoteNpmInstall: true
    })
  }
}

function resolveCloudFunctionUploader() {
  if (typeof ci.uploadCloudFunction === "function") {
    return options => ci.uploadCloudFunction(options)
  }
  if (ci.cloud && typeof ci.cloud.uploadFunction === "function") {
    return options => ci.cloud.uploadFunction(options)
  }
  if (ci.cloud && typeof ci.cloud.deployFunction === "function") {
    return options => ci.cloud.deployFunction(options)
  }
  return null
}

function resolveVersion() {
  if (process.env.WECHAT_MINIPROGRAM_VERSION) {
    return normalizeVersion(process.env.WECHAT_MINIPROGRAM_VERSION)
  }

  const refName = process.env.GITHUB_REF_NAME || ""
  if (refName.startsWith("mini-v")) {
    return normalizeVersion(refName.slice("mini-v".length))
  }
  if (refName.startsWith("mp-v")) {
    return normalizeVersion(refName.slice("mp-v".length))
  }

  const androidBuildFile = path.resolve(projectRoot, "..", "app", "build.gradle.kts")
  if (fs.existsSync(androidBuildFile)) {
    const text = fs.readFileSync(androidBuildFile, "utf8")
    const match = text.match(/versionName\s*=\s*"([^"]+)"/)
    if (match) {
      return normalizeVersion(match[1])
    }
  }

  return normalizeVersion(new Date().toISOString().slice(0, 10).replace(/-/g, "."))
}

function normalizeVersion(value) {
  return String(value || "")
    .trim()
    .replace(/^mini-v/i, "")
    .replace(/^mp-v/i, "")
    .replace(/^v/i, "")
}

function resolvePrivateKeyPath() {
  if (process.env.WECHAT_MINIPROGRAM_PRIVATE_KEY_PATH) {
    return process.env.WECHAT_MINIPROGRAM_PRIVATE_KEY_PATH
  }

  const privateKey = process.env.WECHAT_MINIPROGRAM_PRIVATE_KEY
  if (!privateKey) {
    throw new Error("Missing WECHAT_MINIPROGRAM_PRIVATE_KEY or WECHAT_MINIPROGRAM_PRIVATE_KEY_PATH.")
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "wechat-miniprogram-key-"))
  const privateKeyPath = path.join(tmpDir, "private.key")
  fs.writeFileSync(privateKeyPath, privateKey.replace(/\\n/g, "\n"), { mode: 0o600 })
  return privateKeyPath
}

function requiredEnv(name) {
  const value = process.env[name]
  if (!value) {
    throw new Error(`Missing ${name}.`)
  }
  return value
}

function patchLocalConfig({ appid, cloudEnv }) {
  const projectConfigText = fs.readFileSync(projectConfigPath, "utf8")
  const envConfigText = fs.readFileSync(envConfigPath, "utf8")
  const projectConfig = JSON.parse(projectConfigText)

  projectConfig.appid = appid
  fs.writeFileSync(projectConfigPath, `${JSON.stringify(projectConfig, null, 2)}\n`)
  fs.writeFileSync(
    envConfigPath,
    `module.exports = {\n  cloudEnv: ${JSON.stringify(cloudEnv)},\n  appId: ${JSON.stringify(appid)}\n}\n`
  )

  return { projectConfigText, envConfigText }
}

function restoreLocalConfig(originals) {
  if (!originals) return
  fs.writeFileSync(projectConfigPath, originals.projectConfigText)
  fs.writeFileSync(envConfigPath, originals.envConfigText)
}

main().catch(error => {
  console.error(error && error.stack ? error.stack : error)
  process.exit(1)
})
