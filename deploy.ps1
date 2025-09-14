param(
  [string]$ProjectId,
  [string]$Service = "email-dispatcher",
  [string]$Region = "asia-northeast3",
  [string]$Repo = "app-repo",
  [string]$Tag = (Get-Date -Format "yyyyMMdd-HHmmss"),
  [string]$FromImage = "eclipse-temurin:21-jre",
  [int]   $Port = 8080
)

$ErrorActionPreference = "Stop"

function Exec([string]$cmd, [string[]]$arguments) {
  Write-Host ">> $cmd $($arguments -join ' ')" -ForegroundColor Cyan
  $tmpOut = [System.IO.Path]::GetTempFileName()
  $tmpErr = [System.IO.Path]::GetTempFileName()
  try {
    $proc = Start-Process -FilePath $cmd -ArgumentList $arguments -NoNewWindow -Wait -PassThru `
      -RedirectStandardOutput $tmpOut -RedirectStandardError $tmpErr
    $outText = ""
    $errText = ""
    try { $outText = Get-Content -LiteralPath $tmpOut -Raw -ErrorAction SilentlyContinue } catch {}
    try { $errText = Get-Content -LiteralPath $tmpErr -Raw -ErrorAction SilentlyContinue } catch {}
    if ($outText) { Write-Output $outText }
    if ($errText) { Write-Host $errText }
    if ($proc.ExitCode -ne 0) {
      throw "Command failed ($($proc.ExitCode)): $cmd $($arguments -join ' ')"
    }
  }
  finally {
    Remove-Item -LiteralPath $tmpOut, $tmpErr -ErrorAction SilentlyContinue
  }
}

# gcloud.ps1 실행정책 이슈 우회: 항상 .cmd 호출
$gcloud = "gcloud.cmd"

# 0) 선행 점검(간단)
Exec $gcloud @("--version") | Out-Null
if (-not $ProjectId -or $ProjectId.Trim() -eq "") {
  $ProjectId = (Exec $gcloud @("config", "get-value", "project")).Trim()
}
if (-not $ProjectId) { Write-Error "ProjectId 필요"; exit 1 }

Write-Host "Project : $ProjectId"
Write-Host "Service : $Service"
Write-Host "Region  : $Region"
Write-Host "Repo    : $Repo"
Write-Host "Tag     : $Tag"

# 1) 사전 활성화/리포지토리 생성은 생략. 존재 가정하고 실패에도 계속 진행

# 3) Docker 크리덴셜 구성 (Jib가 이걸 사용)
Exec $gcloud @("auth", "configure-docker", "$Region-docker.pkg.dev") | Out-Null

# 4) Jib 빌드 & 푸시
$image = "$Region-docker.pkg.dev/${ProjectId}/${Repo}/${Service}:${Tag}"
Write-Host "IMAGE  : $image" -ForegroundColor Yellow

# Gradle Wrapper가 윈도우에서는 gradlew.bat
$gradlew = if (Test-Path ".\gradlew.bat") { ".\gradlew.bat" } else { ".\gradlew" }

# 각 -D 인자는 따옴표로 감싸서 PowerShell 파싱 이슈 방지
Exec $gradlew @("jib",
  "-Djib.to.image=$image",
  "-Djib.from.image=$FromImage",
  "-Djib.container.ports=$Port"
)

# 5) Cloud Run 배포 (무중단)
Exec $gcloud @("run", "deploy", $Service,
  "--image", $image,
  "--region", $Region,
  "--no-allow-unauthenticated",
  "--cpu", "0.5", "--memory", "512Mi",
  "--max-instances", "1", "--min-instances", "0",
  "--timeout", "900s"
)

# 6) URL 출력
$url = (Exec $gcloud @("run", "services", "describe", $Service, "--region", $Region, "--format=value(status.url)"))
Write-Host "`n✅ 완료: $image → $Service 배포. URL: $url" -ForegroundColor Green
