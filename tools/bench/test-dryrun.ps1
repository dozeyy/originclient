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

function Run-Bench([string]$out, [string]$fixtureDir, [string[]]$extra = @()) {
	$a = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $script, "-DryRun", "-Baseline", "-OutDir", $out, "-FixtureDir", $fixtureDir) + $extra
	# A `throw` in the script writes to stderr; Windows PowerShell 5.1 turns that into a terminating error here
	# unless the preference is relaxed for this call. Console wrapping is folded to single spaces for the Contains checks.
	$old = $ErrorActionPreference
	$ErrorActionPreference = "Continue"
	try { $o = & $ps @a 2>&1 | Out-String; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
	return @{ code = $code; text = ($o -replace "\s+", " ") }
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

	# 5. no origin-default: stop, never quietly re-baseline; -SkipOriginDefault is the explicit way through
	Write-Host "5. missing origin-default stops the run unless skipped on purpose"
	$fx5 = Join-Path $work "fx-noorigin"
	Copy-Item -Recurse $fixtures $fx5
	Remove-Item -Recurse -Force (Join-Path $fx5 "origin-default")
	$r5 = Run-Bench (Join-Path $work "noorigin") $fx5
	Check ($r5.code -ne 0) "exit code is not 0 (got $($r5.code))"
	Check ($r5.text.Contains("origin-default") -and $r5.text.Contains("-SkipOriginDefault")) "message names origin-default and the switch"
	Check (-not (Test-Path (Join-Path $work "noorigin/report.md"))) "no report.md is written with a silently changed baseline"
	$r5b = Run-Bench (Join-Path $work "noorigin-ok") $fx5 @("-SkipOriginDefault")
	Check ($r5b.code -eq 0) "with -SkipOriginDefault it runs (got $($r5b.code))"
	$s5 = Get-Content (Join-Path $work "noorigin-ok/summary.json") -Raw | ConvertFrom-Json
	Check ($s5.baseline -eq "optifine-only") "and the baseline is the OptiFine-only variant, said in summary.json"

	# 6. every run of a variant invalid (summarizer --require-valid is passed): not a green report of empty tables
	Write-Host "6. a variant with no valid run fails"
	$fx6 = Join-Path $work "fx-allinvalid"
	Copy-Item -Recurse $fixtures $fx6
	foreach ($n in 1, 2, 3) {
		$bp = Join-Path $fx6 "origin-default/run-$n/bench.json"
		$b = Get-Content $bp -Raw | ConvertFrom-Json
		foreach ($sc in $b.scenarios) { if ($sc.scenario -eq "idle") { $sc.valid = $false } }
		[System.IO.File]::WriteAllText($bp, ($b | ConvertTo-Json -Depth 10), (New-Object System.Text.UTF8Encoding($false)))
	}
	$r6 = Run-Bench (Join-Path $work "allinvalid") $fx6
	Check ($r6.code -ne 0) "exit code is not 0 (got $($r6.code))"
	Check ($r6.text.Contains("SUMMARY FAILED") -and $r6.text.Contains("origin-default")) "message names the variant with no valid run"
	Check (-not (Test-Path (Join-Path $work "allinvalid/report.md"))) "no report.md from a bench with no usable measurement"

	# 7. every run of a variant left out as busy: the variant vanishes from the folders, so it is caught by name
	Write-Host "7. a variant whose every run was busy fails"
	$fx7 = Join-Path $work "fx-allbusy"
	Copy-Item -Recurse $fixtures $fx7
	foreach ($n in 1, 2, 3) {
		$mp7 = Join-Path $fx7 "competitive/run-$n/meta.json"
		$m7 = Get-Content $mp7 -Raw | ConvertFrom-Json
		$m7.otherCpuPct = 45
		[System.IO.File]::WriteAllText($mp7, ($m7 | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
	}
	$r7 = Run-Bench (Join-Path $work "allbusy") $fx7
	Check ($r7.code -ne 0) "exit code is not 0 (got $($r7.code))"
	Check ($r7.text.Contains("NO COUNTED RUN") -and $r7.text.Contains("competitive")) "message names the variant with no counted run"

	# 8. -OutDir can never make the script delete somebody else's folder: only an "out" it marked itself is replaced
	Write-Host "8. a foreign 'out' folder is not deleted"
	$out8 = Join-Path $work "foreign"
	New-Item -ItemType Directory -Force -Path (Join-Path $out8 "out") | Out-Null
	$sentinel = Join-Path $out8 "out/precious.txt"
	[System.IO.File]::WriteAllText($sentinel, "do not delete", (New-Object System.Text.UTF8Encoding($false)))
	$r8 = Run-Bench $out8 $fixtures
	Check ($r8.code -ne 0) "exit code is not 0 (got $($r8.code))"
	Check ($r8.text.Contains("Refusing to delete") -and $r8.text.Contains("marker")) "message says it refuses and why"
	Check (Test-Path $sentinel) "the foreign file is still there"
	$r8b = Run-Bench $out1 $fixtures        # $out1 holds a previous run of this script: marked, so replaced
	Check ($r8b.code -eq 0) "a folder the script made itself is replaced on re-run (got $($r8b.code))"
	Check (Test-Path (Join-Path $out1 "out/.origin-bench-189-out")) "and carries the marker again"
}
finally {
	Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

if ($script:failed -gt 0) { Write-Host "test-dryrun: $($script:failed) check(s) FAILED"; exit 1 }
Write-Host "test-dryrun: all checks passed"
exit 0
