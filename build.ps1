# Builds KozyrChunkyQueue.jar without any build system (Maven/Gradle) - just javac + jar.
# Downloads compile-time-only dependencies into .deps/ (git-ignored) on first run.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$PaperApiVersion = if ($env:PAPER_API_VERSION) { $env:PAPER_API_VERSION } else { "26.2.build.87-stable" }
$ChunkyVersionId = if ($env:CHUNKY_VERSION_ID) { $env:CHUNKY_VERSION_ID } else { "MdY6JATr" }  # Modrinth version id for Chunky-Bukkit-1.5.3.jar
$AdventureVersion = "5.2.0"
$BungeecordChatVersion = "1.21-R0.2-deprecated+build.21"
$AnnotationsVersion = "26.0.1"

$DepsDir = ".deps"
$OutDir = "out"
$Javac = if ($env:JAVAC) { $env:JAVAC } else { "javac" }
$JarExe = if ($env:JAR_EXE) { $env:JAR_EXE } else { "jar" }

New-Item -ItemType Directory -Force -Path $DepsDir, "$OutDir\classes", "build" | Out-Null

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-IfMissing($Url, $Dest) {
    if (Test-Path $Dest) { return }
    Write-Output "Downloading $(Split-Path $Dest -Leaf)..."
    curl.exe -sfL $Url -o $Dest
    if ($LASTEXITCODE -ne 0) { throw "Failed to download $Url" }
}

Get-IfMissing "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/$PaperApiVersion/paper-api-$PaperApiVersion.jar" "$DepsDir\paper-api.jar"
Get-IfMissing "https://api.modrinth.com/v2/version/$ChunkyVersionId" "$DepsDir\chunky-version.json"
$chunkyMeta = Get-Content "$DepsDir\chunky-version.json" -Raw | ConvertFrom-Json
$chunkyUrl = ($chunkyMeta.files | Where-Object { $_.filename -like "Chunky-Bukkit*" } | Select-Object -First 1).url
Get-IfMissing $chunkyUrl "$DepsDir\chunky-bukkit.jar"
Get-IfMissing "https://repo.papermc.io/repository/maven-public/net/kyori/adventure-api/$AdventureVersion/adventure-api-$AdventureVersion.jar" "$DepsDir\adventure-api.jar"
Get-IfMissing "https://repo.papermc.io/repository/maven-public/net/kyori/adventure-key/$AdventureVersion/adventure-key-$AdventureVersion.jar" "$DepsDir\adventure-key.jar"
Get-IfMissing "https://repo.papermc.io/repository/maven-public/net/md-5/bungeecord-chat/$BungeecordChatVersion/bungeecord-chat-$BungeecordChatVersion.jar" "$DepsDir\bungeecord-chat.jar"
Get-IfMissing "https://repo1.maven.org/maven2/org/jetbrains/annotations/$AnnotationsVersion/annotations-$AnnotationsVersion.jar" "$DepsDir\annotations.jar"

$cp = @("$DepsDir\paper-api.jar", "$DepsDir\chunky-bukkit.jar", "$DepsDir\adventure-api.jar", "$DepsDir\adventure-key.jar", "$DepsDir\bungeecord-chat.jar", "$DepsDir\annotations.jar") -join ";"

Remove-Item -Recurse -Force "$OutDir\classes" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$OutDir\classes" | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java "src\main\java" | ForEach-Object { $_.FullName }
& $Javac --release 25 -encoding UTF-8 -cp $cp -d "$OutDir\classes" $sources
Copy-Item "src\main\resources\*.yml" "$OutDir\classes\"

$version = (Select-String -Path "src\main\resources\plugin.yml" -Pattern '^version:\s*(\S+)').Matches[0].Groups[1].Value
Push-Location "$OutDir\classes"
& $JarExe cf "..\..\build\KozyrChunkyQueue-$version.jar" .
Pop-Location

Write-Output "Built build\KozyrChunkyQueue-$version.jar"
