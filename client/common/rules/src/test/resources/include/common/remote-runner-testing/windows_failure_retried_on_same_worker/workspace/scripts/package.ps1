# Packages the compiled executable into a signed zip archive.
$exe = 'dist/win-x64/app.exe'
if (-not (Test-Path $exe)) {
	throw "Cannot find path '$exe' because it does not exist."
}
signtool sign /fd SHA256 /a $exe
Compress-Archive -Path $exe, README.md -DestinationPath release/app-win-x64.zip -Force
