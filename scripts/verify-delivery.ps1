[CmdletBinding()]
param([string]$Repository = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Repository).Path
$evidence = Join-Path $root '.ua/008-delivery'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$copy = Join-Path ([IO.Path]::GetTempPath()) ('hiagent-delivery-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $copy | Out-Null
$paths = [Collections.Generic.List[string]]::new()
foreach ($name in @('pom.xml', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties', 'agent-demo/package.json')) { $paths.Add($name) }
[xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw
foreach ($module in $pom.project.modules.module) {
    $paths.Add("$module/pom.xml")
    $source = Join-Path $root "$module/src/main"
    if (Test-Path -LiteralPath $source) {
        foreach ($file in Get-ChildItem -LiteralPath $source -File -Recurse) { $paths.Add([IO.Path]::GetRelativePath($root, $file.FullName).Replace('\','/')) }
    }
}
# Only Demo test sources are necessary for the targeted flows and evaluation; full tests run in the original repository.
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $root 'agent-demo/src/test') -File -Recurse) { $paths.Add([IO.Path]::GetRelativePath($root, $file.FullName).Replace('\','/')) }
$files = foreach ($relative in $paths | Sort-Object -Unique) {
    if ($relative -match '(^|/)(\.env[^/]*|\.git|\.ua|target|learning)(/|$)' -or $relative -match '(?i)secret|credential') { throw "Disallowed copy path: $relative" }
    $source = Join-Path $root $relative
    if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Source links are not allowed' }
    $destination = Join-Path $copy $relative
    New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
    $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) { throw "Copy mismatch: $relative" }
    @{path=$relative; sha256=$hash}
}
Push-Location $root
try {
    $sha = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $sha -notmatch '^[a-f0-9]{40}$') { throw 'Known source Git revision required' }
    $status = @(& git status --porcelain=v1 --untracked-files=all)
    $diff = Join-Path $evidence 'source.diff'
    & git diff HEAD --binary --no-ext-diff "--output=$diff"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot capture source diff' }
} finally { Pop-Location }
$manifest = [ordered]@{schemaVersion=1;codeSha=$sha;workingTreeDirty=($status.Count -gt 0);trackedDiffHash=(Get-FileHash -LiteralPath $diff -Algorithm SHA256).Hash.ToLowerInvariant();untrackedCount=@($status | Where-Object { $_.StartsWith('?? ') }).Count;files=@($files)}
$provenance = Join-Path $copy 'source-provenance.json'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $provenance -Encoding utf8NoBOM
$checks = [Collections.Generic.List[object]]::new()
$wrapper = Join-Path $copy 'mvnw.cmd'
$commands = @(
    @{id='flows';args=@('-pl','agent-demo','-am','test','-Dtest=DeliveryFlowTest,DeliverySseTest,DeliveryModeTest','-Dsurefire.failIfNoSpecifiedTests=false',"-Dagentflow.eval.provenance=$provenance")},
    @{id='evaluation';args=@('-pl','agent-demo','-am','test','-Dtest=AgentEvaluationSuiteTest','-Dsurefire.failIfNoSpecifiedTests=false','-Dagentflow.eval.repeat=3',"-Dagentflow.eval.provenance=$provenance")}
)
try {
    Push-Location $copy
    try {
        foreach ($command in $commands) {
            $log = Join-Path $evidence ($command.id + '.log')
            & $wrapper @($command.args) *> $log
            $code = $LASTEXITCODE
            $checks.Add(@{id=$command.id;exitCode=$code;status=$(if($code -eq 0){'PASS'}else{'FAIL'});evidenceRef=$log})
            if ($code -ne 0) { throw "Copied $($command.id) verification failed" }
        }
        & npm --prefix agent-demo test *> (Join-Path $evidence 'frontend.log')
        $code = $LASTEXITCODE
        $checks.Add(@{id='frontend';exitCode=$code;status=$(if($code -eq 0){'PASS'}else{'FAIL'});evidenceRef=(Join-Path $evidence 'frontend.log')})
        if ($code -ne 0) { throw 'Copied frontend verification failed' }
    } finally { Pop-Location }
    Copy-Item -LiteralPath (Join-Path $copy 'agent-demo/target/delivery') -Destination $evidence -Recurse -Force
    $reportDirectory = (Select-String -LiteralPath (Join-Path $evidence 'evaluation.log') -Pattern '^Evaluation report: ').Line.Substring(19)
    $report = Get-Content -LiteralPath (Join-Path $reportDirectory 'report.json') -Raw | ConvertFrom-Json
    if ($report.summary.passed -ne 78 -or $report.gateStatus -ne 'PASS' -or $report.provenance.codeStatus -ne 'COPIED_SOURCE') { throw 'Copied evaluation evidence invalid' }
    Copy-Item -LiteralPath (Join-Path $reportDirectory 'report.json') -Destination (Join-Path $evidence 'evaluation-report.json')
    Copy-Item -LiteralPath (Join-Path $reportDirectory 'report.md') -Destination (Join-Path $evidence 'evaluation-report.md')
} finally {
    [ordered]@{mode='OFFLINE_FIXTURE';codeSha=$sha;workingTreeDirty=$manifest.workingTreeDirty;copy=$copy;sourceProvenance=$provenance;checks=$checks.ToArray();externalPublication='NOT_PUBLISHED'} |
        ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $evidence 'results.json') -Encoding utf8NoBOM
}
Write-Output "Copied delivery verification passed: $evidence"
