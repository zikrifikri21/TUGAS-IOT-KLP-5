@echo off
set DEVICE=0I73917I231029CA

echo Mengaktifkan level log DEBUG untuk tag custom...
adb -s %DEVICE% shell setprop log.tag.BerkacaKi_Main DEBUG
adb -s %DEVICE% shell setprop log.tag.BerkacaKi_Source DEBUG
adb -s %DEVICE% shell setprop log.tag.BerkacaKi_Boot DEBUG
adb -s %DEVICE% shell setprop log.tag.MirrorAutoAccept DEBUG

echo Membersihkan buffer log lama...
adb -s %DEVICE% logcat -c

echo Restart aplikasi...
adb -s %DEVICE% shell am force-stop com.berkacaki
adb -s %DEVICE% shell am start -n com.berkacaki/.MainActivity

echo.
echo Logcat live (Ctrl+C untuk berhenti):
echo ============================================
adb -s %DEVICE% logcat -v time "BerkacaKi_Main:D" "BerkacaKi_Source:D" "BerkacaKi_Boot:D" "MirrorAutoAccept:D" "AndroidRuntime:E" "*:S"