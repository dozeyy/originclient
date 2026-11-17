param([string]$Out, [string]$Stop)
# 1 Hz: epoch ms, total CPU %, then pid=3D-engine-util% for every process using the 3D engine (sum of its engines).
$paths = '\GPU Engine(*engtype_3D)\Utilization Percentage', '\Processor(_Total)\% Processor Time'
while (-not (Test-Path $Stop)) {
  try {
    $s = Get-Counter $paths -ErrorAction Stop
    $ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $cpu = 0; $by = @{}
    foreach ($c in $s.CounterSamples) {
      if ($c.Path -like '*processor*') { $cpu = [math]::Round($c.CookedValue, 1); continue }
      if ($c.InstanceName -match 'pid_(\d+)_') { $p = $Matches[1]; $by[$p] = ($by[$p] + $c.CookedValue) }
    }
    $parts = ($by.GetEnumerator() | Where-Object { $_.Value -ge 1 } | ForEach-Object { "$($_.Key)=$([math]::Round($_.Value,1))" }) -join ' '
    Add-Content -Path $Out -Value "$ms $cpu $parts"
  } catch { Start-Sleep -Milliseconds 500 }
}
