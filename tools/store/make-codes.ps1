# Makes free Origin Store codes (services/cosmetics/src/codes.js). Will runs
# this on his PC; each code works once.
#
#   make-codes.ps1                 one code: everything in the player's bag, free
#   make-codes.ps1 -Count 10       ten of them
#   make-codes.ps1 -Plus           one code: Origin+ for good
#
# The admin key is read from %USERPROFILE%\.origin-admin-key (one line, never
# in the repo; the service only knows its SHA-256, wrangler.toml
# CODES_ADMIN_SHA256). New codes are printed, copied to the clipboard and
# added to Documents\Origin store codes.txt.
param(
  [ValidateRange(1, 100)][int]$Count = 1,
  [switch]$Plus
)
$ErrorActionPreference = 'Stop'

$keyFile = Join-Path $HOME '.origin-admin-key'
if (-not (Test-Path $keyFile)) {
  Write-Host "No admin key yet. Save it first (Claude has it):" -ForegroundColor Yellow
  Write-Host "  Set-Content `"$keyFile`" 'origin_admin_...'"
  exit 1
}
$key = (Get-Content $keyFile -Raw).Trim()

# The same pointer file the store page and the mod read.
$api = (Get-Content (Join-Path $PSScriptRoot '..\..\website\cosmetics\service.json') -Raw | ConvertFrom-Json).api
$kind = if ($Plus) { 'plus' } else { 'bag' }
$what = if ($Plus) { 'Origin+ for good' } else { 'free bag of cosmetics' }

try {
  $r = Invoke-RestMethod -Method Post -Uri "$api/v1/admin/codes" -ContentType 'application/json' `
    -Headers @{ Authorization = "Bearer $key" } -Body (@{ kind = $kind; count = $Count } | ConvertTo-Json)
} catch {
  $msg = try { ($_.ErrorDetails.Message | ConvertFrom-Json).error } catch { $_.Exception.Message }
  Write-Host "Couldn't make codes: $msg" -ForegroundColor Red
  exit 1
}

Write-Host ""
Write-Host "$($r.codes.Count) new code(s), each one $what, works once:" -ForegroundColor Green
$r.codes | ForEach-Object { Write-Host "  $_" }
Write-Host ""
Write-Host "Players type it in 'Have a code?' at originclient.org/store.html"

$r.codes -join "`r`n" | Set-Clipboard
$log = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Origin store codes.txt'
Add-Content $log ("# {0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm'), $what)
Add-Content $log $r.codes
Write-Host "Copied to your clipboard and saved in $log"
