$out = "project_dump.json"
$exclude = 'build|\.gradle|\.idea|\.git|\.kotlin|captures'
$extExclude = '\.(png|jpg|jpeg|webp|gif|ttf|otf|apk|aab|iml|class|jar|so|dex)$'

Write-Host "[1/3] Сканирую проект..." -ForegroundColor Cyan
$files = Get-ChildItem -Recurse -File | Where-Object { $_.FullName -notmatch $exclude -and $_.Name -notmatch $extExclude }

Write-Host "[2/3] Собираю код в JSON ($($files.Count) файлов)..." -ForegroundColor Yellow
$fileObjects = @()

$counter = 0
$files | ForEach-Object {
    $counter++
    if ($counter % 10 -eq 0) { Write-Host "  бработано: $counter / $($files.Count)" -ForegroundColor Gray }
    
    $relPath = $_.FullName.Replace((Get-Location), ".")
    $content = Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue
    
    if ($content) {
        $fileObjects += [PSCustomObject]@{
            path = $relPath
            content = $content
        }
    }
}

$json = @{ files = $fileObjects } | ConvertTo-Json -Depth 10 -Compress

Write-Host "[3/3] аписываю JSON..." -ForegroundColor Cyan
[System.IO.File]::WriteAllText((Resolve-Path $out).Path, $json, [System.Text.Encoding]::UTF8)

$size = [math]::Round((Get-Item $out).Length / 1MB, 2)
Write-Host "[OK] JSON создан: $out ($size MB)" -ForegroundColor Green
