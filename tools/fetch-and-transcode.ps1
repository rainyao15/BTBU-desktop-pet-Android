# 从上游 dsh-pet 还原素材并转码成动画 WebP（Windows 用）
#
# 在仓库根目录运行：
#   pwsh -File tools/fetch-and-transcode.ps1
#   pwsh -File tools/fetch-and-transcode.ps1 -Ffmpeg "C:\ffmpeg\ffmpeg.exe" -Width 640 -Quality 60 -Fps 24
#
# 需要：Node.js（含 npm）、ffmpeg（支持 libvpx-vp9 解码与 libwebp_anim 编码）。
param(
    [string]$Ffmpeg = "ffmpeg",
    [int]$Width = 480,
    [int]$Quality = 50,
    [int]$Fps = 15
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $work = Join-Path ([System.IO.Path]::GetTempPath()) "dsh-pet-src"
    if (Test-Path $work) { Remove-Item $work -Recurse -Force }
    New-Item -ItemType Directory -Path $work | Out-Null

    Write-Host "==> npm pack dsh-pet" -ForegroundColor Cyan
    & npm pack dsh-pet --pack-destination $work | Out-Null
    $tgz = Get-ChildItem $work -Filter "dsh-pet-*.tgz" | Select-Object -First 1
    if (-not $tgz) { throw "npm pack 失败：拿不到 dsh-pet 包" }
    & tar -xzf $tgz.FullName -C $work

    Write-Host "==> 转码 + 生成配置" -ForegroundColor Cyan
    & node (Join-Path $PSScriptRoot "build-assets.mjs") `
        --pkg (Join-Path $work "package") `
        --out "app/src/main/assets/webp" `
        --width $Width --quality $Quality --fps $Fps --ffmpeg $Ffmpeg
    if ($LASTEXITCODE -ne 0) { throw "build-assets.mjs 失败（退出码 $LASTEXITCODE）" }
}
finally {
    Pop-Location
}
