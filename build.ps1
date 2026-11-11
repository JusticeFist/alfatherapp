# Builds the app with the SDK and Gradle cache kept in .tools, then copies the APK to AllFather.apk
$env:GRADLE_USER_HOME = "$PSScriptRoot\.tools\gradle-home"
$env:ANDROID_HOME = "$PSScriptRoot\.tools\sdk"
& "$PSScriptRoot\gradlew.bat" -p "$PSScriptRoot" assembleRelease
if ($LASTEXITCODE -eq 0) { Copy-Item "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk" "$PSScriptRoot\AllFather.apk" -Force; "APK listo: $PSScriptRoot\AllFather.apk" }
