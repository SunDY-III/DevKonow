@echo off
call "D:\vs_code\VSBuildTools\VC\Auxiliary\Build\vcvars64.bat"
set RUSTUP_HOME=C:\Users\Sun\.rustup
set CARGO_HOME=D:\cargo
set PATH=D:\cargo\bin;%PATH%
cd /d D:\DevKnow\DevKnow
echo ============ BUILD START ============
cargo build --manifest-path src-tauri\Cargo.toml
set RESULT=%ERRORLEVEL%
echo ============ BUILD EXIT CODE: %RESULT% ============
exit /b %RESULT%
