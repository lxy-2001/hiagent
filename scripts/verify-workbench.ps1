[CmdletBinding()]
param([string]$Repository = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Repository).Path
$evidence = Join-Path $root '.ua/009-workbench/delivery'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$copy = Join-Path ([IO.Path]::GetTempPath()) ('hiagent-workbench-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $copy | Out-Null
$checks = [Collections.Generic.List[object]]::new()
function Invoke-Gate([string]$Name, [string]$Command, [string[]]$Arguments, [switch]$ExpectFailure) {
    $log = Join-Path $evidence "$Name.log"
    & $Command @Arguments *> $log
    $code = $LASTEXITCODE
    $passed = if ($ExpectFailure) { $code -ne 0 } else { $code -eq 0 }
    $checks.Add(@{name=$Name;exitCode=$code;passed=$passed;log=$log})
    if (-not $passed) { throw "Gate failed: $Name (exit $code); see $log" }
}
try {
    $paths = [Collections.Generic.List[string]]::new()
    foreach ($name in @('pom.xml','mvnw.cmd','.mvn/wrapper/maven-wrapper.properties','agent-demo/package.json','scripts/build-workbench.ps1','scripts/verify-workbench-package.ps1','docs/workbench.md')) { $paths.Add($name) }
    [xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw
    foreach ($module in $pom.project.modules.module) {
        $paths.Add("$module/pom.xml")
        $source = Join-Path $root "$module/src"
        if (Test-Path -LiteralPath $source) { foreach ($file in Get-ChildItem -LiteralPath $source -File -Recurse) { $paths.Add([IO.Path]::GetRelativePath($root,$file.FullName).Replace('\','/')) } }
    }
    $front = Join-Path $root 'agent-demo/frontend'
    foreach ($file in Get-ChildItem -LiteralPath $front -File) { if ($file.Name -match '^(package(-lock)?\.json|.*config\.(ts|js|mjs)|tsconfig\.json|index\.html)$') { $paths.Add([IO.Path]::GetRelativePath($root,$file.FullName).Replace('\','/')) } }
    foreach ($folder in @('src','scripts','tests')) { foreach ($file in Get-ChildItem -LiteralPath (Join-Path $front $folder) -Recurse -File) { $paths.Add([IO.Path]::GetRelativePath($root,$file.FullName).Replace('\','/')) } }
    $files = foreach ($relative in $paths | Sort-Object -Unique) {
        if ($relative -match '(^|/)(\.env[^/]*|\.git|\.ua|target|node_modules|learning)(/|$)') { throw "Disallowed copy path: $relative" }
        $source = Join-Path $root $relative
        if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Links not allowed: $relative" }
        $destination = Join-Path $copy $relative
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
        $hash = (Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant()
        if ((Get-FileHash -LiteralPath $destination).Hash.ToLowerInvariant() -ne $hash) { throw "Copy mismatch: $relative" }
        @{path=$relative;sha256=$hash}
    }
    $sha = (& git -C $root rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $sha -notmatch '^[a-f0-9]{40}$') { throw 'Source revision unavailable' }
    $dirty = @(& git -C $root status --porcelain=v1 --untracked-files=all).Count -gt 0
    @{schemaVersion=1;codeSha=$sha;workingTreeDirty=$dirty;files=@($files)} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $copy 'source-provenance.json') -Encoding utf8NoBOM
    Push-Location $copy
    try {
        $wrapper = Join-Path $copy 'mvnw.cmd'
        Invoke-Gate 'missing-build-rejected' $wrapper @('-pl','agent-demo','-am','-Pworkbench','validate') -ExpectFailure
        Invoke-Gate 'prepare-fixture' $wrapper @('-pl','agent-demo','-am','-DskipTests','install')
        Invoke-Gate 'fixture-classpath' $wrapper @('-pl','agent-demo','dependency:build-classpath','-Dmdep.outputFile=target/demo-test-classpath.txt','-DincludeScope=test')
        Invoke-Gate 'build-workbench' 'pwsh' @('-NoProfile','-File',(Join-Path $copy 'scripts/build-workbench.ps1'))
        $env:WORKBENCH_PACKAGED = '1'
        try { Invoke-Gate 'packaged-browser' 'npm.cmd' @('--prefix','agent-demo/frontend','run','test:e2e') }
        finally { Remove-Item Env:WORKBENCH_PACKAGED -ErrorAction SilentlyContinue }
        # Corrupt only the disposable build manifest; restore exact bytes afterwards.
        $manifest = Join-Path $copy 'agent-demo/target/workbench/build-info.json'
        $original = [IO.File]::ReadAllBytes($manifest)
        try {
            [IO.File]::WriteAllText($manifest,'{}')
            Invoke-Gate 'bad-manifest-rejected' 'pwsh' @('-NoProfile','-File',(Join-Path $copy 'scripts/verify-workbench-package.ps1')) -ExpectFailure
        } finally { [IO.File]::WriteAllBytes($manifest,$original) }
        $package = Join-Path $copy 'agent-demo/frontend/package.json'
        $originalPackage = [IO.File]::ReadAllBytes($package)
        try {
            $value = Get-Content -LiteralPath $package -Raw | ConvertFrom-Json
            foreach ($probe in @('test:unit','build')) {
                $saved = $value.scripts.$probe
                try {
                    $value.scripts.$probe = 'node -e "process.exit(19)"'
                    $value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $package -Encoding utf8NoBOM
                    Invoke-Gate ($probe.Replace(':','-') + '-failure-propagated') 'pwsh' @('-NoProfile','-File',(Join-Path $copy 'scripts/build-workbench.ps1'),'-FrontendOnly') -ExpectFailure
                } finally { $value.scripts.$probe = $saved }
            }
        } finally { [IO.File]::WriteAllBytes($package,$originalPackage) }
    } finally { Pop-Location }
    Write-Output "Fresh-copy packaged verification passed. Evidence: $evidence; retained source copy: $copy"
} catch { Write-Error $_ -ErrorAction Continue; exit 1 }
finally { @{copy=$copy;checks=@($checks);paidModel='NOT_RUN'} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8NoBOM }
