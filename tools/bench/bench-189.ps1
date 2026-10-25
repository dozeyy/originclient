<#
.SYNOPSIS
  Origin 1.8.9 benchmark on a real GPU: one command, plain-words report.

.DESCRIPTION
  From a fresh PowerShell:
      cd "C:\Users\Will\Documents\Minecraft Mods\Origin Client"
      powershell -NoProfile -ExecutionPolicy Bypass -File tools\bench\bench-189.ps1

  It runs the stack a player runs (Forge 11.15.1.2318, OptiFine HD U M5, TexFix, BetterFps, the
  Origin jar built from this repo, the launcher's Temurin 8 and JVM flags) through tools\bench\prod189.py,
  in an ISOLATED game folder under artifacts\bench-189\. It never touches the launcher's data
  (%LocalAppData%\OriginLauncher), your worlds, accounts or options.

  Variants (each runs -Runs times, rounds alternate the order so PC drift hits all of them alike):
      origin-default   Origin exactly as shipped
      competitive / balanced / quality
                       Origin + that preset (tools\bench\presets-189\<name>.json). A preset whose file does
                       not exist yet is SKIPPED and the report says so.
      optifine-only    only with -Baseline: same mods, Origin switched off (shows what Origin itself costs)

  Per run it records how busy the PC was just before and during the run (other programs skew the
  numbers). A run with another program using more than -MaxCpuLoad percent is kept on disk under
  left-out-<n> and not counted; the report names it.

  Output: artifacts\bench-189\report.md (plain words), summary.json, summary.md, and out\<variant>\run-<n>\
  with every run's bench.json and per-frame CSVs.

  -DryRun makes no game and needs no PC load: it runs the same summary + report code on the fixture runs in
  tools\bench\fixtures\dryrun (synthetic, hand-computable) and says so loudly in the report.

  Do not run it while a Minecraft of yours is open: it refuses to start then (unless -Force), because two
  games on one PC measure each other, not Origin.

.PARAMETER Runs            Rounds per variant (default 3).
.PARAMETER Scenes          Comma list of idle,particles,menu,flythrough (hud only exists with Origin on).
.PARAMETER Warmup          Seconds of warm-up per scene (default 15).
.PARAMETER Duration        Measured seconds per scene (default 45).
.PARAMETER Variants        Comma list (default origin-default,competitive,balanced,quality).
.PARAMETER Baseline        Also run optifine-only (Origin inert).
.PARAMETER OutDir          Default artifacts\bench-189 (artifacts\bench-189\dry-run with -DryRun).
.PARAMETER MaxCpuLoad      A run is not counted if other programs used more than this % of the CPU (default 20).
.PARAMETER IdleWaitMinutes How long to wait for a busy PC to calm down before a run (default 5).
.PARAMETER NoBuild         Use the jar already in src\mods\versions\1.8.9\build\libs.
.PARAMETER Width/Height    Game window size (default 1920x1080).
.PARAMETER DryRun          Report from fixtures, no game.
.PARAMETER FixtureDir      Fixture runs for -DryRun (default tools\bench\fixtures\dryrun).
.PARAMETER Force           Run even if a java/javaw process is already running.
.PARAMETER SkipOriginDefault  Allow a run with no origin-default variant. Without it the script stops: every
                           comparison is against origin-default, and quietly re-basing on another variant
                           would make every percentage in the report mean something else.
#>
param(
	[int]$Runs = 3,
	[string]$Scenes = "idle,particles,menu,flythrough",
	[double]$Warmup = 15,
	[double]$Duration = 45,
	[string]$Variants = "origin-default,competitive,balanced,quality",
	[switch]$Baseline,
	[string]$OutDir = "",
	[double]$MaxCpuLoad = 20,
	[double]$IdleWaitMinutes = 5,
	[switch]$NoBuild,
	[int]$Width = 1920,
	[int]$Height = 1080,
	[switch]$DryRun,
	[string]$FixtureDir = "",
	[switch]$Force,
	[switch]$SkipOriginDefault
)

$ErrorActionPreference = "Stop"
$inv = [System.Globalization.CultureInfo]::InvariantCulture
$utf8 = New-Object System.Text.UTF8Encoding($false)   # no BOM
$repo = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$module = Join-Path $repo "src\mods\versions\1.8.9"
$prod = Join-Path $PSScriptRoot "prod189.py"
$summarizer = Join-Path $PSScriptRoot "summarize.py"
$presetDir = Join-Path $PSScriptRoot "presets-189"

function Say([string]$msg) { Write-Host "[bench] $msg" }
function Fmt($v, [string]$f) { if ($null -eq $v) { return "-" } return ([double]$v).ToString($f, $inv) }

# ---- where things go (isolated: never the launcher's folder) ----
if ($OutDir -eq "") {
	$OutDir = Join-Path $repo "artifacts\bench-189"
	if ($DryRun) { $OutDir = Join-Path $OutDir "dry-run" }
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
if ($env:LOCALAPPDATA -and $OutDir.StartsWith((Join-Path $env:LOCALAPPDATA "OriginLauncher"), [System.StringComparison]::OrdinalIgnoreCase)) {
	throw "Refusing to write inside the launcher's data folder: $OutDir"
}
$outRoot = Join-Path $OutDir "out"
# A fresh set every time (no mixing of old and new runs), but -OutDir is caller-chosen, so the recursive delete
# only ever touches a folder this script itself made: it carries a marker file written below. An existing "out"
# without the marker is somebody's folder; stop instead of deleting it.
$outMarker = Join-Path $outRoot ".origin-bench-189-out"
if (Test-Path $outRoot) {
	if (-not (Test-Path $outMarker)) {
		throw "Refusing to delete '$outRoot': it was not created by bench-189.ps1 (no .origin-bench-189-out marker). Pick another -OutDir or remove that folder yourself."
	}
	Remove-Item -Recurse -Force $outRoot
}
New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
[System.IO.File]::WriteAllText($outMarker, "written by tools/bench/bench-189.ps1; this folder is deleted and recreated on every run`n", $utf8)

function Find-Python {
	# python3 first (Linux), python (Windows); skip the Microsoft Store stub in WindowsApps, which only prints an install hint.
	foreach ($n in @("python3", "python")) {
		foreach ($c in @(Get-Command $n -All -CommandType Application -ErrorAction SilentlyContinue)) {
			if ($c.Source -notmatch "WindowsApps") { return @{ exe = $c.Source; pre = "" } }
		}
	}
	$c = Get-Command "py" -ErrorAction SilentlyContinue
	if ($c) { return @{ exe = $c.Source; pre = "-3 " } }
	throw "Python 3 was not found (needed for tools/bench/summarize.py)."
}
$py = Find-Python

# ---- which variants run ----
$wanted = @($Variants -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
if ($Baseline -and ($wanted -notcontains "optifine-only")) { $wanted += "optifine-only" }
$known = @("origin-default", "optifine-only", "competitive", "balanced", "quality")
foreach ($v in $wanted) { if ($known -notcontains $v) { throw "Unknown variant '$v' (known: $($known -join ', '))." } }

$active = @()
$skipped = @()
if ($DryRun) {
	if ($FixtureDir -eq "") { $FixtureDir = Join-Path $PSScriptRoot "fixtures/dryrun" }
	if (-not (Test-Path $FixtureDir)) { throw "Fixture folder not found: $FixtureDir" }
	foreach ($v in $wanted) {
		if (Test-Path (Join-Path $FixtureDir $v)) { $active += $v } else { $skipped += [pscustomobject]@{ name = $v; why = "no fixture runs for it" } }
	}
} else {
	foreach ($v in $wanted) {
		if (@("origin-default", "optifine-only") -contains $v) { $active += $v; continue }
		if (Test-Path (Join-Path $presetDir "$v.json")) { $active += $v }
		else { $skipped += [pscustomobject]@{ name = $v; why = "preset file tools\bench\presets-189\$v.json does not exist yet (Phase 6.2)" } }
	}
}
if ($active.Count -eq 0) { throw "No variant can run." }
# No silent re-baseline: the report compares everything with origin-default. Missing it is an error unless
# the caller said so with -SkipOriginDefault.
if (($active -notcontains "origin-default") -and -not $SkipOriginDefault) {
	throw "origin-default is not among the variants that can run ($($active -join ', ')), so there is no baseline to compare with. Add it, or pass -SkipOriginDefault to compare against '$($active[0])' on purpose."
}
foreach ($s in $skipped) { Say "Skipping $($s.name): $($s.why)" }

# ---- this PC ----
function Get-Machine {
	if ($DryRun) {
		$m = Get-Content (Join-Path $FixtureDir "machine.json") -Raw | ConvertFrom-Json
		return [ordered]@{ gpu = $m.gpu; gpuDriver = $m.gpuDriver; monitor = $m.monitor; cpu = $m.cpu; cpuCores = $m.cpuCores
			ramGb = $m.ramGb; os = $m.os; powerPlan = $m.powerPlan; java = $m.java; build = $m.build; cores = 8 }
	}
	$gpu = Get-CimInstance Win32_VideoController | Where-Object { $_.Name -notmatch "Basic Display" } | Select-Object -First 1
	$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
	$os = Get-CimInstance Win32_OperatingSystem
	$ram = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
	$power = "unknown"
	try { $power = ((powercfg /getactivescheme) -join " ").Trim() } catch { }
	$commit = "?"
	try { Push-Location $repo; $commit = (git rev-parse --short HEAD).Trim() } catch { } finally { Pop-Location }
	return [ordered]@{ gpu = $gpu.Name; gpuDriver = $gpu.DriverVersion
		monitor = "$($gpu.CurrentHorizontalResolution)x$($gpu.CurrentVerticalResolution)"
		cpu = $cpu.Name.Trim(); cpuCores = "$($cpu.NumberOfCores) cores / $($cpu.NumberOfLogicalProcessors) threads"
		ramGb = $ram; os = "$($os.Caption) $($os.Version)"; powerPlan = $power; java = "(see stack.json of each run)"
		build = "this repo @ $commit"; cores = [int]$cpu.NumberOfLogicalProcessors }
}
$machine = Get-Machine
Say "PC: $($machine.gpu) (driver $($machine.gpuDriver)), $($machine.cpu), $($machine.ramGb) GB RAM"

function Get-CpuLoad {
	$s = (Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 3).CounterSamples | ForEach-Object { $_.CookedValue }
	return [math]::Round((($s | Measure-Object -Average).Average), 1)
}

# ---- rounds: both variants of a round are run before the next round; the order alternates ----
function Round-Order([string[]]$list, [int]$round) {
	$n = $list.Count
	$rot = @(); for ($i = 0; $i -lt $n; $i++) { $rot += $list[(($i + $round - 1) % $n)] }
	if ($round % 2 -eq 0) { [array]::Reverse($rot) }
	return $rot
}

function Write-Json($obj, [string]$path) { [System.IO.File]::WriteAllText($path, ($obj | ConvertTo-Json -Depth 6), $utf8) }

$runMeta = @()   # one entry per run: variant, run, status, cpu numbers, folder

function Place-Run([string]$variant, [int]$n, $meta) {
	# Counted runs stay run-<n>; a run another program disturbed becomes left-out-<n> (summarize.py ignores it).
	$dir = Join-Path (Join-Path $outRoot $variant) "run-$n"
	if ($meta.status -eq "ok" -and (($meta.cpuBeforePct -gt $MaxCpuLoad) -or ($meta.otherCpuPct -gt $MaxCpuLoad))) {
		$meta.status = "left-out-busy"
	}
	if ($meta.status -ne "ok" -and (Test-Path $dir)) {
		$new = Join-Path (Join-Path $outRoot $variant) "left-out-$n"
		if (Test-Path $new) { Remove-Item -Recurse -Force $new }
		Move-Item -LiteralPath $dir -Destination $new
		$dir = $new
	}
	$meta.dir = $dir
	return $meta
}

if ($DryRun) {
	Say "DRY RUN: using the synthetic fixture runs in $FixtureDir. No game starts and nothing is measured."
	foreach ($v in $active) {
		$vSrc = Join-Path $FixtureDir $v
		foreach ($rd in (Get-ChildItem $vSrc -Directory | Where-Object { $_.Name -like "run-*" } | Sort-Object Name)) {
			$n = [int]($rd.Name -replace "run-", "")
			if ($n -gt $Runs) { continue }
			$dst = Join-Path (Join-Path $outRoot $v) $rd.Name
			New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
			Copy-Item -Recurse -Force $rd.FullName $dst
			$m = [ordered]@{ variant = $v; run = $n; status = "ok"; cpuBeforePct = $null; cpuDuringPct = $null; gameCpuPct = $null; otherCpuPct = $null; dir = "" }
			$mp = Join-Path $dst "meta.json"
			if (Test-Path $mp) {
				$j = Get-Content $mp -Raw | ConvertFrom-Json
				$m.cpuBeforePct = $j.cpuBeforePct; $m.cpuDuringPct = $j.cpuDuringPct; $m.gameCpuPct = $j.gameCpuPct; $m.otherCpuPct = $j.otherCpuPct
			}
			$runMeta += (Place-Run $v $n $m)
		}
	}
} else {
	if ($env:OS -ne "Windows_NT") { throw "The live run is for Windows (your PC). Use -DryRun elsewhere; CI runs the Linux bench through tools/bench/prod189.py." }
	$others = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue)
	if ($others.Count -gt 0 -and -not $Force) {
		throw "A java process is already running ($($others.Count)). Close Minecraft and other Java programs first (or pass -Force): two games on one PC measure each other."
	}
	# The Origin jar a player would get.
	$libs = Join-Path $module "build\libs"
	if (-not $NoBuild) {
		Say "Building the Origin 1.8.9 jar (remapJar)..."
		Push-Location $module
		try { & (Join-Path $module "gradlew.bat") remapJar --console=plain | Out-Null; if ($LASTEXITCODE -ne 0) { throw "gradle remapJar failed (exit $LASTEXITCODE)" } } finally { Pop-Location }
	}
	if (-not (Test-Path $libs)) { throw "No jar folder: $libs (run without -NoBuild)." }
	$jar = Get-ChildItem $libs -Filter "originclient-1.8.9-*.jar" | Where-Object { $_.Name -notmatch "sources|dev" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
	if (-not $jar) { throw "No originclient-1.8.9-*.jar in $libs." }
	Say "Origin jar: $($jar.Name)"
	$machine.build = "$($jar.Name) ($($machine.build))"

	$work = Join-Path $OutDir "work"
	$cache = Join-Path $OutDir "cache"
	$sceneCount = ($Scenes -split ",").Count
	$limit = [int](300 + $sceneCount * ($Warmup + $Duration) * 2)

	for ($r = 1; $r -le $Runs; $r++) {
		foreach ($v in (Round-Order $active $r)) {
			$runOut = Join-Path (Join-Path $outRoot $v) "run-$r"
			New-Item -ItemType Directory -Force -Path $runOut | Out-Null
			$load = Get-CpuLoad
			$deadline = (Get-Date).AddMinutes($IdleWaitMinutes)
			while ($load -gt $MaxCpuLoad -and (Get-Date) -lt $deadline) {
				Say "Round $r $v`: PC busy ($load%), waiting for it to drop under $MaxCpuLoad%..."
				Start-Sleep -Seconds 30
				$load = Get-CpuLoad
			}
			Say "Round $r/$Runs $v`: PC load before = $load%. Starting the game (a window opens; don't touch it)."

			$variantArg = $(if ($v -eq "optifine-only") { "optifine-only" } else { "origin-default" })
			$argStr = $py.pre + "`"$prod`" run --variant $variantArg --origin-jar `"$($jar.FullName)`" --out `"$runOut`"" +
				" --scenes $Scenes --warmup $Warmup --duration $Duration --width $Width --height $Height" +
				" --work `"$work`" --cache `"$cache`" --timeout $limit --label `"this PC, real GPU`" --save-options"
			if (@("competitive", "balanced", "quality") -contains $v) { $argStr += " --preset `"$(Join-Path $presetDir "$v.json")`"" }

			$t0 = Get-Date
			$p = Start-Process -FilePath $py.exe -ArgumentList $argStr -WorkingDirectory $repo -NoNewWindow -PassThru
			$cpuSamples = @(); $javaCpu = @{}
			while (-not $p.WaitForExit(4000)) {
				$cpuSamples += (Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 1).CounterSamples[0].CookedValue
				foreach ($j in (Get-Process -Name java, javaw -ErrorAction SilentlyContinue)) {
					try { $javaCpu[$j.Id] = $j.TotalProcessorTime.TotalSeconds } catch { }
				}
				if (((Get-Date) - $t0).TotalSeconds -gt ($limit + 600)) { Say "Timed out; stopping."; & taskkill /PID $p.Id /T /F | Out-Null; break }
			}
			$secs = [math]::Max(1, ((Get-Date) - $t0).TotalSeconds)
			$during = $(if ($cpuSamples.Count -gt 0) { [math]::Round((($cpuSamples | Measure-Object -Average).Average), 1) } else { $null })
			$gameCpuSec = ($javaCpu.Values | Measure-Object -Sum).Sum
			$game = $(if ($null -ne $gameCpuSec) { [math]::Round($gameCpuSec / ($secs * $machine.cores) * 100, 1) } else { $null })
			$other = $(if ($null -ne $during -and $null -ne $game) { [math]::Max(0, [math]::Round($during - $game, 1)) } else { $null })
			$ok = Test-Path (Join-Path $runOut "bench.json")
			$m = [ordered]@{ variant = $v; run = $r; status = $(if ($ok) { "ok" } else { "failed" }); cpuBeforePct = $load; cpuDuringPct = $during
				gameCpuPct = $game; otherCpuPct = $other; samples = $cpuSamples.Count; dir = "" }
			Write-Json $m (Join-Path $runOut "meta.json")
			$m = Place-Run $v $r $m
			Say "Round $r $v`: $($m.status). PC load during = $during% (game $game%, other programs $other%)."
		}
	}
}

# ---- numbers: summarize.py does the maths from the raw frames ----
$label = $(if ($DryRun) { "DRY RUN, synthetic fixtures" } else { "this PC, real GPU" })
# origin-default is the baseline; only -SkipOriginDefault (checked above) lets it be another variant.
$baselineName = $(if ($active -contains "origin-default") { "origin-default" } elseif ($active -contains "optifine-only") { "optifine-only" } else { $active[0] })
# A variant whose every run was left out (busy PC, failed launch) has no run-<n> folder, so the summarizer would
# not even know it existed. Catch that here: a variant that was asked for and has no counted run is a failure.
$noRuns = @($active | Where-Object { @(Get-ChildItem -Path (Join-Path $outRoot $_) -Directory -Filter "run-*" -ErrorAction SilentlyContinue).Count -eq 0 })
if ($noRuns.Count -gt 0) {
	Say "NO COUNTED RUN for: $($noRuns -join ', ') (every run was busy, failed or missing; see left-out-* in $outRoot)."
	exit 2
}
$sumJson = Join-Path $OutDir "summary.json"
$sumMd = Join-Path $OutDir "summary.md"
# --require-valid: a variant whose runs were all invalid (unfocused / camera off / truncated) must fail, not print empty tables.
$sumArgs = $py.pre + "`"$summarizer`" aggregate `"$outRoot`" --out `"$sumJson`" --md `"$sumMd`" --label `"$label`" --baseline $baselineName --require-valid"
$sp = Start-Process -FilePath $py.exe -ArgumentList $sumArgs -NoNewWindow -Wait -PassThru -RedirectStandardOutput (Join-Path $OutDir "summarize.out.txt") -RedirectStandardError (Join-Path $OutDir "summarize.err.txt")
if ($sp.ExitCode -ne 0) {
	Say "SUMMARY FAILED (exit $($sp.ExitCode)): $((Get-Content (Join-Path $OutDir 'summarize.err.txt') -Raw))"
	exit 2
}
$agg = Get-Content $sumJson -Raw | ConvertFrom-Json

# ---- report.md, in plain words ----
$md = New-Object System.Text.StringBuilder
function L([string]$s) { [void]$md.AppendLine($s) }
L "# Origin 1.8.9 benchmark report"
L ""
if ($DryRun) {
	L "> **DRY RUN. Nothing here was measured.** These numbers come from made-up fixture files (tools\bench\fixtures\dryrun) that only test the report. Do not quote them."
	L ""
}
L "Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm'). Each number is the middle one (median) of the counted runs, with the lowest and highest run in brackets."
L ""
L "## This PC"
L ""
L "- Graphics card: $($machine.gpu), driver $($machine.gpuDriver), screen $($machine.monitor)"
L "- Processor: $($machine.cpu) ($($machine.cpuCores))"
L "- Memory: $($machine.ramGb) GB"
L "- Windows: $($machine.os); power plan: $($machine.powerPlan)"
L "- Build: $($machine.build)"
L ""
$names = @{ "origin-default" = "Origin default"; "optifine-only" = "OptiFine only"; "competitive" = "Competitive"; "balanced" = "Balanced"; "quality" = "Quality" }
$vnames = @($agg.variants.PSObject.Properties.Name)
$sceneNames = @()
foreach ($vn in $vnames) { foreach ($sn in $agg.variants.$vn.scenes.PSObject.Properties.Name) { if ($sceneNames -notcontains $sn) { $sceneNames += $sn } } }

L "## What was tested"
L ""
L "The game a player gets: Forge 11.15.1.2318 + OptiFine HD U M5 + TexFix + BetterFps + the Origin jar, started with the launcher's Java and memory settings, in a throw-away folder under artifacts\bench-189 (your launcher, worlds and options are not touched). Scenes: $($sceneNames -join ', ')."
L ""
if (-not $DryRun) { L "Each scene: $Warmup s warm-up, then $Duration s measured; game window $Width x $Height." ; L "" }
function VName([string]$v) {
	if ($v -eq "origin-default") { return "Origin as shipped" }
	if ($v -eq "optifine-only") { return "OptiFine only, Origin switched off" }
	return "Origin + the $v preset"
}
L ("Variants: " + (($active | ForEach-Object { VName $_ }) -join "; ") + ".")
if ($skipped.Count -gt 0) {
	L ""
	foreach ($s in $skipped) { L "- Not run: $($s.name) ($($s.why))." }
}
L ""

function Cell($spread, [string]$f) {
	if ($null -eq $spread) { return "-" }
	return "$(Fmt $spread.median $f) ($(Fmt $spread.min $f)-$(Fmt $spread.max $f))"
}
function Pct($row, [string]$name) {
	if ($null -eq $row) { return "-" }
	$v = $row.$name
	if ($null -eq $v) { return "-" }
	return ([double]$v).ToString("+0.0;-0.0;0.0", $inv) + "%"
}

L "## Results"
L ""
L "Columns: **Avg FPS** = frames per second (higher is better). **Worst 1%** and **worst 0.1%** = the speed of the slowest 1% / 0.1% of frames: the hitching you feel (higher is better). **Typical frame** and **slow frame (99th)** = how long a frame takes, in milliseconds (lower is better). **Wobble** = how much frame times vary (lower is smoother). **Memory** = average Java heap in use. **GC pause** = time the game froze for cleanup, total and longest. **Alloc** = memory the render thread creates per second (lower means less cleanup)."
L ""
foreach ($sn in $sceneNames) {
	L "### Scene: $sn"
	L ""
	L "| Variant | Counted runs | Avg FPS | Worst 1% FPS | Worst 0.1% FPS | Typical frame ms | Slow frame (99th) ms | Wobble ms | Memory MB | GC pause total ms | GC pause longest ms | Alloc MB/s |"
	L "|---|---|---|---|---|---|---|---|---|---|---|---|"
	foreach ($vn in $vnames) {
		$m = $agg.variants.$vn.scenes.$sn
		if ($null -eq $m) { continue }
		L "| $($names[$vn]) | $($m.runs) | $(Cell $m.avgFps '0.0') | $(Cell $m.low1Fps '0.0') | $(Cell $m.low01Fps '0.0') | $(Cell $m.p50Ms '0.00') | $(Cell $m.p99Ms '0.00') | $(Cell $m.stdevMs '0.00') | $(Cell $m.heapAvgMb '0') | $(Cell $m.gcPauseTotalMs '0') | $(Cell $m.gcPauseMaxMs '0') | $(Cell $m.allocMbPerSec '0.0') |"
	}
	L ""
	$others = @($vnames | Where-Object { $_ -ne $agg.baseline })
	if ($others.Count -gt 0) {
		L "Change against **$($names[$agg.baseline])** (median to median; for FPS a plus is faster, for the frame-time and GC columns a minus is better):"
		L ""
		L "| Variant | Avg FPS | Worst 1% FPS | Slow frame (99th) | Wobble | GC pause total | Alloc |"
		L "|---|---|---|---|---|---|---|"
		foreach ($vn in $others) {
			$row = $agg.deltaPctVsBaseline.$vn.$sn
			if ($null -eq $row) { continue }
			L "| $($names[$vn]) | $(Pct $row 'avgFps') | $(Pct $row 'low1Fps') | $(Pct $row 'p99Ms') | $(Pct $row 'stdevMs') | $(Pct $row 'gcPauseTotalMs') | $(Pct $row 'allocMbPerSec') |"
		}
		L ""
	}
}

L "## Was the PC quiet? (idle CPU before and during each run)"
L ""
L "Other programs using the CPU make a run slower for reasons that have nothing to do with Origin. Before each run the PC load is read; during it the load is sampled and the game's own share is taken off, leaving what other programs used. A run is not counted if other programs used more than $MaxCpuLoad%."
L ""
foreach ($m in ($runMeta | Sort-Object { $_.variant }, { $_.run })) {
	$state = switch ($m.status) { "ok" { "Counted" } "left-out-busy" { "NOT COUNTED: the PC was too busy" } default { "FAILED: no results (see the run's game.log)" } }
	if ($null -eq $m.cpuBeforePct) {
		L "- $($names[$m.variant]), run $($m.run): no CPU reading ($state)."
	} else {
		L "- $($names[$m.variant]), run $($m.run): PC was $(Fmt $m.cpuBeforePct '0')% busy before and $(Fmt $m.cpuDuringPct '0')% during (the game used $(Fmt $m.gameCpuPct '0')%, other programs $(Fmt $m.otherCpuPct '0')%). $state."
	}
}
L ""
$notCounted = @($runMeta | Where-Object { $_.status -ne "ok" })
if ($notCounted.Count -gt 0) {
	L "**$($notCounted.Count) run(s) did not count.** Their files are kept in the left-out-<n> folders under out\<variant>. Close other programs and run again if a variant has too few runs."
	L ""
}

L "## What these numbers mean"
L ""
L "- They describe this PC and this graphics card only. A slower PC or card will show different numbers; nothing here is an estimate for other machines."
L "- Differences of a few percent between variants are inside the run-to-run noise: look at the (lowest-highest) brackets before believing a small change."
L "- The scenes are scripted (fixed world, fixed camera path, same window size) so every variant sees the same thing. The 'menu' scene is the title/inventory style screen; 'flythrough' is the moving camera."
L "- Raw per-frame data for every run: out\<variant>\run-<n>\frames-<scene>.csv. Everything is re-derived from it by tools\bench\summarize.py."
$report = Join-Path $OutDir "report.md"
[System.IO.File]::WriteAllText($report, $md.ToString(), $utf8)

Write-Host ""
Write-Host $md.ToString()
Say "Report: $report"
$counted = @($runMeta | Where-Object { $_.status -eq "ok" })
if ($counted.Count -eq 0) {
	Say "No run counted. Close other heavy programs and run again."
	exit 1
}
exit 0
