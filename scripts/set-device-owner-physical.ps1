# =========================
# Config
# =========================
$apkPath = "C:\TTTN\Sql Server\BackgroundLocationTracking\app\build\outputs\apk\debug\app-debug.apk"
$packageName = "com.plcoding.backgroundlocationtracking"
$deviceAdminReceiver = ".admin.MyDeviceAdminReceiver"

# =========================
# Helper Functions
# =========================

function Get-VNTime {
    # Vietnam time = UTC + 7
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
    Write-Host "[$time] ====== $msg ======" -ForegroundColor White
}

# =========================
# Main Functions
# =========================

function Wait-ForPhysicalDevice {
    Log-Title "Waiting for Physical Device Connection"
    Log-Info "Please connect the physical Android device via USB..."
    do {
        Start-Sleep -Seconds 3
        $device = adb devices | Select-String "device$"
    } while (-not $device)
    Log-Success "Physical device detected and ready."
}

function Setup-DeviceOwner {
    Log-Title "Setting Device Owner on Physical Device"

    Log-Info "Installing APK on device..."
    adb install -r "$apkPath" | Out-Null

    Log-Info "Activating DeviceAdminReceiver..."
    adb shell dpm set-active-admin "$packageName/$deviceAdminReceiver" | Out-Null

    Log-Info "Attempting to set device owner..."
    $result = adb shell dpm set-device-owner "$packageName/$deviceAdminReceiver" 2>&1

    if ($result -match "Success") {
        Log-Success "Device Owner has been set successfully."
    }
    elseif ($result -match "java.lang.IllegalStateException") {
        Log-Error "Device already provisioned or has an existing account."
        Log-Warning "Please perform a manual factory reset, then rerun this script."
        exit
    }
    else {
        Log-Error "Unknown error: $result"
        exit
    }
}

# =========================
# Main Execution
# =========================

Log-Title "Device Owner Setup Script (Physical Device)"
Wait-ForPhysicalDevice
Setup-DeviceOwner

Log-Title "Verifying Device Owner Status"
adb shell dpm list-owners
Log-Success "Device Owner setup completed successfully on the physical device."
