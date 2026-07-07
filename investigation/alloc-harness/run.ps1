# Allocation/time harness runner. See ../README.md.
#   ./run.ps1 -Tag old          measure the 1.16.2 baseline
#   ./run.ps1 -Tag main         measure locally built unmodified-main jars
#   ./run.ps1 -Tag new -New     measure prototype jars including new-API-only scenarios
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [switch]$New
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$cp = (Get-ChildItem "../libs-$Tag/*.jar" | ForEach-Object { $_.FullName }) -join ';'
$out = "out-$Tag"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory $out | Out-Null

$sources = @('src/AllocBench.java')
if ($New) { $sources += 'src-new/AllocBenchNew.java' }
javac -nowarn -cp $cp -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

foreach ($s in @('cached_sample', 'lifecycle_ltt', 'lifecycle_noltt', 'tags_convert')) {
    java '-XX:+UseParallelGC' -Xms512m -Xmx512m -cp "$out;$cp" AllocBench $s
    if ($LASTEXITCODE -ne 0) { throw "run failed: $s" }
}
if ($New) {
    foreach ($s in @('tags_of_kv', 'tags_convert5', 'id_gettags')) {
        java '-XX:+UseParallelGC' -Xms512m -Xmx512m -cp "$out;$cp" AllocBenchNew $s
        if ($LASTEXITCODE -ne 0) { throw "run failed: $s" }
    }
}
