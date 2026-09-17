// OS에 맞는 Gradle wrapper(gradlew / gradlew.bat)를 backend 폴더에서 실행하는 래퍼
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const backendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'backend')
const isWindows = process.platform === 'win32'
const command = isWindows ? 'gradlew.bat' : './gradlew'

const child = spawn(command, process.argv.slice(2), {
  cwd: backendDir,
  stdio: 'inherit',
  // Windows에서 .bat 파일은 셸을 거쳐야만 실행할 수 있다
  shell: isWindows,
})

child.on('exit', (code, signal) => {
  if (signal) process.kill(process.pid, signal)
  process.exit(code ?? 1)
})
