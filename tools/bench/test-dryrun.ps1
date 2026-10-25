<#
.SYNOPSIS
  Test for bench-189.ps1 -DryRun: runs the real script against the synthetic fixture runs and checks the
  report against numbers worked out by hand (see tools/bench/fixtures/make_dryrun.py), then proves it
  fails loudly on a corrupt CSV and leaves a busy run out. Works in Windows PowerShell 5.1 and pwsh.

  Run:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\bench\test-dryrun.ps1   (exit 0 = all pass)
#>
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$script = Join-Path $here "bench-189.ps1"
$fixtures = Join-Path $here "fixtures/dryrun"
$ps = (Get-Process -Id $PID).Path
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("bench189-dryrun-test-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null

$script:failed = 0
function Check([bool]$ok, [string]$what) {
	if ($ok) { Write-Host "  PASS  $what" } else { Write-Host "  FAIL  $what"; $script:failed++ }
}

function Run-Bench([string]$out, [string]$fixtureDir) {
	$a = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $script, "-DryRun", "-Baseline", "-OutDir", $out, "-FixtureDir", $fixtureDir)
	$o = & $ps @a 2>&1 | Out-String
	return @{ code = $LASTEXITCODE; text = $o }
}

try {
	# 1. the real fixtures: exit 0, hand-computed numbers in report.md and summary.json
	Write-Host "1. fixtures -> report"
	$out1 = Join-Path $work "ok"
	$r = Run-Bench $out1 $fixtures
	Check ($r.code -eq 0) "exit code 0 (got $($r.code))"
	$rep = ""
	if (Test-Path (Join-Path $out1 "report.md")) { $rep = Get-Content (Join-Path $out1 "report.md") -Raw }
	Check ($rep -ne "") "report.md written"
	Check ($rep.Contains("DRY RUN. Nothing here was measured")) "report says it is a dry run"
	Check ($rep.Contains("Fixture GPU 9000") -and $rep.Contains("Fixture CPU 8-Core") -and $rep.Contains("32 GB")) "machine details (GPU, CPU, RAM) are in the report"
	# idle, base 10 ms (+/-0.5): 300 frames, 10 of them 4x -> 909.09/10 = 90.9 FPS; OptiFine-only 9 ms -> 101.0; competitive 8 ms -> 113.6
	Check ($rep.Contains("| Origin default | 3 | 90.9 (86.6-95.7)")) "Origin default idle avg FPS = 90.9 (86.6-95.7)"
	Check ($rep.Contains("| OptiFine only | 3 | 101.0 (95.7-107.0)")) "OptiFine-only idle avg FPS = 101.0"
	Check ($rep.Contains("| Competitive | 3 | 113.6 (107.0-121.2)")) "Competitive idle avg FPS = 113.6"
	Check ($rep.Contains("| Competitive | +25.0%")) "Competitive vs Origin default = +25.0%"
	Check ($rep.Contains("| OptiFine only | +11.1%")) "OptiFine-only vs Origin default = +11.1%"
	Check ($rep.Contains("Not run: balanced") -and $rep.Contains("Not run: quality")) "presets without data are skipped and named"
	Check ($rep.Contains("Origin default, run 2: PC was 2% busy before and 11% during (the game used 9%, other programs 2%)")) "idle CPU before/during note per run"
	$j = Get-Content (Join-Path $out1 "summary.json") -Raw | ConvertFrom-Json
	Check ($j.label -eq "DRY RUN, synthetic fixtures") "summary.json label"
	Check ([math]::Abs($j.variants.'origin-default'.scenes.idle.avgFps.median - 90.909) -lt 0.02) "summary.json idle median = 90.91 (909.09 / 10 ms)"
	Check ($j.variants.'origin-default'.scenes.idle.gcPauseMaxMs.median -eq 22) "GC pause max median = 22 (from the run files)"

	# 2. a busy run is kept but not counted
	Write-Host "2. busy run is left out"
	$fx2 = Join-Path $work "fx-busy"
	Copy-Item -Recurse $fixtures $fx2
	$mp = Join-Path $fx2 "competitive/run-3/meta.json"
	$m = Get-Content $mp -Raw | ConvertFrom-Json
	$m.otherCpuPct = 45
	[System.IO.File]::WriteAllText($mp, ($m | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
	$out2 = Join-Path $work "busy"
	$r2 = Run-Bench $out2 $fx2
	Check ($r2.code -eq 0) "exit code 0 (got $($r2.code))"
	$rep2 = Get-Content (Join-Path $out2 "report.md") -Raw
	Check ($rep2.Contains("NOT COUNTED") -and $rep2.Contains("Competitive, run 3")) "report names the busy run"
	Check ($rep2.Contains("| Competitive | 2 |")) "competitive now counts 2 runs"
	Check (Test-Path (Join-Path $out2 "out/competitive/left-out-3")) "its files are kept in left-out-3"

	# 3. a corrupt CSV stops the whole thing, loudly
	Write-Host "3. corrupt CSV fails loudly"
	$fx3 = Join-Path $work "fx-corrupt"
	Copy-Item -Recurse $fixtures $fx3
	$csv = Join-Path $fx3 "origin-default/run-2/frames-idle.csv"
	Add-Content -LiteralPath $csv -Value "not-a-number"
	$out3 = Join-Path $work "corrupt"
	$r3 = Run-Bench $out3 $fx3
	Check ($r3.code -ne 0) "exit code is not 0 (got $($r3.code))"
	Check ($r3.text.Contains("SUMMARY FAILED") -and $r3.text.Contains("frames-idle.csv")) "message names the failing file"
	Check (-not (Test-Path (Join-Path $out3 "report.md"))) "no report.md is written from bad data"

	# 4. a truncated CSV (fewer frames than bench.json recorded) also fails
	Write-Host "4. truncated CSV fails loudly"
	$fx4 = Join-Path $work "fx-trunc"
	Copy-Item -Recurse $fixtures $fx4
	$csv4 = Join-Path $fx4 "optifine-only/run-1/frames-flythrough.csv"
	$lines = @(Get-Content -LiteralPath $csv4)
	[System.IO.File]::WriteAllText($csv4, (($lines | Select-Object -First 200) -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))
	$r4 = Run-Bench (Join-Path $work "trunc") $fx4
	Check ($r4.code -ne 0) "exit code is not 0 (got $($r4.code))"
	Check ($r4.text.Contains("truncated")) "message says truncated"
}
finally {
	Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

if ($script:failed -gt 0) { Write-Host "test-dryrun: $($script:failed) check(s) FAILED"; exit 1 }
Write-Host "test-dryrun: all checks passed"
exit 0
