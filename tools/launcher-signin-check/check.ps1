<#
Origin Launcher - "Add Microsoft Account" isolation check.

Catches the 2026-10-13 second-account bug: the sign-in WebView2 ran in
WebView2's default on-disk profile (<exe>.WebView2), so Microsoft's session
cookies from the first sign-in were reused and every later "Add Microsoft
Account" silently signed in as the first account again.

What it does (never types into the sign-in page, never reads cookie values):
  1. Starts the DEBUG launcher build (Debug builds run beside an installed
     release; a Release build would just focus the installed one).
  2. Clicks Accounts -> ADD MICROSOFT ACCOUNT via UI Automation and requires a
     FRESH Microsoft sign-in: the empty email box, no error.
  3. Requires that a sign-in browser profile folder was created or changed in
     this run under one of the two roots the sign-in can use:
       <exe dir>\<exe>.WebView2\EBWebView\*            (the old default profile)
       %LocalAppData%\OriginLauncher\webview\signin\EBWebView\*   (OriginPaths)
     (Default, and every WV2Profile_* a per-panel ProfileName creates.)
     None found = the check is looking in the wrong place: CHECK ERROR.
  4. Closes the launcher WITH THE CARD STILL OPEN (so the panel's own
     Profile.Delete never runs and this run's profile stays on disk to be
     inspected - the next launcher start sweeps it), then reads every
     <root>\EBWebView\*\Network\Cookies. Chromium holds these databases under
     an exclusive lock while it runs, so they can only be read after close.
     FAIL if any cookie for a Microsoft login host (live.com,
     microsoftonline.com, microsoft.com) was created, read or updated during
     this run. An InPrivate profile never writes cookies to disk; a normal
     profile writes MUID/fpc on page load. Rows left by an older run are
     ignored. CHECK ERROR if the profile used this run had no cookie store to
     read (nothing was actually inspected).
  5. Prints how many per-panel sign-in profile folders exist after the run,
     so repeated runs show whether they pile up.

Exit codes: 0 PASS, 1 FAIL, 2 CHECK ERROR (python missing, launcher not
found, sign-in page didn't load or showed an error, no sign-in profile from
this run found, unreadable cookie store) - an error is never PASS or FAIL.

Path length: run the exe from a short folder. Under a very deep build folder
the old exe-side profile path passes 260 characters and WebView2 cannot start
(CO_E_SERVER_EXEC_FAILURE), which this check reports as a CHECK ERROR.

Usage (repo root, after `dotnet build src\OriginLauncher.App -c Debug`, copied
to a short folder such as %TEMP%\olv):
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/launcher-signin-check/check.ps1 -Exe <path>
It reads %LocalAppData%\OriginLauncher like the real launcher; it does not
change accounts unless someone completes a sign-in in the window it opens.
#>
param(
    [string] $Exe = (Join-Path $PSScriptRoot '..\..\src\OriginLauncher.App\bin\Debug\net8.0-windows\OriginLauncher.App.exe'),
    [string] $Shot = (Join-Path $env:TEMP 'origin-addaccount-check.png'),
    [int] $WaitSeconds = 12           # for the Microsoft page to load
)
$ErrorActionPreference = 'Stop'

function Exit-CheckError([string] $why) {
    Write-Host "CHECK ERROR: $why"
    exit 2
}

try {
    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Drawing
    Add-Type -Namespace OriginCheck -Name Win32 -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
'@
    if (-not (Get-Command python -ErrorAction SilentlyContinue)) { Exit-CheckError 'python is not on PATH' }
    if (-not (Test-Path $Exe)) { Exit-CheckError "launcher not found: $Exe (build Debug first)" }
    $Exe = (Resolve-Path $Exe).Path
}
catch { Exit-CheckError $_.Exception.Message }

$exeName = Split-Path $Exe -Leaf
$profileRoots = @(
    (Join-Path (Split-Path $Exe) "$exeName.WebView2\EBWebView"),
    (Join-Path $env:LOCALAPPDATA 'OriginLauncher\webview\signin\EBWebView')
)
$signinRoot = $profileRoots[1]

function Get-ProfileDirs {
    foreach ($root in $profileRoots) {
        if (Test-Path $root) {
            Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -eq 'Default' -or $_.Name -like 'WV2Profile_*' }
        }
    }
}

# Only cookies created, read or updated DURING this run count.
$py = @'
import sqlite3, sys
try:
    since = int(sys.argv[2])
    c = sqlite3.connect(sys.argv[1])
    rows = c.execute(
        "select host_key, name from cookies "
        "where (host_key like '%live.com' or host_key like '%microsoftonline.com' or host_key like '%microsoft.com') "
        "and max(creation_utc, last_access_utc, last_update_utc) >= ?", (since,)).fetchall()
except Exception as e:
    print("error:", type(e).__name__, e)
    sys.exit(2)
for h, n in rows: print(h, n)
sys.exit(1 if rows else 0)
'@

# Sets $script:failed when any store had Microsoft cookies used this run.
# Messages go to the host (not the pipeline) so they can't be mistaken for a
# return value.
function Test-CookieStores([string] $when) {
    foreach ($dir in @(Get-ProfileDirs)) {
        $db = Join-Path $dir.FullName 'Network\Cookies'
        if (-not (Test-Path $db)) { Write-Host "  [$when] no cookie file: $($dir.FullName)"; continue }
        if ($script:usedDirs -contains $dir.FullName) { $script:inspected++ }
        $copy = Join-Path $env:TEMP ("origin-cookies-" + [guid]::NewGuid().ToString('N') + '.db')
        try {
            Copy-Item -LiteralPath $db $copy
            $out = $py | python -I - $copy $runStartChromium
            $code = $LASTEXITCODE
        }
        catch { $out = $_.Exception.Message; $code = 2 }
        finally { Remove-Item $copy -ErrorAction SilentlyContinue }

        if ($code -eq 2) { Exit-CheckError "could not read $db : $out" }
        if ($code -ne 0) {
            $script:failed = $true
            Write-Host "  [$when] FAIL: $db had Microsoft cookies used during this sign-in:"
            $out | ForEach-Object { Write-Host "      $_" }
        }
        else { Write-Host "  [$when] clean: $db" }
    }
}

# Chromium cookie timestamps: microseconds since 1601-01-01 UTC (= FILETIME / 10).
$runStart = (Get-Date).AddSeconds(-1)
$runStartChromium = [long]([DateTime]::UtcNow.ToFileTimeUtc() / 10) - 1000000

$p = Start-Process -FilePath $Exe -PassThru
$root = [System.Windows.Automation.AutomationElement]::RootElement
$pidCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ProcessIdProperty, $p.Id)
$win = $null
for ($i = 0; $i -lt 60 -and -not $win; $i++) {
    Start-Sleep -Milliseconds 500
    $win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $pidCond)
}
if (-not $win) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; Exit-CheckError 'launcher window not found' }
Start-Sleep -Seconds 2

function Find-El($prop, $value) {
    $c = New-Object System.Windows.Automation.PropertyCondition($prop, $value)
    $win.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $c)
}
function Invoke-El($el) { $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke() }
$NameP = [System.Windows.Automation.AutomationElement]::NameProperty
$IdP = [System.Windows.Automation.AutomationElement]::AutomationIdProperty

$uiError = $null
$failed = $false
try {
    $acct = Find-El $NameP 'Accounts'
    if (-not $acct) { throw 'Accounts button not found' }
    # A cold first start can take several seconds to open the flyout: wait up
    # to ~10 s, clicking Accounts once more half-way.
    Invoke-El $acct
    $add = $null
    for ($i = 0; $i -lt 33 -and -not $add; $i++) {
        Start-Sleep -Milliseconds 300
        $add = Find-El $IdP 'AddAccountButton'
        if (-not $add -and $i -eq 16) { Invoke-El $acct }
    }
    if (-not $add) { throw 'AddAccountButton not found' }
    Invoke-El $add
    Start-Sleep -Seconds $WaitSeconds

    # The sign-in must actually have opened as a FRESH Microsoft sign-in (the
    # empty email box). If it errored or never loaded, nothing below would
    # mean anything, so that is a check error, never a PASS.
    $errText = Find-El $IdP 'ErrorText'
    if ($errText -and -not $errText.Current.IsOffscreen -and $errText.Current.Name) {
        throw "sign-in reported an error: $($errText.Current.Name)"
    }
    if (-not (Find-El $IdP 'usernameEntry')) {
        throw 'the Microsoft email box (usernameEntry) never appeared - page not loaded, or it signed in without asking'
    }
    Write-Output 'sign-in page: fresh Microsoft sign-in with an empty email box'

    # The Debug window can sit behind another (e.g. the installed launcher):
    # bring THIS window to the front before capturing its rectangle.
    $hwnd = [IntPtr]$win.Current.NativeWindowHandle
    [OriginCheck.Win32]::ShowWindow($hwnd, 9) | Out-Null   # SW_RESTORE
    [OriginCheck.Win32]::SetForegroundWindow($hwnd) | Out-Null
    try { $win.SetFocus() } catch { }
    Start-Sleep -Milliseconds 700
    $r = $win.Current.BoundingRectangle
    $bmp = New-Object System.Drawing.Bitmap([int]$r.Width, [int]$r.Height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen([int]$r.X, [int]$r.Y, 0, 0, $bmp.Size)
    $bmp.Save($Shot, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Output "screenshot: $Shot"

    # Proof the check looks where the sign-in browser actually lives.
    $used = @(Get-ProfileDirs | Where-Object { $_.CreationTime -ge $runStart -or $_.LastWriteTime -ge $runStart })
    if ($used.Count -eq 0) { throw 'no sign-in browser profile was created or changed in this run under either root' }
    $used | ForEach-Object { Write-Output "profile in use this run: $($_.FullName)" }
    $script:usedDirs = @($used | ForEach-Object { $_.FullName })
}
catch { $uiError = $_.Exception.Message }
finally {
    $close = Find-El $NameP 'Close'
    if ($close) { try { Invoke-El $close } catch { $p.CloseMainWindow() | Out-Null } } else { $p.CloseMainWindow() | Out-Null }
    if (-not $p.WaitForExit(15000)) { Stop-Process -Id $p.Id -Force }
    Start-Sleep -Seconds 5   # let the WebView2 browser process flush and exit
}
if ($uiError) { Exit-CheckError "UI step failed: $uiError" }

$script:inspected = 0
Test-CookieStores 'closed'
if ($script:inspected -eq 0) { Exit-CheckError 'the profile used this run had no cookie store left to inspect after close' }

$left = @(if (Test-Path $signinRoot) { Get-ChildItem -LiteralPath $signinRoot -Directory -Filter 'WV2Profile_signin-*' })
Write-Output "per-panel sign-in profile folders left: $($left.Count)"

if ($failed) { Write-Output 'FAIL: the sign-in kept Microsoft cookies on disk (the next Add Account could reuse that session)'; exit 1 }
Write-Output 'PASS: sign-in did not create, read or update any Microsoft cookie on disk'
exit 0
