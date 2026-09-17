// E2E용 백엔드 실행: jar를 빌드한 뒤 java -jar로 직접 띄운다.
// gradlew bootRun은 Gradle 데몬이 앱 프로세스를 관리해서, 테스트 종료 시 강제 종료하면 데몬이 뒤늦게 빌드를 취소하며
// 같은 콘솔의 다른 프로세스(다음 실행의 Vite 등)까지 종료시키는 경우가 있었다. 직접 띄우면 종료 범위가 이 프로세스로 한정된다.
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const backendDir = path.join(root, 'backend')
const isWindows = process.platform === 'win32'

const build = spawnSync(isWindows ? 'gradlew.bat' : './gradlew', ['bootJar', '-q', '--console=plain'], {
  cwd: backendDir,
  stdio: 'inherit',
  shell: isWindows,
})
if (build.status !== 0) {
  process.exit(build.status ?? 1)
}

const libs = path.join(backendDir, 'build', 'libs')
const jar = readdirSync(libs).find((name) => name.endsWith('.jar') && !name.endsWith('-plain.jar'))
if (!jar) {
  console.error('실행할 jar를 찾지 못했습니다:', libs)
  process.exit(1)
}

const javaHome = process.env.JAVA_HOME
const javaBin = javaHome && existsSync(path.join(javaHome, 'bin')) ? path.join(javaHome, 'bin', 'java') : 'java'
const app = spawn(javaBin, ['-Dfile.encoding=UTF-8', '-jar', path.join(libs, jar)], { cwd: root, stdio: 'inherit' })

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => app.kill(signal))
}
app.on('exit', (code) => process.exit(code ?? 0))
