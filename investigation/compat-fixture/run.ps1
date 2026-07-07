# Binary/source compatibility fixture runner. See ../README.md.
# Compiles the fixture ONCE against the old (1.16.2) jars, runs the resulting classes
# against both old and new jars, recompiles the shared sources against the new jars,
# and finally compiles each source-compatibility probe against old and new jars.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$cpOld = (Get-ChildItem '../libs-old/*.jar' | ForEach-Object { $_.FullName }) -join ';'
$cpNew = (Get-ChildItem '../libs-new/*.jar' | ForEach-Object { $_.FullName }) -join ';'

function Banner($text) { Write-Output "`n===== $text =====" }

Banner 'compile fixture against OLD (1.16.2) jars'
if (Test-Path out-old) { Remove-Item -Recurse -Force out-old }
New-Item -ItemType Directory out-old | Out-Null
javac --release 8 -nowarn -cp $cpOld -d out-old (Get-ChildItem src/*.java, src-oldonly/*.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw 'compile against old jars failed' }
Write-Output 'compiled OK'

Banner 'run old-compiled classes on OLD jars'
java -cp "out-old;$cpOld" CompatMain old old
if ($LASTEXITCODE -ne 0) { throw 'CompatMain failed on old jars' }
java -cp "out-old;$cpOld" RegistryOverrideMain old
if ($LASTEXITCODE -ne 0) { throw 'RegistryOverrideMain failed on old jars' }

Banner 'run old-compiled classes on NEW (prototype) jars'
java -cp "out-old;$cpNew" CompatMain new old
if ($LASTEXITCODE -ne 0) { throw 'CompatMain (old-compiled) failed on new jars' }
java -cp "out-old;$cpNew" RegistryOverrideMain new
if ($LASTEXITCODE -ne 0) { throw 'RegistryOverrideMain (old-compiled) failed on new jars' }

Banner 'recompile shared sources against NEW jars and run'
if (Test-Path out-new) { Remove-Item -Recurse -Force out-new }
New-Item -ItemType Directory out-new | Out-Null
javac --release 8 -nowarn -cp $cpNew -d out-new (Get-ChildItem src/*.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw 'recompile against new jars failed' }
java -cp "out-new;$cpNew" CompatMain new new
if ($LASTEXITCODE -ne 0) { throw 'CompatMain (new-compiled) failed on new jars' }

Banner 'source compatibility survey'
$probes = @(Get-ChildItem src-probes/*.java | ForEach-Object { $_.FullName }) + @((Resolve-Path 'src-oldonly/MyRegistry.java').Path)
foreach ($probe in $probes) {
    $name = Split-Path -Leaf $probe
    if (Test-Path out-probe) { Remove-Item -Recurse -Force out-probe }
    New-Item -ItemType Directory out-probe | Out-Null

    javac --release 8 -nowarn -cp $cpOld -d out-probe $probe 2>$null | Out-Null
    $oldOk = ($LASTEXITCODE -eq 0)

    $newOutput = javac --release 8 '-Xlint:deprecation,-options' -cp $cpNew -d out-probe $probe 2>&1 | Out-String
    $newOk = ($LASTEXITCODE -eq 0)

    Write-Output "PROBE ${name}: compiles-against-old=$oldOk compiles-against-new=$newOk"
    if ($newOutput.Trim().Length -gt 0) {
        Write-Output "--- javac output against new jars for ${name} ---"
        Write-Output $newOutput.TrimEnd()
    }
}
Write-Output "`nfixture run complete"
exit 0
