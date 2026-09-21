# Portal de difusión de pagos BCP

Primera etapa del portal empresarial para registrar pagos múltiples y solicitudes de extractos. El backend usa Spring Boot 4, Java 21, MySQL, Flyway y sesiones seguras. El frontend está construido con React y Vite a partir del diseño entregado por Stitch.

## Alcance actual

- Login validado contra SAP Business One Service Layer y sesión HTTP local.
- Registro en MySQL de solicitudes de extractos manuales o automáticas.
- Registro de lotes, beneficiarios y montos de pagos múltiples.
- Auditoría con actor, fecha, origen, estado, IP e identificador de correlación.
- Vistas React de login, pagos, detalle de lote y extractos.
- Herramienta temporal para encriptar, firmar, verificar y desencriptar JSON de pruebas.
- Datos sensibles parcialmente ocultos en las respuestas del backend.
- Logs técnicos rotativos en `logs/defusion-bcp.log`.

El login SAP ya está habilitado. La sincronización de pagos desde SAP, la llamada al banco, el cifrado, la firma y el descifrado todavía no están habilitados. Las operaciones se guardan con estado `PENDING_INTEGRATION`.

## Ejecutar todo con Docker

Requisitos: Docker Desktop iniciado y Docker Compose v2.

En PowerShell, desde la carpeta del proyecto:

```powershell
cd C:\Users\HP\Desktop\defusion_bcp
Copy-Item .env.example .env
notepad .env
docker compose up --build -d
```

Cambiar en `.env` las claves de MySQL y configurar `SAP_BASE_URL`, `SAP_COMPANY_DB` y la validación TLS antes de ejecutar Compose. El usuario y la contraseña SAP se ingresan en el login y no se guardan en `.env`. Cuando los tres contenedores estén saludables, abrir:

Configure también las rutas de `BUSINESS.cer` y `ENC_DESA.pfx` en `BCP_BUSINESS_CERT_HOST_PATH` y `BCP_SIGNING_CERT_HOST_PATH`. Docker los monta como archivos de solo lectura; los certificados no se copian dentro de la imagen.

`MYSQL_USER` debe ser un usuario de aplicación, por ejemplo `defusion_app`; no use `root`. Las variables de inicialización de MySQL solo se aplican cuando el volumen se crea por primera vez.

- Portal: `http://localhost:5174`
- Backend: `http://localhost:8081`
- Salud del backend: `http://localhost:8081/actuator/health`
- MySQL desde el equipo: `localhost:3307`

Comandos útiles:

```powershell
docker compose ps
docker compose logs -f backend
docker compose logs -f frontend
docker compose down
```

`docker compose down` detiene los servicios sin borrar la base. Los datos de MySQL permanecen en el volumen `mysql-data` y los logs del backend en `backend-logs`.

Para reconstruir después de modificar código:

```powershell
docker compose up --build -d
```

## Preparar MySQL

Crear una base vacía. Flyway creará las tablas al iniciar el backend:

```sql
CREATE DATABASE defusion_bcp
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

Esta preparación manual no es necesaria al usar Docker Compose.

## Ejecutar el backend

En PowerShell:

```powershell
cd C:\Users\HP\Desktop\defusion_bcp
$env:DB_URL = "jdbc:mysql://localhost:3306/defusion_bcp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/La_Paz"
$env:DB_USERNAME = "usuario_mysql"
$env:DB_PASSWORD = "clave_mysql"
$env:APP_USERNAME = "admin"
$env:APP_PASSWORD = "una-clave-segura"
.\mvnw.cmd spring-boot:run
```

El backend queda disponible en `http://localhost:8080`. El endpoint de salud es `http://localhost:8080/actuator/health`.

## Ejecutar el frontend

En otra terminal:

```powershell
cd C:\Users\HP\Desktop\defusion_bcp\frontend
npm install
npm run dev
```

Abrir `http://localhost:5173` e ingresar las credenciales personales de SAP Business One.

## Tablas principales

- `bank_statement_requests`: quién solicitó cada extracto, periodo, origen y estado.
- `payment_batches`: lotes de pago, operador, cuenta de origen, total y estado.
- `payment_recipients`: personas o proveedores incluidos en cada lote.
- `process_audit_logs`: historial transversal de acciones y resultados.

## Endpoints disponibles

- `GET /api/auth/csrf`
- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`
- `GET|POST /api/statements`
- `GET|POST /api/payment-batches`
- `GET /api/payment-batches/{id}`
- `GET /api/audit-logs`
- `POST /api/crypto/encrypt`
- `POST /api/crypto/decrypt`
- `GET /api/sap/vendor-payments?from=AAAA-MM-DD&to=AAAA-MM-DD&page=0&size=20`
- `GET /api/sap/vendor-payments/{docEntry}`
- `POST /api/sap/vendor-payments/diffusion/prepare`
- `POST /api/sap/vendor-payments/diffusion/preview`
- `GET /api/sap/vendor-payments/diffusion/catalogs`

El listado admite `department=LP` (o CH, CB, OR, PO, TJ, SC, BE, PA). Sin ese parametro se consultan
todos los departamentos. El departamento se resuelve exclusivamente desde `BusinessPartners.U_CITY`,
con los valores 1..10 del catalogo `sapCities`. Sucre se agrupa en Chuquisaca y El Alto en La Paz.
El filtro cruza todas las paginas SAP del rango antes
de contar y paginar los pagos coincidentes; no filtra solo la pagina visible. Un proveedor con varias
cuentas aparece una sola vez y conserva la cuenta predeterminada y todas sus cuentas bancarias, aunque
sus ciudades difieran del departamento de la cabecera. Como la ciudad esta en el socio de negocio,
las consultas por departamento requieren recorrer el rango completo
y pueden tardar mas para rangos amplios.

## Previsualizacion de difusion

La cuenta de origen es obligatoria: `sourceAccount=11010501` (BCP LP, predeterminada) o
`sourceAccount=11010570` (BCP SC). La consulta SAP agrega siempre `TransferAccount eq 'CODIGO'`
al filtro de fechas y PBL; no se envian etiquetas ni numeros bancarios en ese filtro. Cualquier otro
valor se rechaza antes de consultar SAP. Tambien se comprueba la cuenta devuelta por SAP.
`sapTransferAccount` conserva la cuenta contable de origen; `transferAccount` sigue siendo la
cuenta beneficiaria del socio de negocio. Las vistas de detalle rechazan origenes fuera de las dos cuentas.

La previsualizacion vuelve a leer TransferAccount y verifica que todos los pagos pertenezcan al origen
elegido. La cabecera bancaria usa `2015009988370` para BCP LP y `20150838488388` para BCP SC,
sin guiones. No se usa una cuenta fija de entorno para este campo. La ciudad de la cuenta de origen
y el departamento del beneficiario (`U_CITY`, usado para `branchOfficeId`) se mantienen separados.
Cambiar el origen reinicia la pagina y limpia el lote seleccionado.

El listado tambien admite `bank=1016` (codigo del catalogo BCP); sin ese parametro muestra todos los
bancos. Combina banco, departamento y fechas antes de contar y paginar. El banco se compara con la
equivalencia resuelta de `BPBankAccounts.BankCode` / `Banks.BankName`, no con la cuenta contable.
Si un proveedor tiene cuentas en varios bancos, se muestra una sola vez y solo permite seleccionar las
cuentas del banco filtrado. Se conserva la predeterminada si coincide; si hay una unica coincidencia se
selecciona esa cuenta; si hay varias sin predeterminada se requiere confirmacion manual. Cambiar los
filtros aplicados limpia la seleccion del lote. Las consultas filtradas recorren el rango SAP completo.

La seleccion de pagos se conserva entre paginas y se limpia al cambiar el rango de fechas. La cuenta
beneficiaria viene de `BusinessPartners.BPBankAccounts.AccountNo`, no de la cuenta contable
`VendorPayments.TransferAccount`. Se usa la cuenta predeterminada del socio o la unica cuenta disponible;
si hay varias sin una predeterminada, el operador debe elegirla.

La region viene exclusivamente de `BusinessPartners.U_CITY`. Todos los pagos deben pertenecer a la misma
region y tener moneda local e importe de transferencia positivo. El codigo BCP `1005` genera lineas
`PROV`; los otros bancos del catalogo generan `ACH`. Los codigos se conservan en
`src/main/resources/bank/payment-catalogs.json`. Para BankCode que no coincidan con el catalogo,
se consulta `Banks?$filter=BankCode eq 'CODIGO'&$select=BankCode,BankName`. El nombre se compara
con nombres y alias explicitos del catalogo, normalizando acentos, mayusculas y sufijos societarios.
Por ejemplo, `BANECO` / `BANCO ECONOMICO S.A.` se resuelve como `1016`. Se conserva el BankCode SAP
para validar la cuenta y se usa el codigo BCP resuelto en `bankId` o para clasificar `PROV`/`ACH`.
La respuesta incluye `bankName` y `bcpBankCode`. Cada codigo se consulta una vez por listado,
sin guardar sesiones ni cuentas en cache global. Bancos desconocidos o resultados ambiguos no se
asignan automaticamente y bloquean la generacion del lote.

El backend vuelve a consultar SAP para generar el JSON y rechaza pagos duplicados, cancelados, cambios
de cuenta y regiones diferentes. No se cifra, firma ni envia al banco en esta etapa. La auditoria conserva
usuario, fecha, region, DocEntry y correlacion, pero no el cuerpo del lote ni cuentas completas.

El numero de documento viene de `BusinessPartners.FederalTaxID`, el tipo de `U_TIPDOC` (NIT -> T,
CI -> Q, etc.) y la extension se deriva de `U_CITY` segun la regla confirmada para esta integracion.
No se usa `U_ExtCI` ni `BPBankAccounts.City` para estos campos. Los valores reconocidos se completan
automaticamente; los ausentes o no reconocidos quedan editables y generan una advertencia si siguen
incompletos. El backend vuelve a leer los valores SAP y rechaza cambios respecto de la previsualizacion.
El complemento sigue siendo manual. En ACH, `branchOfficeId` usa el codigo del departamento (101..901),
no un 201 fijo. El catalogo conserva 999 para N/A, pero no sustituye automaticamente un U_CITY desconocido:
sin una region verificable no se puede asegurar que el lote pertenezca a una sola region.

Configure en el entorno `BANK_DIFFUSION_PASSWORD` y `BANK_DIFFUSION_DOCUMENT_NUMBER` para
completar la cabecera. `sourceAccount` se deriva del origen SAP permitido, no de
`BANK_DIFFUSION_SOURCE_ACCOUNT`. No hay credenciales en la plantilla ni se deben incluir en el
repositorio. Sin las variables de credenciales el JSON muestra esos campos vacios y una advertencia.

## Seguridad

No guardar contraseñas, certificados, sesiones SAP, firmas, claves privadas ni cuerpos cifrados en los logs. En producción se debe instalar la CA del servidor SAP y mantener `SAP_TLS_REJECT_UNAUTHORIZED=true`.

## Verificación

```powershell
.\mvnw.cmd test
cd frontend
npm run build
```
#   D i f u s i o n - B a n c o - B C P - - - D e l i z i a  
 