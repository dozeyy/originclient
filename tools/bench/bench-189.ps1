<#
.SYNOPSIS
  Origin 1.8.9 benchmark: one command, real GPU, plain-English report.

.DESCRIPTION
  From a fresh PowerShell:
      cd "C:\Users\Will\Documents\Minecraft Mods\Origin Client"
      powershell -ExecutionPolicy Bypass -File tools\bench\bench-189.ps1

  What it does, per run:
    1. Checks how busy the CPU is (other builds running skew the numbers) and waits a
       little for it to calm down. A run that starts or ends on a busy PC is kept on disk
       but left out of the summary, and the summary says so.
    2. Backs up the dev game's options.txt and Origin config, starts the 1.8.9 game with
       benchmark mode on (OriginBench.java), and lets it run every scene by itself:
       idle, hud, particles, menu, flythrough. A Minecraft window opens; don't touch it.
       The game quits by itself when done.
    3. Puts the backed-up files back.
  After all runs it writes summary.md / summary.json (median of the good runs, with the
  min-max spread) next to each run's own bench.md / bench.json and prints the summary.

  Which game it runs: the DEV client (gradlew runClient in src/mods/versions/1.8.9),
  without OptiFine. The real Origin install cannot be benchmarked by this script yet: the
  launcher has no way to add -Dorigin.bench to a launch, and Forge 1.8.9 + OptiFine only
  run from a launcher-built instance. The report says which one ran.

  It never touches worlds or accounts. The benchmark creates its own fresh world
  (OriginBench-<time>) in the dev game's run\saves each run.

.PARAMETER Runs        Runs of the whole scene set (default 3).
.PARAMETER Scenarios   "all" or a comma list: idle,hud,particles,menu,flythrough.
.PARAMETER Warmup      Seconds of warm-up per scene before measuring (default 10).
.PARAMETER Duration    Measured seconds per scene (default 30).
.PARAMETER OutDir      Where reports go (default tools\bench\results\1.8.9-<time>).
.PARAMETER MaxCpuLoad  A run is left out of the summary if the PC was busier than this (% of
                       all cores) just before or just after it (default 20).
.PARAMETER IdleWaitMinutes  How long to wait for a busy PC to calm down before each run (default 5).
.PARAMETER Shots       Also save one screenshot per scene (taken during warm-up).
#>
param(
	[int]$Runs = 3,
	[string]$Scenarios = "all",
	[double]$Warmup = 10,
	[double]$Duration = 30,
	[string]$OutDir = "",
	[double]$MaxCpuLoad = 20,
	[double]$IdleWaitMinutes = 5,
	[switch]$Shots
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$module = Join-Path $repo "src\mods\versions\1.8.9"
if (-not (Test-Path (Join-Path $module "gradlew.bat"))) {
	throw "Run this from the Origin Client repo (couldn't find $module\gradlew.bat)."
}
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ($OutDir -eq "") {
	$OutDir = Join-Path $repo "tools\bench\results\1.8.9-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

function Say([string]$msg) { Write-Host "[bench] $msg" }

# ---- which game: the real install, or the dev client ----
$realInstance = Join-Path $env:LOCALAPPDATA "OriginLauncher\instances\1.8.9"
if (Test-Path $realInstance) {
	$modeReason = "an Origin 1.8.9 install exists, but the launcher can't pass the benchmark switch to it yet"
} else {
	$modeReason = "no Origin 1.8.9 install on this PC ($realInstance)"
}
Say "Game: DEV client, no OptiFine ($modeReason)."

# ---- this PC ----
$gpu = Get-CimInstance Win32_VideoController | Where-Object { $_.Name -notmatch "Basic Display" } | Select-Object -First 1
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$ramGb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
$power = ""
try { $power = ((powercfg /getactivescheme) -join " ").Trim() } catch { $power = "unknown" }
$jarVersion = ((Get-Content (Join-Path $module "gradle.properties")) | Where-Object { $_ -match "^mod_version=" }) -replace "mod_version=", ""
$commit = ""
try { Push-Location $repo; $commit = (git rev-parse --short HEAD).Trim() } catch { $commit = "?" } finally { Pop-Location }
$pc = [ordered]@{
	gpu = $gpu.Name
	gpuDriver = $gpu.DriverVersion
	monitor = "$($gpu.CurrentHorizontalResolution)x$($gpu.CurrentVerticalResolution)"
	cpu = $cpu.Name.Trim()
	cpuCores = "$($cpu.NumberOfCores) cores / $($cpu.NumberOfLogicalProcessors) threads"
	ramGb = $ramGb
	powerPlan = $power
	game = "dev client (gradlew runClient), OptiFine off"
	gameReason = $modeReason
	build = "originclient-1.8.9 $jarVersion @ $commit"
}
Say "PC: $($pc.gpu) (driver $($pc.gpuDriver)), $($pc.cpu), $ramGb GB RAM"

# The launcher's JVM setup for a 1.8.9 player on this PC (RamAdvice.LegacyAutoMb = 4096 MB,
# JvmArgPresets.LegacySmallHeapFlags), so the dev game runs with the same heap and GC.
$jvm = "-Xms4096M -Xmx4096M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
	"-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=25 -XX:G1MaxNewSizePercent=35 " +
	"-XX:G1HeapRegionSize=4M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 " +
	"-XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 " +
	"-XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"

# Files the benchmark changes while it runs (it puts them back itself too; this is the safety net).
$runDir = Join-Path $module "run"
$guarded = @("options.txt", "optionsof.txt", "config\originclient-mods.json", "config\originclient.json")

function Get-CpuLoad {
	$s = (Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 3).CounterSamples |
		ForEach-Object { $_.CookedValue }
	return [math]::Round((($s | Measure-Object -Average).Average), 1)
}

function Median([double[]]$xs) {
	if ($xs.Count -eq 0) { return $null }
	$s = $xs | Sort-Object
	$m = [int][math]::Floor($s.Count / 2)
	if ($s.Count % 2 -eq 1) { return $s[$m] }
	return ($s[$m - 1] + $s[$m]) / 2
}

$runsInfo = @()
for ($i = 1; $i -le $Runs; $i++) {
	$runOut = Join-Path $OutDir "run-$i"
	New-Item -ItemType Directory -Force -Path $runOut | Out-Null

	# CPU check (other builds on this PC skew the numbers)
	$load = Get-CpuLoad
	$deadline = (Get-Date).AddMinutes($IdleWaitMinutes)
	while ($load -gt $MaxCpuLoad -and (Get-Date) -lt $deadline) {
		Say "Run $i/$Runs`: CPU busy ($load%), waiting for it to drop under $MaxCpuLoad%..."
		Start-Sleep -Seconds 30
		$load = Get-CpuLoad
	}
	Say "Run $i/$Runs`: CPU load before = $load%. Starting the game (a window opens; don't touch it)."

	# Back up what the run may change
	$backup = Join-Path $runOut "backup"
	New-Item -ItemType Directory -Force -Path $backup | Out-Null
	$saved = @{}
	foreach ($rel in $guarded) {
		$src = Join-Path $runDir $rel
		if (Test-Path $src) {
			$dst = Join-Path $backup ($rel -replace "[\\/]", "__")
			Copy-Item -LiteralPath $src -Destination $dst -Force
			$saved[$rel] = $dst
		}
	}

	$env:ORIGIN_BENCH = $Scenarios
	$env:ORIGIN_BENCH_WARMUP = "$Warmup"
	$env:ORIGIN_BENCH_DURATION = "$Duration"
	$env:ORIGIN_BENCH_OUT = $runOut
	$env:ORIGIN_BENCH_BUILD = $pc.build
	$env:ORIGIN_BENCH_JVM = $jvm
	$env:ORIGIN_BENCH_SHOTS = $(if ($Shots) { "1" } else { "0" })
	$log = Join-Path $runOut "game.log"
	$ok = $false
	try {
		# One scene set takes about (settle 15 s + scenes x (warm-up + duration)) plus boot.
		$sceneCount = $(if ($Scenarios -eq "all") { 5 } else { ($Scenarios -split ",").Count })
		$limit = [int](300 + $sceneCount * ($Warmup + $Duration) * 2)
		# Full quoted paths (the repo path has spaces); cmd /c strips the outer pair of quotes.
		$tmp = Join-Path $module "game.log.tmp"
		$gradlew = Join-Path $module "gradlew.bat"
		$cmdLine = "/c `"`"$gradlew`" runClient --console=plain > `"$tmp`" 2>&1`""
		$p = Start-Process -FilePath "cmd.exe" -ArgumentList $cmdLine -WorkingDirectory $module -NoNewWindow -PassThru
		if (-not $p.WaitForExit($limit * 1000)) {
			Say "Run $i timed out after $limit s; stopping it."
			& taskkill /PID $p.Id /T /F | Out-Null
			# The game itself is started by the Gradle daemon, outside cmd's process tree:
			# stop only the java process running THIS module's dev client.
			Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
				Where-Object { $_.CommandLine -and $_.CommandLine.Contains($module) -and $_.CommandLine.Contains("dli.env=client") } |
				ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
		}
		if (Test-Path $tmp) { Move-Item -LiteralPath $tmp -Destination $log -Force }
		$ok = Test-Path (Join-Path $runOut "bench.json")
	} finally {
		foreach ($k in @("ORIGIN_BENCH", "ORIGIN_BENCH_WARMUP", "ORIGIN_BENCH_DURATION", "ORIGIN_BENCH_OUT",
				"ORIGIN_BENCH_BUILD", "ORIGIN_BENCH_JVM", "ORIGIN_BENCH_SHOTS")) {
			Remove-Item "Env:$k" -ErrorAction SilentlyContinue
		}
		foreach ($rel in $saved.Keys) {
			Copy-Item -LiteralPath $saved[$rel] -Destination (Join-Path $runDir $rel) -Force
		}
	}
	$loadAfter = Get-CpuLoad
	$busy = ($load -gt $MaxCpuLoad) -or ($loadAfter -gt $MaxCpuLoad)
	if (-not $ok) {
		Say "Run $i FAILED: no bench.json. Game log: $log"
		$runsInfo += [pscustomobject]@{ run = $i; ok = $false; cpuBefore = $load; cpuAfter = $loadAfter; busy = $busy; dir = $runOut; data = $null }
		continue
	}
	$data = Get-Content (Join-Path $runOut "bench.json") -Raw | ConvertFrom-Json
	$note = $(if ($busy) { " (PC was busy: left out of the summary)" } else { "" })
	Say "Run $i done, CPU load after = $loadAfter%$note"
	foreach ($r in $data.scenarios) {
		Say ("  {0,-11} avg {1,7} fps   1% low {2,7}   0.1% low {3,7}   p99 {4,6} ms   valid {5}" -f $r.scenario, $r.avgFps, $r.low1Fps, $r.low01Fps, $r.p99Ms, $r.valid)
	}
	$runsInfo += [pscustomobject]@{ run = $i; ok = $true; cpuBefore = $load; cpuAfter = $loadAfter; busy = $busy; dir = $runOut; data = $data }
}

# ---- summary across runs ----
$good = @($runsInfo | Where-Object { $_.ok -and -not $_.busy })
$metrics = @("avgFps", "low1Fps", "low01Fps", "p50Ms", "p95Ms", "p99Ms", "stutters", "hitches20ms", "heapAvgMb", "heapMaxMb", "gcCount", "gcTimeMs", "renderThreadAllocMbPerSec")
$sceneNames = @()
foreach ($r in $runsInfo) { if ($r.ok) { foreach ($s in $r.data.scenarios) { if ($sceneNames -notcontains $s.scenario) { $sceneNames += $s.scenario } } } }
$summary = [ordered]@{}
foreach ($sc in $sceneNames) {
	$rows = @()
	foreach ($r in $good) { $rows += @($r.data.scenarios | Where-Object { $_.scenario -eq $sc -and $_.valid }) }
	$entry = [ordered]@{ runs = $rows.Count }
	foreach ($m in $metrics) {
		$vals = [double[]]@($rows | ForEach-Object { [double]$_.$m })
		if ($vals.Count -gt 0) {
			$entry[$m] = [ordered]@{ median = (Median $vals); min = ($vals | Measure-Object -Minimum).Minimum; max = ($vals | Measure-Object -Maximum).Maximum }
		}
	}
	$summary[$sc] = $entry
}
$env0 = $null
foreach ($r in $runsInfo) { if ($r.ok) { $env0 = $r.data.environment; break } }

$runList = @($runsInfo | ForEach-Object {
	[ordered]@{ run = $_.run; ok = $_.ok; cpuBeforePct = $_.cpuBefore; cpuAfterPct = $_.cpuAfter; leftOutBusy = $_.busy; dir = $_.dir }
})
$json = [ordered]@{ pc = $pc; game = $env0; runs = $runList; summary = $summary }
$utf8 = New-Object System.Text.UTF8Encoding($false)   # no BOM, so any JSON reader takes it
[System.IO.File]::WriteAllText((Join-Path $OutDir "summary.json"), ($json | ConvertTo-Json -Depth 8), $utf8)

function Cell($e, $m, $fmt) {
	if ($null -eq $e -or -not $e.Contains($m)) { return "-" }
	$v = $e[$m]
	return ("{0:$fmt} ({1:$fmt}-{2:$fmt})" -f $v.median, $v.min, $v.max)
}
$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# Origin 1.8.9 benchmark summary")
[void]$md.AppendLine("")
[void]$md.AppendLine("$(Get-Date -Format 'yyyy-MM-dd HH:mm'). $($good.Count) of $Runs runs counted. Each cell: median of the counted runs (min-max).")
[void]$md.AppendLine("")
[void]$md.AppendLine("**Game:** $($pc.game). Why not the real install: $($pc.gameReason).")
[void]$md.AppendLine("")
[void]$md.AppendLine("| Scene | Runs | Avg FPS | 1% low | 0.1% low | Frame p50 ms | Frame p99 ms | Stutters | Hitches 20ms+ | Heap avg MB | GC count |")
[void]$md.AppendLine("|---|---|---|---|---|---|---|---|---|---|---|")
foreach ($sc in $sceneNames) {
	$e = $summary[$sc]
	[void]$md.AppendLine("| $sc | $($e.runs) | $(Cell $e 'avgFps' 'N0') | $(Cell $e 'low1Fps' 'N0') | $(Cell $e 'low01Fps' 'N0') | $(Cell $e 'p50Ms' 'N2') | $(Cell $e 'p99Ms' 'N2') | $(Cell $e 'stutters' 'N0') | $(Cell $e 'hitches20ms' 'N0') | $(Cell $e 'heapAvgMb' 'N0') | $(Cell $e 'gcCount' 'N0') |")
}
[void]$md.AppendLine("")
[void]$md.AppendLine("## Runs")
[void]$md.AppendLine("")
foreach ($r in $runsInfo) {
	$state = $(if (-not $r.ok) { "FAILED (see game.log)" } elseif ($r.busy) { "left out: PC was busy" } else { "counted" })
	[void]$md.AppendLine("- Run $($r.run): $state. CPU load before $($r.cpuBefore)%, after $($r.cpuAfter)% (limit $MaxCpuLoad%). $($r.dir)")
}
[void]$md.AppendLine("")
[void]$md.AppendLine("## This PC")
[void]$md.AppendLine("")
foreach ($k in $pc.Keys) { [void]$md.AppendLine("- $k`: $($pc[$k])") }
if ($env0) {
	[void]$md.AppendLine("")
	[void]$md.AppendLine("## Game settings during the benchmark")
	[void]$md.AppendLine("")
	foreach ($k in @("resolution", "fullscreen", "optifine", "java", "heapLimitMb", "renderDistance", "maxFps", "vsync", "graphics", "smoothLighting", "particles", "guiScale", "useVbo", "mipmaps", "menuBlur", "hudOnOutsideHudScene", "warmupS", "durationS", "seed")) {
		[void]$md.AppendLine("- $k`: $($env0.$k)")
	}
}
[void]$md.AppendLine("")
[void]$md.AppendLine("Avg FPS = frames per second. 1% / 0.1% low = the average FPS of the slowest 1% / 0.1% of frames (the hitching you feel). Stutters = frames over twice the typical frame time. Hitches = frames over 20 ms.")
$summaryMd = Join-Path $OutDir "summary.md"
[System.IO.File]::WriteAllText($summaryMd, $md.ToString(), $utf8)

Write-Host ""
Write-Host $md.ToString()
Say "Report: $summaryMd"
if ($good.Count -eq 0) {
	Say "No run counted (all failed or the PC was busy). Close other heavy programs and run it again."
	exit 1
}
