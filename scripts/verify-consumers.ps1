[CmdletBinding()]
param([string]$Repository = (Split-Path $PSScriptRoot -Parent), [switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Repository).Path
$wrapper = Join-Path $root 'mvnw.cmd'
$evidence = Join-Path $root '.ua/008-consumers'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('hiagent-consumers-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
$results = [Collections.Generic.List[object]]::new()
$matrix = @(
    @{id='plain'; sample='plain-java'; profile=''; absent='org.springframework:|agent-tool|agent-web|agent-llm|agent-rag|agent-mcp'},
    @{id='local'; sample='spring-agent'; profile=''; absent='agent-web|agent-llm|agent-rag|agent-mcp|spring-data-jpa|spring-data-redis'},
    @{id='llm'; sample='spring-agent'; profile='llm'; absent='agent-web|agent-rag|agent-mcp|spring-data-jpa|spring-data-redis'},
    @{id='rag'; sample='spring-agent'; profile='rag'; absent='agent-web|agent-llm|agent-mcp|spring-data-jpa|spring-data-redis'},
    @{id='mcp'; sample='spring-agent'; profile='mcp'; absent='agent-web|agent-llm|agent-rag|spring-data-jpa|spring-data-redis'},
    @{id='web'; sample='web-agent'; profile=''; absent='agent-demo|agent-eval'}
)
try {
    if (!$SkipInstall) {
        Push-Location $root
        try { & $wrapper install '-DskipTests' *> (Join-Path $evidence 'install.log'); $installExit = $LASTEXITCODE }
        finally { Pop-Location }
        if ($installExit -ne 0) { throw "Artifact installation failed; see $evidence/install.log" }
    }
    foreach ($case in $matrix) {
        $source = Join-Path $root ('samples/' + $case.sample)
        $destination = Join-Path $temporary $case.id
        New-Item -ItemType Directory -Path $destination | Out-Null
        Copy-Item -LiteralPath (Join-Path $source 'pom.xml') -Destination $destination
        Copy-Item -LiteralPath (Join-Path $source 'src') -Destination $destination -Recurse
        $arguments = @('clean', 'verify', 'dependency:tree', '-Dscope=compile')
        if ($case.profile) { $arguments += '-P' + $case.profile }
        $log = Join-Path $evidence ($case.id + '.log')
        Push-Location $destination
        try { & $wrapper @arguments *> $log; $code = $LASTEXITCODE }
        finally { Pop-Location }
        $tree = (Get-Content -LiteralPath $log | Where-Object { $_ -match '^\[INFO\] [|+\\ ]*[-+\\]' }) -join "`n"
        $isolation = $tree -notmatch $case.absent
        $results.Add([ordered]@{ id=$case.id; profile=$case.profile; directory=$destination; command=($arguments -join ' '); exitCode=$code; dependencyIsolation=$isolation; status=$(if($code -eq 0 -and $isolation){'PASS'}else{'FAIL'}); evidenceRef=$log })
        $results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'results.json') -Encoding utf8
        Write-Output "$($case.id): $($results[-1].status)"
    }
    if (@($results | Where-Object status -ne 'PASS').Count) { throw 'Consumer matrix failed; evidence and temporary directories retained.' }
} finally {
    # Retain only this run's new directory for diagnosis; never delete user directories.
    [ordered]@{ temporaryDirectory=$temporary; repository=$root; cases=$results.ToArray(); completed=($results.Count -eq 6) } |
        ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $evidence 'manifest.json') -Encoding utf8
}
