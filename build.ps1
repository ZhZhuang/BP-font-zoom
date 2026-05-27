# Build script for bFontZoom - Burp Suite Font Zoom Extension
# Uses the JDK bundled with Burp Suite Portable edition
# Usage: build.ps1

$ErrorActionPreference = "Stop"
$ROOT = $PSScriptRoot   # Directory containing this script (no -Parent)
$JDK  = "D:\program\BurpSuite\jdk-24.0.2"   # Bundled JDK with Burp Suite Portable
$API  = "$ROOT\lib\montoya-api-2026.4.jar"
$SRC  = "$ROOT\src\main\java"
$OUT  = "$ROOT\target\classes"
$JAR  = "$ROOT\target\bFontZoom-1.0.0.jar"
$MAN  = "$ROOT\MANIFEST.MF"

Write-Host "=== bFontZoom Build ==="

# Verify JDK exists
if (-not (Test-Path "$JDK\bin\javac.exe")) {
    Write-Host "ERROR: JDK not found at $JDK"
    exit 1
}

# Download API JAR if missing
if (-not (Test-Path $API)) {
    Write-Host "Downloading Montoya API..."
    Invoke-WebRequest "https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/2026.4/montoya-api-2026.4.jar" -OutFile $API -UseBasicParsing
    Write-Host "Done."
}

# Clean output
if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Path $OUT -Force | Out-Null

# Compile
Write-Host "Compiling..."
$JAVAFILES = Get-ChildItem "$SRC\exp\fontzoom" -Filter *.java -Recurse
& "$JDK\bin\javac.exe" -cp $API -encoding UTF-8 -d $OUT @($JAVAFILES.FullName)

if ($LASTEXITCODE -ne 0) {
    Write-Host "COMPILATION FAILED"
    exit 1
}

# Create manifest (use .NET for cross-version compatibility)
[System.IO.File]::WriteAllText($MAN, "Manifest-Version: 1.0`r`nBurp-Extender-Class: exp.fontzoom.FontZoomExtender`r`n")

# Package JAR
Write-Host "Packaging..."
& "$JDK\bin\jar.exe" cfm $JAR $MAN -C "$OUT" "."

Write-Host "`n=== BUILD SUCCESS ==="
Write-Host "Output: $JAR ($((Get-Item $JAR).Length) bytes)"
Write-Host "Place this JAR in Burp Suite -> Extender -> Extensions -> Add"
