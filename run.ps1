$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$MavenVersion = "3.9.11"
$MavenUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MavenVersion/apache-maven-$MavenVersion-bin.zip"
$MavenRoot = Join-Path $PSScriptRoot ".mvn"
$MavenDir = Join-Path $MavenRoot "apache-maven-$MavenVersion"
$MavenExe = Join-Path $MavenDir "bin\mvn.cmd"
$MavenZip = Join-Path $MavenRoot "apache-maven-$MavenVersion-bin.zip"

function Stop-WithMessage {
    param([string] $Message)
    Write-Host ""
    Write-Host $Message -ForegroundColor Red
    exit 1
}

function Get-JavaInfo {
    param([string] $JavaExe)
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $versionText = (& $JavaExe -version 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($versionText -notmatch 'version "([^"]+)"') {
        Stop-WithMessage "Could not detect Java version from: $versionText"
    }

    $version = $Matches[1]
    if ($version.StartsWith("1.")) {
        $major = [int]($version.Split(".")[1])
    } else {
        $major = [int]($version.Split(".")[0])
    }

    return [pscustomobject]@{
        Major = $major
        Version = $version
        JavaExe = $JavaExe
    }
}

function Get-JavaHomeFromExe {
    param([string] $JavaExe)
    $javaFile = Get-Item -LiteralPath $JavaExe -ErrorAction SilentlyContinue
    if (-not $javaFile) {
        return $null
    }

    return $javaFile.Directory.Parent.FullName
}

function Find-JdkHomes {
    $homes = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME) {
        $homes.Add($env:JAVA_HOME)
    }

    $pathJava = Get-Command java -ErrorAction SilentlyContinue
    if ($pathJava) {
        $pathJavaHome = Get-JavaHomeFromExe $pathJava.Source
        if ($pathJavaHome) {
            $homes.Add($pathJavaHome)
        }
    }

    $roots = @(
        (Join-Path $env:ProgramFiles "Eclipse Adoptium"),
        (Join-Path $env:ProgramFiles "Java"),
        (Join-Path $env:ProgramFiles "Microsoft")
    )

    foreach ($root in $roots) {
        if (-not (Test-Path $root)) {
            continue
        }

        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^(jdk|openjdk|temurin|microsoft-jdk)' -or $_.Name -match '17|18|19|20|21|22|23' } |
            ForEach-Object { $homes.Add($_.FullName) }
    }

    return $homes | Select-Object -Unique
}

function Initialize-Java {
    $oldestFound = $null

    foreach ($jdkHome in Find-JdkHomes) {
        $javaExe = Join-Path $jdkHome "bin\java.exe"
        if (-not (Test-Path $javaExe)) {
            continue
        }

        $info = Get-JavaInfo $javaExe
        if (-not $oldestFound -or $info.Major -gt $oldestFound.Major) {
            $oldestFound = $info
        }

        if ($info.Major -ge 17) {
            $env:JAVA_HOME = $jdkHome
            $env:Path = "$(Join-Path $jdkHome 'bin');$env:Path"
            Write-Host "Using Java $($info.Version): $jdkHome"
            return
        }
    }

    if ($oldestFound) {
        Stop-WithMessage "Java $($oldestFound.Version) is too old. Install JDK 17 or newer."
    }

    Stop-WithMessage "Java was not found. Install JDK 17 or newer and try again."
}

Initialize-Java

if (Test-Path $MavenExe) {
    $mvn = $MavenExe
} else {
    $systemMaven = Get-Command mvn -ErrorAction SilentlyContinue
    if ($systemMaven) {
        $mvn = $systemMaven.Source
    } else {
        Write-Host "Maven was not found. Downloading Apache Maven $MavenVersion locally..."
        New-Item -ItemType Directory -Force -Path $MavenRoot | Out-Null

        if (-not (Test-Path $MavenZip)) {
            Invoke-WebRequest -Uri $MavenUrl -OutFile $MavenZip
        }

        Expand-Archive -Path $MavenZip -DestinationPath $MavenRoot -Force
        $mvn = $MavenExe
    }
}

& $mvn -DskipTests javafx:run @args
exit $LASTEXITCODE
