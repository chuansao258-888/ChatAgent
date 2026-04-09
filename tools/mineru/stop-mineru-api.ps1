$listeners = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
if (-not $listeners) {
    Write-Host "No MinerU listener found on port 8000." -ForegroundColor Yellow
    exit 0
}

$stopped = $false
foreach ($listener in $listeners) {
    $owner = $listener.OwningProcess
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $owner" -ErrorAction SilentlyContinue
    if ($process -and (
            $process.CommandLine -like '*mineru-api*' -or
            $process.CommandLine -like '*mineru.cli.fast_api*'
        )) {
        Stop-Process -Id $owner -Force
        Write-Host "Stopped MinerU API process $owner" -ForegroundColor Cyan
        $stopped = $true
    }
}

if (-not $stopped) {
    Write-Host "Port 8000 is in use, but not by a MinerU API process." -ForegroundColor Yellow
}
