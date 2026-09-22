# Portal Delizia 1.0: preparacion de produccion

La configuracion productiva es independiente de las pruebas. No se ha iniciado ni se han enviado pagos productivos.

## Archivos y credenciales

Copiar `.env.production.example` a `.env.production` en el servidor y completar los campos vacios. Restringir los permisos de este archivo al administrador y al proceso de despliegue. No publicarlo ni agregarlo a Git.

Para valores con caracteres especiales como `$` o `#`, usar comillas simples en el archivo de entorno para conservar el valor literal. No pegar contrasenas en el chat. El archivo productivo privado ya esta creado como plantilla y no se usa en el despliegue local.

`BCP_BASIC_USERNAME` y `BCP_BASIC_PASSWORD` son Basic Auth. `BANK_DIFFUSION_PASSWORD`, `BCP_COMPANY_ID` y los campos `BANK_DIFFUSION_DOCUMENT_*` son la cabecera del JSON, compartida con extractos. `BCP_GENERIC_USER_ACCESS` es solo una referencia administrativa y no se envia al banco.

Colocar los tres certificados productivos en una carpeta protegida FUERA del repositorio. Configurar `BCP_CERT_HOST_DIRECTORY` con su ruta en el servidor y los tres `*_CERTIFICATE_PATH` con sus nombres dentro de `/run/bcp-certs/`. Docker los monta solo para lectura. Las contrasenas PFX se configuran por separado, nunca en React ni en logs. Confirmar con BCP la funcion de cada archivo: autenticacion PFX, firma/encriptacion PFX y BUSINESS CER; el CER publico no requiere contrasena.

Para pagos completar tambien concepto, origen/destino de fondos, correo y autorizadores reales en `BANK_DIFFUSION_APPROVERS_JSON`. Verificar las cuentas de origen BCP LP y BCP SC antes de enviar. No se usan los autorizadores de prueba en produccion.

## Acceso por red

`PORTAL_BIND_IP` es la IP privada real del SERVIDOR. `PORTAL_PORT=8081` publica el frontend, no el backend. `FRONTEND_ORIGIN` debe ser la direccion HTTP completa que usaran los usuarios, incluyendo el puerto, por ejemplo `http://192.168.1.50:8081`.

Este despliegue publica HTTP interno y no requiere `fullchain.pem` ni `private.key`. Por ello las credenciales SAP y los datos mostrados por el portal no quedan cifrados entre el navegador y el servidor. Debe limitarse estrictamente a la red corporativa y no exponerse a Internet. Los certificados BCP siguen siendo obligatorios y protegen la comunicacion entre el backend y el banco, no el acceso web de los usuarios.

Autorizar el puerto TCP 8081 en el firewall SOLO para la red corporativa permitida. No publicar MySQL ni el backend, ni abrir el portal a Internet. Backend y base de datos permanecen en redes Docker; Nginx comunica `/api` internamente. La cookie de sesion se configura sin la marca `Secure` exclusivamente para este modo HTTP.

## Despliegue

Validar primero, sin imprimir el contenido expandido del entorno:

```powershell
docker compose --env-file .env.production -f compose.production.yaml config --quiet
docker compose --env-file .env.production -f compose.production.yaml up -d --build
docker compose --env-file .env.production -f compose.production.yaml ps
docker compose --env-file .env.production -f compose.production.yaml logs --tail 100 backend
```

Abrir `http://IP_O_NOMBRE_DEL_SERVIDOR:8081` desde el servidor y desde otro equipo autorizado. Probar login SAP, filtros, consulta de extractos y descarga/visualizacion de respuestas. `BCP_PAYMENTS_ENABLED=false` bloquea inicialmente las consultas/envios al banco; habilitarlo expresamente solo despues de validar credenciales y certificados.

TLS al banco inicia con TLS 1.3 y validacion de servidor activa; confirmar el protocolo negociado en productivo. La compatibilidad RSA antigua queda exclusivamente en sandbox. La reversion local de documentos esta deshabilitada en produccion. No existen reintentos automaticos para pagos ambiguos.

El proyecto Compose `defusion-bcp-production` usa volumenes de base de datos y logs separados del sandbox. No importar documentos de pruebas. Implementar copias de seguridad y probar restauracion. Las respuestas historicas dependen del material criptografico con que se guardaron; conservar protegidas las versiones anteriores de certificados antes de rotarlos. El cifrado de almacenamiento requiere una revision de seguridad independiente del protocolo criptografico heredado del banco.

## Validacion pendiente

No se puede certificar acceso en la red destino ni conectividad BCP hasta disponer del servidor, certificados productivos y credenciales. Realizar primero una consulta de extractos; cualquier pago real requiere revision y confirmacion del operador y autorizacion bancaria.
