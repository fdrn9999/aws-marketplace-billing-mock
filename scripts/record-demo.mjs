// 데모 영상을 녹화해 docs/demo.webm으로 저장한다 (npm run demo:video).
import { spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const cli = path.join(root, 'node_modules', '@playwright', 'test', 'cli.js')
const resultsDir = path.join(root, 'test-results')

const run = spawnSync(process.execPath, [cli, 'test', 'e2e/demo-video.spec.ts', '--reporter=list'], {
  cwd: root,
  stdio: 'inherit',
  env: { ...process.env, PW_VIDEO: '1' },
})
if (run.status !== 0) {
  process.exit(run.status ?? 1)
}

/** test-results 아래에서 데모 테스트의 가장 최근 영상 찾기 */
function findVideos(dir) {
  if (!existsSync(dir)) return []
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) return findVideos(full)
    return entry.name.endsWith('.webm') && full.includes('demo-video') ? [full] : []
  })
}

const videos = findVideos(resultsDir).sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)
if (!videos.length) {
  console.error('녹화된 영상을 찾지 못했습니다.')
  process.exit(1)
}
const target = path.join(root, 'docs', 'demo.webm')
copyFileSync(videos[0], target)
console.log(`데모 영상 저장: ${path.relative(root, target)} (${Math.round(statSync(target).size / 1024)} KB)`)
