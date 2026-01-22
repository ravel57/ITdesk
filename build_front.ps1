$path = Get-Location
Set-Location ../../WebstormProjects/itdesk-front

yarn install
yarn build

if (Test-Path "./dist/spa/") {
    Copy-Item -Path "./dist/spa/*" -Destination "$path/src/main/resources/static" -Recurse -Force
    Set-Location $path
} else {
    Write-Error "Coping error"
    Set-Location $path
    exit 1
}
