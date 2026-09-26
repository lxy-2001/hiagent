[CmdletBinding()]
param([switch]$FrontendOnly)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$frontend = Join-Path $repository 'agent-demo/frontend'
function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit code $LASTEXITCODE" }
}
try {
    $node = (Get-Command node -ErrorAction Stop).Source
    $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
    $nodeVersion = & $node -p 'process.versions.node'
    if ($LASTEXITCODE -ne 0 -or [version]$nodeVersion -lt [version]'24.15.0' -or [version]$nodeVersion -ge [version]'25.0.0') { throw 'Workbench requires Node 24.15 or newer within Node 24.' }
    if (-not (Test-Path -LiteralPath (Join-Path $frontend 'package-lock.json'))) { throw 'Frontend lockfile is missing.' }
    Push-Location $frontend
    try {
        Invoke-Checked $npm @('ci', '--ignore-scripts')
        foreach ($step in @('typecheck', 'lint', 'test:unit', 'build')) { Invoke-Checked $npm @('run', $step) }
    } finally { Pop-Location }
    $buildDirectory = Join-Path $repository 'agent-demo/target/workbench'
    foreach ($file in @('index.html', 'asset-manifest.json', 'build-info.json')) {
        if (-not (Test-Path -LiteralPath (Join-Path $buildDirectory $file))) { throw "Required workbench build file missing: $file" }
    }
    if ($FrontendOnly) { Write-Output 'Frontend build verified; Java packaging not requested.'; exit 0 }
    Push-Location $repository
    try { Invoke-Checked (Join-Path $repository 'mvnw.cmd') @('-pl', 'agent-demo', '-am', '-Pworkbench', '-DskipTests', 'package') }
    finally { Pop-Location }
    $checker = Join-Path $PSScriptRoot 'verify-workbench-package.ps1'
    if (-not (Test-Path -LiteralPath $checker)) { throw 'Package verification script is missing; delivery is incomplete.' }
    & $checker
    if ($LASTEXITCODE -ne 0) { throw 'Workbench package verification failed.' }
} catch { Write-Error $_ -ErrorAction Continue; exit 1 }
