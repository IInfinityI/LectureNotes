$output = "project_tree.txt"
$exclude = @('build', '.gradle', '.idea', '.git', 'local.properties', '*.iml')

function Get-Tree {
    param([string]$Path, [string]$Prefix = "")
    $items = Get-ChildItem -Path $Path | Where-Object {
        $name = $_.Name
        -not ($exclude | Where-Object { $name -like $_ })
    } | Sort-Object { $_.PSIsContainer -eq $false }, Name

    for ($i = 0; $i -lt $items.Count; $i++) {
        $item = $items[$i]
        $isLast = ($i -eq $items.Count - 1)
        $connector = if ($isLast) { [char]0x2514 + [string][char]0x2500 + [string][char]0x2500 + " " } else { [char]0x251C + [string][char]0x2500 + [string][char]0x2500 + " " }
        "$Prefix$connector$($item.Name)"
        if ($item.PSIsContainer) {
            $newPrefix = if ($isLast) { "$Prefix    " } else { "$Prefix" + [char]0x2502 + "   " }
            Get-Tree -Path $item.FullName -Prefix $newPrefix
        }
    }
}

"PROJECT TREE - $(Get-Date -Format 'yyyy-MM-dd HH:mm')" | Out-File $output -Encoding UTF8
("=" * 50) | Out-File $output -Append -Encoding UTF8
Get-Tree -Path "." | Out-File $output -Append -Encoding UTF8
Write-Host "Done: $output"
