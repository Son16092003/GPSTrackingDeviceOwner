# =========================
# Config
# =========================
$avdName = "Pixel_5_DeviceOwner"
$apkPath = "C:\TTTN\Sql Server\BackgroundLocationTracking\app\build\outputs\apk\debug\app-debug.apk"
$packageName = "com.plcoding.backgroundlocationtracking"
$deviceAdminReceiver = ".admin.MyDeviceAdminReceiver"

# =========================
# Helper Functions
# =========================

function Get-VNTime {
    # Giờ Việt Nam = UTC + 7
    return (Get-Date).ToUniversalTime().AddHours(7).ToString("dd/MM/yyyy HH:mm:ss")
}

function Log-Info($msg) {
    $time = Get-VNTime
    Write-Host "[$time] [INFO] $msg" -ForegroundColor Cyan
}

function Log-Success($msg) {
    $time = Get-VNTime
    Write-Host "[$time] [SUCCESS] $msg" -ForegroundColor Green
}

function Log-Warning($msg) {
    $time = Get-VNTime
    Write-Host "[$time] [WARNING] $msg" -ForegroundColor Yellow
}

function Log-Error($msg) {
    $time = Get-VNTime
    Write-Host "[$time] [ERROR] $msg" -ForegroundColor Red
}

function Log-Title($msg) {
    $time = Get-VNTime
    Write-Host "[$time] ========== $msg ==========" -ForegroundColor White
}

# =========================
# Main Functions
# =========================

function Wait-ForBoot {
    Log-Info "Waiting for emulator to boot..."
    do {
        Start-Sleep -Seconds 3
        $deviceStatus = adb devices | Select-String "emulator"
    } while (-not $deviceStatus)

    Log-Info "Emulator online, waiting for system boot complete..."
    do {
        Start-Sleep -Seconds 3
        $bootCompleted = adb shell getprop sys.boot_completed 2>$null
    } while ($bootCompleted -ne "1")

    Log-Success "Emulator boot complete."
}

function Start-Emulator {
    Log-Title "Starting Emulator"
    Log-Info "Killing all running emulators..."
    adb devices | ForEach-Object {
        if ($_ -match "emulator-(\d+)\s+device") {
            $emulatorId = $matches[1]
            Log-Info "Stopping emulator-$emulatorId ..."
            adb -s "emulator-$emulatorId" emu kill
        }
    }
    Start-Sleep -Seconds 5

    Log-Info "Starting AVD $avdName with wipe-data..."
    Start-Process "emulator" "-avd $avdName -wipe-data -netdelay none -netspeed full"
    Wait-ForBoot
}

function Setup-DeviceOwner {
    Log-Title "Setting Device Owner"
    Log-Info "Installing APK..."
    adb install -r "$apkPath" | Out-Null

    Log-Info "Activating DeviceAdminReceiver..."
    adb shell dpm set-active-admin "$packageName/$deviceAdminReceiver" | Out-Null

    $retryCount = 0
    do {
        $retryCount++
        Log-Info "Attempt #${retryCount}: Setting Device Owner..."
        $result = adb shell dpm set-device-owner "$packageName/$deviceAdminReceiver" 2>&1

        if ($result -match "Success") {
            Log-Success "Device Owner set successfully."
            return $true
        } elseif ($result -match "java.lang.IllegalStateException") {
            Log-Warning "Failed to set device owner (probably due to accounts). Retrying after wipe..."
            Start-Emulator
            Log-Info "Reinstalling APK after wipe..."
            adb install -r "$apkPath" | Out-Null
            adb shell dpm set-active-admin "$packageName/$deviceAdminReceiver" | Out-Null
        } else {
            Log-Error "Unknown error occurred. Retrying in 5 seconds..."
            Start-Sleep -Seconds 5
        }
    } while ($true)
}

# =========================
# Main Execution
# =========================
Log-Title "Device Owner Automation Script Started"
Start-Emulator
Setup-DeviceOwner

Log-Title "Checking Device Owner Status"
adb shell dpm list-owners
Log-Success "Script finished."

