# Compatibilidad temporal del sandbox BCP

La conexion exitosa de Postman del 18/09/2026 negocio TLSv1.2 con
TLS_RSA_WITH_AES_256_GCM_SHA384 y API_DESA.pfx. Este cifrado RSA no ofrece
forward secrecy. La excepcion es temporal y no es una configuracion de produccion.

## Activacion

En .env:

```dotenv
BCP_TLS_PROTOCOLS=TLSv1.2
BCP_SANDBOX_LEGACY_RSA_ENABLED=true
```

El Dockerfile instala curl/OpenSSL y los certificados CA del sistema operativo.
Reconstruir desde la carpeta del proyecto:

```powershell
docker compose up -d --build backend frontend
```

Solo se permite la URL exacta del sandbox ProcessMultiple. La compatibilidad
requiere el PFX de autenticacion. Credenciales, JSON encriptado y contrasena PFX
se pasan por stdin: no se escriben en archivos ni argumentos del proceso.
El transporte valida el certificado y hostname del servidor, usa HTTP/1.1,
no sigue redirecciones y no reintenta pagos. No cambia java.security ni la
configuracion de SAP. curl/OpenSSL es necesario: curl de Windows con Schannel
no es el runtime de esta implementacion. El runtime admitido es Docker.

## Diagnostico sin pagos

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Probe-BcpTls.ps1
```

Hace HEAD sin cuerpo de lote ni Basic Auth. Un HTTP 403/405 todavia demuestra
una respuesta HTTP de esa conexion; no confirma que el banco acepte un lote.
Muestra TLS, cifrado y eventos de handshake del certificado sin exponer datos.
Observar un mensaje Certificate saliente no prueba por si solo la identidad
aceptada por el banco: puede contener una cadena vacia.

## Envio y resultados

Desde el frontend, previsualizar y enviar un lote de prueba revisado. Los logs
BCP_HTTP_RESPONSE muestran el HTTP real y TLS/cifrado negociados. El flujo
existente conserva y desencripta body o message y muestra el resultado.
HTTP 200 con isOk=false sigue siendo un rechazo de negocio. Un cierre o una
respuesta incompleta deja UNKNOWN: conciliar antes de un nuevo envio.

curlExit: 6 DNS, 7 TCP, 28 timeout, 35 TLS, 58 PFX, 60 certificado del servidor,
52 cierre sin HTTP, 56 lectura interrumpida. No se registra stderr completo:
curl verbose incluye Authorization. Solo se extraen metadatos acotados.

## Desactivacion

Restaurar BCP_SANDBOX_LEGACY_RSA_ENABLED=false y BCP_TLS_PROTOCOLS=TLSv1.3,
y recrear backend. Antes de produccion, confirmar con BCP una configuracion
moderna soportada y sustituir las credenciales expuestas en la consola compartida.

Referencias: https://curl.se/docs/manpage.html y
https://docs.spring.io/spring-boot/specification/executable-jar/property-launcher.html
