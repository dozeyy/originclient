# Compiles ShaderCompat (pure Java, no Minecraft types) with the standalone check
# and runs it. Needs a JDK (javac) on PATH or JAVA_HOME. Exit 0 = pass.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'src\main\java\com\origin\client\client\shaders\ShaderCompat.java'
$chk = Join-Path $PSScriptRoot 'CheckShaderAllowlist.java'
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('shadercheck-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out | Out-Null
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
& $javac -source 8 -target 8 -Xlint:-options -d $out $src $chk
if ($LASTEXITCODE -ne 0) { exit 1 }
& $java -cp $out CheckShaderAllowlist
$code = $LASTEXITCODE
Remove-Item -Recurse -Force $out
exit $code
