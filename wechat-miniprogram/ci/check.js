const fs = require("fs")
const path = require("path")

const root = path.resolve(__dirname, "..")
const errors = []

function walk(directory, callback) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const fullPath = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === "node_modules" || entry.name === "miniprogram_npm") continue
      walk(fullPath, callback)
    } else {
      callback(fullPath)
    }
  }
}

walk(root, filePath => {
  if (!filePath.endsWith(".json")) return
  try {
    JSON.parse(fs.readFileSync(filePath, "utf8"))
  } catch (error) {
    errors.push(`${path.relative(root, filePath)}: ${error.message}`)
  }
})

if (errors.length > 0) {
  console.error(errors.join("\n"))
  process.exit(1)
}

console.log("WeChat mini program JSON files are valid.")
