param(
    [string]$Version = "",
    [string]$CommitMessage = "",
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

if ([string]::IsNullOrWhiteSpace($Version)) {
    $gradleFile = Join-Path $repoRoot "app\build.gradle.kts"
    $gradleText = Get-Content -LiteralPath $gradleFile -Raw
    if ($gradleText -notmatch 'versionName\s*=\s*"([^"]+)"') {
        throw "Cannot find versionName in app/build.gradle.kts. Pass -Version explicitly."
    }
    $Version = $Matches[1]
}

$Version = $Version.Trim().TrimStart("v").Replace("mini-v", "").Replace("mp-v", "")
$tagName = "mini-v$Version"
$branch = (& git branch --show-current).Trim()

if (-not $branch) {
    throw "Cannot determine current git branch."
}

Write-Host "SmartReimburse mini program release helper"
Write-Host "Version: $Version"
Write-Host "Tag:     $tagName"
Write-Host "Branch:  $branch"

$dirty = (& git status --porcelain)
if ($dirty -and [string]::IsNullOrWhiteSpace($CommitMessage) -and -not $DryRun) {
    throw "Working tree has changes. Commit them first, or rerun with -CommitMessage `"your message`"."
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

Invoke-Checked "git" @("tag", "-a", $tagName, "-m", "SmartReimburse Mini Program $tagName")

if (-not $NoPush) {
    Invoke-Checked "git" @("push", "-u", "origin", $branch)
    Invoke-Checked "git" @("push", "origin", $tagName)
    if ($DryRun) {
        Write-Host "[dry-run] Mini program upload workflow would be triggered."
    } else {
        Write-Host "Mini program upload workflow triggered."
    }
} else {
    Write-Host "Created local tag $tagName. Push it with: git push origin $tagName"
}
