$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
Write-Host 'Logs del backend BCP. Ctrl+C detiene la visualizacion, no los contenedores.'
docker compose logs --follow --tail 100 backend
