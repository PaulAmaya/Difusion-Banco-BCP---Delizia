$ErrorActionPreference = 'Stop'

$forbiddenNames = '(?i)(^|/)(\.env($|\.)|.*\.(pfx|p12|pem|key|cer|crt|csr|jks|keystore|truststore|db|sqlite|bak|backup|dump)$)'
$allowedExamples = @('.env.example', '.env.production.example')
$tracked = @(
    git -c safe.directory=$((Get-Location).Path -replace '\\', '/') ls-files --cached --others --exclude-standard
) | Sort-Object -Unique

$forbidden = $tracked | Where-Object {
    $normalized = $_ -replace '\\', '/'
    $allowedExamples -notcontains $normalized -and $normalized -match $forbiddenNames
}

if ($forbidden) {
    Write-Error ("Archivos sensibles que Git incluiria:`n" + ($forbidden -join "`n"))
}

$patterns = @(
    '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----',
    '(?i)BCP_BASIC_PASSWORD\s*=\s*[^\s$<{]',
    '(?i)BCP_AUTH_CERTIFICATE_PASSWORD\s*=\s*[^\s$<{]',
    '(?i)BCP_SIGNING_CERTIFICATE_PASSWORD\s*=\s*[^\s$<{]',
    '(?i)BANK_DIFFUSION_PASSWORD\s*=\s*[^\s$<{]',
    '(?i)MYSQL_(ROOT_)?PASSWORD\s*=\s*[^\s$<{]'
)

$findings = foreach ($file in $tracked) {
    if ($allowedExamples -contains $file) { continue }
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { continue }
    if ($file -match '(?i)\.(png|jpg|jpeg|gif|ico|jar|zip)$') { continue }
    $content = Get-Content -LiteralPath $file -Raw -ErrorAction SilentlyContinue
    foreach ($pattern in $patterns) {
        if ($content -match $pattern) { $file; break }
    }
}

if ($findings) {
    Write-Error ("Revise posibles secretos en:`n" + (($findings | Sort-Object -Unique) -join "`n"))
}

Write-Output 'Revision completada: no se detectaron archivos secretos entre los candidatos de Git.'
