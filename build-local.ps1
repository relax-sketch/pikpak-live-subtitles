# 在 Codex Windows 进程中构建时，避免 JVM 的 Unix-domain 回环连接问题。
$ErrorActionPreference = 'Stop'
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
Push-Location $PSScriptRoot
try {
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djdk.net.unixdomain.tmpdir=$PSScriptRoot\build\nonexistent-unix-sockets"
    & .\gradlew.bat :app:assembleRelease :app:testDebugUnitTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Pop-Location
}
