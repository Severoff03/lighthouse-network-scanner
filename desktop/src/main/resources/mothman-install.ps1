param(
    [Parameter(Mandatory=$true)][int]$ParentId,
    [Parameter(Mandatory=$true)][string]$Install,
    [Parameter(Mandatory=$true)][string]$Stage,
    [Parameter(Mandatory=$true)][string]$Log
)
$ErrorActionPreference = 'Stop'
function Write-UpdateLog([string]$Message) {
    Add-Content -LiteralPath $Log -Value ("{0:o} {1}" -f (Get-Date), $Message) -Encoding UTF8
}
$installPath = [IO.Path]::GetFullPath($Install).TrimEnd('\')
$stagePath = [IO.Path]::GetFullPath($Stage).TrimEnd('\')
$parentPath = Split-Path -Parent $installPath
$statePath = [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.lighthouse')).TrimEnd('\')
if ($installPath -eq [IO.Path]::GetPathRoot($installPath).TrimEnd('\') -or
    -not $stagePath.StartsWith($statePath + '\update-', [StringComparison]::OrdinalIgnoreCase) -or
    -not (Test-Path -LiteralPath (Join-Path $installPath 'Lighthouse.exe')) -or
    -not (Test-Path -LiteralPath (Join-Path $stagePath 'Lighthouse.exe'))) { throw 'Invalid updater paths' }
$suffix = [guid]::NewGuid().ToString('N')
$candidatePath = [IO.Path]::GetFullPath((Join-Path $parentPath ('.lighthouse-new-' + $suffix)))
$backupPath = [IO.Path]::GetFullPath((Join-Path $parentPath ('.lighthouse-previous-' + $suffix)))
foreach ($checkedPath in @($installPath,$candidatePath,$backupPath)) {
    if ((Split-Path -Parent $checkedPath) -ne $parentPath) { throw 'Update target escaped installation parent' }
}
$moved = $false
try {
    # Prepare the complete replacement beside the installation (same volume), preserving old files.
    Copy-Item -LiteralPath $stagePath -Destination $candidatePath -Recurse
    if (-not (Test-Path -LiteralPath (Join-Path $candidatePath 'runtime')) -or
        -not (Test-Path -LiteralPath (Join-Path $candidatePath 'app'))) { throw 'Incomplete replacement' }
    $process = Get-Process -Id $ParentId -ErrorAction SilentlyContinue
    if ($process -and -not $process.WaitForExit(120000)) { throw 'Application did not exit; installation untouched' }
    Move-Item -LiteralPath $installPath -Destination $backupPath
    $moved = $true
    try { Move-Item -LiteralPath $candidatePath -Destination $installPath }
    catch { Move-Item -LiteralPath $backupPath -Destination $installPath; $moved = $false; throw }
    Start-Process -FilePath (Join-Path $installPath 'Lighthouse.exe') -WorkingDirectory $installPath -WindowStyle Hidden
    Write-UpdateLog "Installed successfully. Previous installation retained: $backupPath"
    # Only remove this updater's validated temporary directory; never recursively delete the installation.
    $tempPath = Split-Path -Parent $stagePath
    if ((Split-Path -Parent $tempPath) -eq $statePath -and (Split-Path -Leaf $tempPath) -match '^update-[A-Za-z0-9-]+$') {
        Remove-Item -LiteralPath $tempPath -Recurse -Force -ErrorAction SilentlyContinue
    }
} catch {
    Write-UpdateLog ("Update failed: " + $_.Exception.Message + "; backup=" + $backupPath)
    if ($moved -and (Test-Path -LiteralPath $backupPath)) {
        # All three absolute sibling paths were validated above; preserve the failed replacement.
        Move-Item -LiteralPath $installPath -Destination $candidatePath
        Move-Item -LiteralPath $backupPath -Destination $installPath
        $moved = $false
        Write-UpdateLog 'Previous installation restored'
    }
    if (-not $moved -and (Test-Path -LiteralPath (Join-Path $installPath 'Lighthouse.exe'))) {
        Start-Process -FilePath (Join-Path $installPath 'Lighthouse.exe') -WorkingDirectory $installPath -WindowStyle Hidden
    }
    exit 1
}
