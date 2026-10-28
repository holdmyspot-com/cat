param([Parameter(Mandatory)] [string] $Output)
# Compiles the release build and writes app.exe to $Output.
New-Item -ItemType Directory -Force -Path $Output | Out-Null
cargo build --release --target x86_64-pc-windows-msvc
Copy-Item target/x86_64-pc-windows-msvc/release/app.exe (Join-Path $Output 'app.exe')
