# Certificado de autenticacion BCP

Si el banco exige autenticacion TLS mutua, coloque aqui el certificado de
autenticacion PKCS12 (.pfx), distinto de los certificados de cifrado y firma.
No publique certificados ni claves privadas. Esta carpeta se monta en el
backend como /run/bcp-auth, solo lectura.

En .env configure BCP_AUTH_CERTIFICATE_PATH=/run/bcp-auth/nombre.pfx y
BCP_AUTH_CERTIFICATE_PASSWORD. Deje ambos vacios si no se necesita mTLS.

Para www99.bancred.com.bo, la configuracion de Postman usa API_DESA.pfx.
Se configura BCP_AUTH_CERTIFICATE_PATH=/run/bcp-auth/API_DESA.pfx y la misma
Passphrase de ese archivo en BCP_AUTH_CERTIFICATE_PASSWORD. No es la clave
de Basic Auth ni la del certificado ENC_DESA.pfx. La clave privada y cadena
del PFX se cargan mediante KeyManagerFactory para el handshake TLS del banco.

Para usar directamente la misma carpeta de Postman, configure
BCP_AUTH_CERT_HOST_DIRECTORY con la ruta de Windows de Certificados Desarrollo,
por ejemplo C:/Users/HP/Downloads/.../Certificados Desarrollo. Docker la monta
en /run/bcp-auth en modo solo lectura. BCP_AUTH_CERTIFICATE_PATH debe seguir
siendo /run/bcp-auth/API_DESA.pfx, no una ruta C:/Users/.... Deje la variable
HOST_DIRECTORY vacia para montar la carpeta bank-auth-certs del proyecto.

El PFX de sandbox.openbanking.bcp.com.bo corresponde a otro host y no se usa
en ProcessMultiple de www99.bancred.com.bo.

El backend usa HTTP/1.1 y TLSv1.3 por defecto, sin fallback automatico a TLSv1.2,
y valida el certificado del servidor y su hostname.
Si el PFX o su clave no son validos, la operacion falla antes de reservar los
documentos o enviar el POST. No desactive la validacion TLS para corregirlo.
