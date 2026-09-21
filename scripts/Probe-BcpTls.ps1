$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
Write-Host 'Diagnostico BCP: HEAD sin lote ni Basic Auth. No envia pagos.'
docker compose exec -T backend java '-Dloader.main=com.example.defusion_bcp.service.BankSandboxTlsProbe' -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
if ($LASTEXITCODE -ne 0) { throw 'Fallo el diagnostico TLS. Revise el codigo de error mostrado.' }
