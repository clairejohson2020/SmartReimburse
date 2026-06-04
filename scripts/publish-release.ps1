param(
    [string]$CommitMessage = "",
    [switch]$SkipLocalBuild,
    [switch]$NoPush,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [string]$FilePath,
        [string[]]$Arguments
    )

    $display = "$FilePath $($Arguments -join ' ')"
    if ($DryRun) {
        Write-Host "[dry-run] $display"
        return
    }

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $display"
    }
}

$repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

$gradleFile = Join-Path $repoRoot "app\build.gradle.kts"
$gradleText = Get-Content -LiteralPath $gradleFile -Raw

if ($gradleText -notmatch 'versionName\s*=\s*"([^"]+)"') {
    throw "Cannot find versionName in app/build.gradle.kts."
}
$versionName = $Matches[1]

if ($gradleText -notmatch 'versionCode\s*=\s*([0-9]+)') {
    throw "Cannot find versionCode in app/build.gradle.kts."
}
$versionCode = $Matches[1]
$tagName = "v$versionName"

Write-Host "SmartReimburse release helper"
Write-Host "Version name: $versionName"
Write-Host "Version code: $versionCode"
Write-Host "Release tag:  $tagName"

$branch = (& git branch --show-current).Trim()
if (-not $branch) {
    throw "Cannot determine the current git branch."
}

$dirty = (& git status --porcelain)
if ($dirty -and [string]::IsNullOrWhiteSpace($CommitMessage) -and -not $DryRun) {
    throw "Working tree has changes. Commit them first, or rerun with -CommitMessage `"your message`"."
}

if (-not $SkipLocalBuild) {
    $gradleArgs = @(
        "clean",
        ":app:assembleDebug",
        "-Dkotlin.compiler.execution.strategy=in-process"
    )

    $androidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path -LiteralPath $androidStudioJbr) {
        $gradleArgs += "-Dorg.gradle.java.home=$androidStudioJbr"
    }

    Invoke-Checked ".\gradlew.bat" $gradleArgs
}

if ($dirty -and -not [string]::IsNullOrWhiteSpace($CommitMessage)) {
    Invoke-Checked "git" @("add", "-A")
    Invoke-Checked "git" @("commit", "-m", $CommitMessage)
}

if ($DryRun) {
    Write-Host "[dry-run] Would verify that local and remote tag $tagName do not exist."
} else {
    $localTag = (& git tag --list $tagName).Trim()
    if ($localTag) {
        throw "Local tag $tagName already exists."
    }

    & git ls-remote --exit-code --tags origin "refs/tags/$tagName" *> $null
    $remoteTagExitCode = $LASTEXITCODE
    if ($remoteTagExitCode -eq 0) {
        throw "Remote tag $tagName already exists."
    }
    if ($remoteTagExitCode -ne 2) {
        throw "Cannot query remote tag $tagName."
    }
}

Invoke-Checked "git" @("tag", "-a", $tagName, "-m", "SmartReimburse $tagName")

if (-not $NoPush) {
    Invoke-Checked "git" @("push", "-u", "origin", $branch)
    Invoke-Checked "git" @("push", "origin", $tagName)
    if ($DryRun) {
        Write-Host "[dry-run] Release trigger would be pushed. GitHub Actions would build and publish $tagName."
    } else {
        Write-Host "Release trigger pushed. GitHub Actions will build and publish $tagName."
    }
} else {
    Write-Host "Created local tag $tagName. Push it with: git push origin $tagName"
}
