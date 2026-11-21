# run.ps1 -- Clean Build, Install & Mirror tool for KannadaDoc
#

$ErrorActionPreference = "Stop"

$RepoRoot     = $PSScriptRoot
$AndroidDir   = Join-Path $RepoRoot "android"
$ApkPath      = Join-Path $AndroidDir "app\build\outputs\apk\debug\app-debug.apk"
$AppPackage   = "com.kannadadoc"
$MainActivity = "com.kannadadoc.MainActivity"

function Write-Step($text) {
    Write-Host ""
    Write-Host "==> $text" -ForegroundColor Cyan
}

function Get-AdbPath {
    # Check PATH first
    $onPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    # Check local.properties if present
    $localProps = Join-Path $AndroidDir "local.properties"
    if (Test-Path $localProps) {
        $sdkLine = Get-Content $localProps | Where-Object { $_ -match "^sdk\.dir\s*=\s*(.+)$" }
        if ($sdkLine -and ($matches[1])) {
            $sdkDir = $matches[1].Trim().Replace('\\', '\').Replace('/', '\')
            $candidate = Join-Path $sdkDir "platform-tools\adb.exe"
            if (Test-Path $candidate) { return $candidate }
        }
    }

    # Standard Android SDK locations
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        "C:\Android\sdk\platform-tools\adb.exe"
    )

    foreach ($path in $candidates) {
        if ($path -and (Test-Path $path)) {
            return $path
        }
    }

    # Fallback search in LocalAppData
    $found = Get-ChildItem "$env:LOCALAPPDATA\Android" -Recurse -Filter "adb.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($found) { return $found }

    return "adb.exe"
}

$Adb = Get-AdbPath

function Get-ScrcpyPath {
    # Prefer scrcpy-noconsole.exe so no extra terminal console window pops up
    $onPathNoConsole = Get-Command scrcpy-noconsole.exe -ErrorAction SilentlyContinue
    if ($onPathNoConsole) { return $onPathNoConsole.Source }

    $onPath = Get-Command scrcpy.exe -ErrorAction SilentlyContinue
    if ($onPath) {
        $noConsoleNextToIt = Join-Path (Split-Path $onPath.Source) "scrcpy-noconsole.exe"
        if (Test-Path $noConsoleNextToIt) { return $noConsoleNextToIt }
        return $onPath.Source
    }

    $wingetDir = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages"
    if (Test-Path $wingetDir) {
        $foundNoConsole = Get-ChildItem $wingetDir -Recurse -Filter "scrcpy-noconsole.exe" -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
        if ($foundNoConsole) { return $foundNoConsole }

        $found = Get-ChildItem $wingetDir -Recurse -Filter "scrcpy.exe" -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
        if ($found) { return $found }
    }
    return $null
}

function Get-ConnectedDevices {
    $rawLines = & $Adb devices 2>$null
    $list = @()
    foreach ($line in $rawLines) {
        if ($line -match "^(\S+)\s+device$") {
            $id = $matches[1]
            $model = & $Adb -s $id shell getprop ro.product.model 2>$null
            $brand = & $Adb -s $id shell getprop ro.product.brand 2>$null
            $cleanName = if ($model -and $brand) { "$($brand.Trim()) $($model.Trim()) ($id)" } else { $id }
            $isEmulator = $id.StartsWith("emulator-")
            $list += [PSCustomObject]@{ Id = $id; Name = $cleanName; IsEmulator = $isEmulator }
        }
    }
    return $list
}

function Select-TargetDevice([string]$filter = "ANY") {
    $devices = Get-ConnectedDevices

    if ($filter -eq "PHONE") {
        $phones = $devices | Where-Object { -not $_.IsEmulator }
        if ($phones.Count -eq 1) {
            Write-Host "Target Physical Phone: $($phones[0].Name)" -ForegroundColor Green
            return $phones[0].Id
        } elseif ($phones.Count -gt 1) {
            Write-Host "Multiple physical phones detected:" -ForegroundColor Yellow
            for ($i = 0; $i -lt $phones.Count; $i++) {
                Write-Host "   [$($i + 1)] $($phones[$i].Name)" -ForegroundColor Green
            }
            Write-Host "   [#] Go Back to Main Menu" -ForegroundColor DarkGray
            while ($true) {
                $choice = Read-Host "Select phone [1-$($phones.Count)] (or # to go back)"
                if ($choice -eq "#") { return "#BACK#" }
                if ($choice -as [int] -and [int]$choice -ge 1 -and [int]$choice -le $phones.Count) {
                    return $phones[[int]$choice - 1].Id
                }
                Write-Host "Invalid selection '$choice'. Enter a number between 1 and $($phones.Count), or '#' to go back." -ForegroundColor Yellow
            }
        } else {
            Write-Host "[WARNING] No physical phone connected via USB or USB Debugging is disabled." -ForegroundColor Red
            return $null
        }
    }

    if ($filter -eq "EMULATOR") {
        $emulators = $devices | Where-Object { $_.IsEmulator }
        if ($emulators.Count -ge 1) {
            Write-Host "Target Emulator: $($emulators[0].Name)" -ForegroundColor Green
            return $emulators[0].Id
        } else {
            Write-Host "[WARNING] No running emulator detected." -ForegroundColor Yellow
            return $null
        }
    }

    # Standard dynamic selection (filter = "ANY")
    Write-Step "Select Target Device"

    if ($devices.Count -eq 0) {
        Write-Host "No active ADB devices detected." -ForegroundColor Yellow
        Write-Host "   [r] Retry Device Scan" -ForegroundColor Cyan
        Write-Host "   [#] Go Back to Main Menu" -ForegroundColor DarkGray
        while ($true) {
            $resp = Read-Host "Select option [r or #]"
            if ($resp -eq "#") {
                return "#BACK#"
            } elseif ($resp -eq "r" -or $resp -eq "R") {
                return Select-TargetDevice -filter "ANY"
            }
            Write-Host "Invalid selection '$resp'. Enter 'r' to retry or '#' to go back." -ForegroundColor Yellow
        }
    }

    if ($devices.Count -eq 1) {
        Write-Host "Target Device: $($devices[0].Name)" -ForegroundColor Green
        return $devices[0].Id
    }

    Write-Host "Detected connected devices:" -ForegroundColor Yellow
    for ($i = 0; $i -lt $devices.Count; $i++) {
        $tag = if ($devices[$i].IsEmulator) { "[EMULATOR]" } else { "[PHONE]" }
        Write-Host "   [$($i + 1)] $tag $($devices[$i].Name)" -ForegroundColor Green
    }
    Write-Host "   [#] [GO BACK TO MAIN MENU]" -ForegroundColor DarkGray

    while ($true) {
        $choice = Read-Host "Select device number [1-$($devices.Count)] (or # to go back)"
        if ($choice -eq "#") {
            return "#BACK#"
        }
        if ($choice -as [int] -and [int]$choice -ge 1 -and [int]$choice -le $devices.Count) {
            $selected = $devices[[int]$choice - 1]
            Write-Host "Target Device Selected: $($selected.Name)" -ForegroundColor Green
            return $selected.Id
        } else {
            Write-Host "Invalid selection '$choice'. Enter a number between 1 and $($devices.Count), or # to go back." -ForegroundColor Yellow
        }
    }
}

function Run-Build {
    Write-Step "Building the app (gradlew assembleDebug)..."
    Push-Location $AndroidDir
    try {
        & .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
    Write-Host "Build succeeded: $ApkPath" -ForegroundColor Green
}

function Run-Uninstall($targetId) {
    Write-Step "Uninstalling $AppPackage from $targetId..."
    & $Adb -s $targetId uninstall $AppPackage 2>$null
    Write-Host "Uninstall complete." -ForegroundColor Green
}

function Run-Install($targetId) {
    Write-Step "Installing APK onto $targetId..."
    if (-not (Test-Path $ApkPath)) {
        Write-Host "APK not found at $ApkPath. Initiating build first..." -ForegroundColor Yellow
        Run-Build
    }
    & $Adb -s $targetId install -r -d $ApkPath
    if ($LASTEXITCODE -ne 0) { throw "Installation failed." }
    Write-Host "Installed successfully on $targetId!" -ForegroundColor Green
}

function Watch-Crashes($targetId) {
    $logFile = Join-Path $RepoRoot "crash-watch.log"
    Write-Step "Watching for crashes in $AppPackage on $targetId (Ctrl+C to stop)"
    Write-Host "Full log will be saved to: $logFile" -ForegroundColor Gray

    & $Adb -s $targetId logcat -c

    Write-Step "Launching $AppPackage ($MainActivity)..."
    & $Adb -s $targetId shell am start -n "$AppPackage/$MainActivity" | Out-Null

    $buffers = @{}
    $ownerPkg = @{}
    $flushed = @{}

    & $Adb -s $targetId logcat -v threadtime "AndroidRuntime:E" "*:S" | ForEach-Object {
        $line = $_
        Add-Content -Path $logFile -Value $line

        if ($line -match '^\S+\s+\S+\s+(\d+)\s+\d+\s+E\s+AndroidRuntime') {
            $crashPid = $matches[1]
            if (-not $buffers.ContainsKey($crashPid)) {
                $buffers[$crashPid] = New-Object System.Collections.Generic.List[string]
            }
            $buffers[$crashPid].Add($line)

            if ($line -match "Process:\s+(\S+?),") {
                $ownerPkg[$crashPid] = $matches[1]
            }

            if ($ownerPkg.ContainsKey($crashPid) -and $ownerPkg[$crashPid] -eq $AppPackage) {
                if (-not $flushed.ContainsKey($crashPid)) {
                    Write-Host ""
                    Write-Host "=== CRASH DETECTED in $AppPackage (PID $crashPid) ===" -ForegroundColor Red
                    $buffers[$crashPid] | ForEach-Object { Write-Host $_ -ForegroundColor Red }
                    $flushed[$crashPid] = $true
                } else {
                    Write-Host $line -ForegroundColor Red
                }
            }
        }
    }
}

function Get-RunningScrcpyDeviceIds {
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.Name -match "scrcpy" }
    $runningIds = @()
    foreach ($proc in $procs) {
        if ($proc.CommandLine -match '-s\s+([^\s]+)') {
            $devId = $matches[1]
            $p = Get-Process -Id $proc.ProcessId -ErrorAction SilentlyContinue
            if ($p -and $p.MainWindowHandle -ne 0) {
                $runningIds += $devId
            } else {
                Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
            }
        }
    }
    return $runningIds
}

function Start-ScrcpyProcess($scrcpyPath, $targetId) {
    $startParams = @{
        FilePath = $scrcpyPath
        ArgumentList = @("-s", "$targetId")
        WorkingDirectory = (Split-Path $scrcpyPath)
    }
    if ($scrcpyPath -notmatch "scrcpy-noconsole\.exe$") {
        $startParams["WindowStyle"] = "Hidden"
    }
    Start-Process @startParams
}

function Run-Mirror($targetId = $null) {
    $scrcpyPath = Get-ScrcpyPath
    if (-not $scrcpyPath) {
        Write-Host "[WARNING] scrcpy is not installed or not in PATH." -ForegroundColor Red
        Write-Host "Install it using: winget install --id Genymobile.scrcpy" -ForegroundColor Yellow
        return
    }

    $env:ADB = $Adb
    $runningIds = Get-RunningScrcpyDeviceIds

    if ($targetId) {
        if ($runningIds -contains $targetId) {
            Write-Host "Screen mirror (scrcpy) is already active for $targetId." -ForegroundColor Yellow
            return
        }
        Write-Step "Starting screen mirror (scrcpy) for $targetId..."
        Start-ScrcpyProcess $scrcpyPath $targetId
        Write-Host "Mirroring window launched for $targetId." -ForegroundColor Green
    } else {
        $devices = Get-ConnectedDevices
        if ($devices.Count -eq 0) {
            Write-Host "No connected devices found to mirror." -ForegroundColor Red
            return
        }
        Write-Step "Starting screen mirror (scrcpy) for all connected devices ($($devices.Count) found)..."
        foreach ($dev in $devices) {
            if ($runningIds -contains $dev.Id) {
                Write-Host "Screen mirror already active for $($dev.Name)." -ForegroundColor Yellow
            } else {
                Start-ScrcpyProcess $scrcpyPath $dev.Id
                Write-Host "Mirroring window launched for $($dev.Name)." -ForegroundColor Green
            }
        }
    }
}

# --- Main Interactive Loop ---
while ($true) {
    Write-Host ""
    Write-Host "==========================================================" -ForegroundColor Cyan
    Write-Host "       KannadaDoc -- Build, Install & Mirror Tool        " -ForegroundColor Cyan
    Write-Host "==========================================================" -ForegroundColor Cyan
    Write-Host "1) Reinstall & Mirror       -- Build, install onto device, mirror screen, and watch crashes"
    Write-Host "2) Fresh Uninstall & Mirror -- Build, uninstall previous app, reinstall, mirror screen, and watch crashes"
    Write-Host "3) Mirror Screen Only       -- Select connected device (phone/emulator) and start scrcpy mirror"
    Write-Host "4) Install APK Only         -- Select device and install app (without launching mirror)"
    Write-Host "5) Mirror All Devices       -- Start scrcpy screen mirroring simultaneously for all connected devices"
    Write-Host "6) Watch Crashes Only       -- Live-tail crashes with logcat on device"
    Write-Host "q) Exit"
    Write-Host "----------------------------------------------------------" -ForegroundColor DarkGray

    $choice = $null
    while ($true) {
        $inputVal = Read-Host "Select option [1, 2, 3, 4, 5, 6, or q]"
        if ($inputVal -match '^[1-6]$') {
            $choice = $inputVal
            break
        } elseif ($inputVal -eq "q" -or $inputVal -eq "Q" -or $inputVal -eq "#") {
            Write-Host "Exiting." -ForegroundColor Gray
            exit 0
        }
        Write-Host "Invalid selection '$inputVal'. Please enter a number between 1 and 6, or 'q' to exit." -ForegroundColor Yellow
    }

    switch ($choice) {
        "1" {
            $targetId = Select-TargetDevice
            if (-not $targetId -or $targetId -eq "#BACK#") { continue }
            Run-Build
            Run-Install $targetId
            Run-Mirror $targetId
            Watch-Crashes $targetId
            break
        }
        "2" {
            $targetId = Select-TargetDevice
            if (-not $targetId -or $targetId -eq "#BACK#") { continue }
            Run-Build
            Run-Uninstall $targetId
            Run-Install $targetId
            Run-Mirror $targetId
            Watch-Crashes $targetId
            break
        }
        "3" {
            $targetId = Select-TargetDevice
            if (-not $targetId -or $targetId -eq "#BACK#") { continue }
            Run-Mirror $targetId
            break
        }
        "4" {
            $targetId = Select-TargetDevice
            if (-not $targetId -or $targetId -eq "#BACK#") { continue }
            Run-Install $targetId
            break
        }
        "5" {
            Run-Mirror
            break
        }
        "6" {
            $targetId = Select-TargetDevice
            if (-not $targetId -or $targetId -eq "#BACK#") { continue }
            Watch-Crashes $targetId
            break
        }
    }
}
