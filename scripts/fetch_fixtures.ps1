$ProgressPreference = 'SilentlyContinue'
$out = Join-Path $PSScriptRoot '..\fixtures_raw'
New-Item -ItemType Directory -Force $out | Out-Null

$base = 'https://timetable.tusur.ru'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) TusurTimetableFetch/1.0'

$targets = [ordered]@{
    'faculties.html'   = '/faculties'
    'groups_fb.html'   = '/faculties/fb'
    'group_714-2.html' = '/faculties/fb/groups/714-2'
    'group_716-1.html' = '/faculties/fb/groups/716-1'
    '714-2.ics'        = '/faculties/fb/groups/714-2.ics'
    '716-1.ics'        = '/faculties/fb/groups/716-1.ics'
}

foreach ($key in $targets.Keys) {
    $url = $base + $targets[$key]
    try {
        $r = Invoke-WebRequest $url -Headers @{ 'User-Agent' = $ua } -UseBasicParsing -TimeoutSec 60
        $bytes = $r.Content
        if ($bytes -isnot [byte[]]) {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$bytes)
        }
        [System.IO.File]::WriteAllBytes((Join-Path $out $key), $bytes)
        Write-Output ("{0} OK {1} bytes" -f $key, $bytes.Length)
    }
    catch {
        Write-Output ("{0} FAIL {1}" -f $key, $_.Exception.Message)
    }
    Start-Sleep -Milliseconds 400
}
