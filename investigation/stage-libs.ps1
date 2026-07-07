# Stages the jars the compat fixture and allocation harness run against.
#   ./stage-libs.ps1 old    downloads the 1.16.2 baseline jars from Maven Central
#   ./stage-libs.ps1 new    copies the locally built jars (run ./gradlew ... jar first)
#   ./stage-libs.ps1 main   like new, but into libs-main (build unmodified main first)
param([Parameter(Mandatory = $true)][ValidateSet('old', 'new', 'main')][string]$Target)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if ($Target -eq 'old') {
    New-Item -ItemType Directory -Force libs-old | Out-Null
    foreach ($artifact in @('micrometer-core', 'micrometer-commons', 'micrometer-observation')) {
        $jar = "libs-old/$artifact-1.16.2.jar"
        if (-not (Test-Path $jar)) {
            Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/io/micrometer/$artifact/1.16.2/$artifact-1.16.2.jar" -OutFile $jar
        }
    }
    if (-not (Test-Path 'libs-old/jspecify-1.0.0.jar')) {
        Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar' -OutFile 'libs-old/jspecify-1.0.0.jar'
    }
    Get-ChildItem libs-old
}
else {
    ./stage-libs.ps1 old | Out-Null # for the jspecify jar
    $dir = "libs-$Target"
    New-Item -ItemType Directory -Force $dir | Out-Null
    Remove-Item "$dir/*.jar" -Force -ErrorAction SilentlyContinue
    foreach ($artifact in @('micrometer-commons', 'micrometer-core', 'micrometer-observation')) {
        # newest jar only, in case build/libs still holds jars of older versions
        $jar = Get-ChildItem "../$artifact/build/libs/$artifact-*.jar" |
            Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -eq $jar) { throw "no $artifact jar found; run ./gradlew :${artifact}:jar first" }
        Copy-Item $jar.FullName $dir
    }
    Copy-Item libs-old/jspecify-1.0.0.jar $dir
    Get-ChildItem $dir
}
