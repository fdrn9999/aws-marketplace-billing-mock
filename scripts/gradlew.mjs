// Cross-platform wrapper: runs the backend Gradle wrapper (gradlew / gradlew.bat) with the given args.
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const backendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'backend')
const isWindows = process.platform === 'win32'
const command = isWindows ? 'gradlew.bat' : './gradlew'

const child = spawn(command, process.argv.slice(2), {
  cwd: backendDir,
  stdio: 'inherit',
  // .bat files can only be spawned through a shell on Windows
  shell: isWindows,
})

child.on('exit', (code, signal) => {
  if (signal) process.kill(process.pid, signal)
  process.exit(code ?? 1)
})
