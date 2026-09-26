[CmdletBinding()]
param([string]$Repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')))
$ErrorActionPreference = 'Stop'
try {
    $repositoryRoot = [IO.Path]::GetFullPath($Repository)
    $build = Join-Path $repositoryRoot 'agent-demo/target/workbench'
    $jar = Join-Path $repositoryRoot 'agent-demo/target/agent-demo-0.1.0-SNAPSHOT.jar'
    $info = Get-Content -LiteralPath (Join-Path $build 'build-info.json') -Raw | ConvertFrom-Json
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($property in $info.files.PSObject.Properties) {
            $entryPath = if ($property.Name -eq 'asset-manifest.json') { 'META-INF/workbench/asset-manifest.json' } else { 'BOOT-INF/classes/static/' + $property.Name }
            $entry = $zip.GetEntry($entryPath)
            if (-not $entry) { throw "Missing packaged asset: $entryPath" }
            $stream = $entry.Open()
            try { $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant() }
            finally { $stream.Dispose() }
            if ($hash -ne $property.Value) { throw "Packaged asset hash mismatch: $entryPath" }
        }
        $forbidden = @($zip.Entries | Where-Object { $_.FullName -match 'BOOT-INF/lib/(agent-eval|h2-|mockito)|BOOT-INF/classes/.*(Fixture|Launcher)|(^|/)\.env($|[./])|\.map$' })
        if ($forbidden.Count -gt 0) { throw 'Production archive contains forbidden fixture, secret or source-map entries.' }
        Write-Output "Workbench package verified: $(@($info.files.PSObject.Properties).Count) asset hashes, no forbidden entries."
    } finally { $zip.Dispose() }
} catch { Write-Error $_ -ErrorAction Continue; exit 1 }
