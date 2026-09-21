# Pruebas de pagos multiples BCP

1. Configure .env: BCP_BASIC_USERNAME y BCP_BASIC_PASSWORD son las credenciales
   Basic Auth. BANK_DIFFUSION_PASSWORD y BANK_DIFFUSION_DOCUMENT_NUMBER son los
   datos del preparador Credinet dentro del JSON cifrado; no son Basic Auth.
2. Revise la cabecera de pruebas en src/main/resources/bank/diffusion-header.json
   (empresa, tipo y extension del documento, autorizadores y origen/destino).
   Las cuentas e importe se calculan desde SAP y el catalogo de origen.
3. Si requiere mTLS, configure el certificado de autenticacion segun
   bank-auth-certs/README.md. No reutilice automaticamente el certificado de firma.
4. BCP_TLS_PROTOCOLS=TLSv1.3 es la configuracion verificada desde Java en Docker:
   el handshake TLS1.2 se cierra sin respuesta, mientras TLS1.3 negocia y valida
   el certificado correctamente. La captura antigua de Postman no garantiza
   igual comportamiento en otro runtime. La validacion de certificado y
   hostname siempre sigue activa; no se habilitan cifrados obsoletos.
5. Ponga BCP_PAYMENTS_ENABLED=true y ejecute docker compose up --build -d.
6. Abra http://localhost:5174, seleccione documentos, previsualice y confirme
   "Enviar a BCP - Pruebas". El backend envia POST al sandbox ProcessMultiple,
   Content-Type application/json y Basic Auth. El body contiene exactamente
   companyId, data y signature con el cifrado/firma existentes.

## Historial y seguridad de reenvios

MySQL conserva el lote, documentos SAP, importes, solicitante, fecha/hora,
estado, HTTP y TransactionId. El request y la respuesta bancaria se guardan
cifrados. Los logs guardan metadata, no credenciales, cuentas o payloads.
Conserve los certificados actuales para poder leer el historial cifrado.

SENT significa preparado, NO autorizado ni pagado: exige isOk=true, body
descifrado con Code="00" y TransactionId no vacio. Estos documentos ya no
aparecen como pendientes. UNKNOWN y PROCESSING tambien se excluyen para evitar
duplicados y requieren conciliacion manual con el banco. No hay liberacion
automatica ni reintento tras timeout, HTML/403, respuesta malformada o crash.
Un rechazo explicito isOk=false queda registrado y libera los documentos,
excepto codigos de conexion 15/16 que tambien requieren conciliacion.
El mismo requestId nunca genera otro POST; la reserva unica protege usuarios
concurrentes. La empresa SAP delimita los documentos y el historial.

La lista aplica exclusiones ANTES de contar y paginar, recorriendo paginas SAP
cuando existen documentos bloqueados. En instalaciones con muchos pagos esto
puede aumentar el tiempo de consulta; no se cambia el estado de SAP.

Las pruebas automaticas usan dobles de SAP/banco; no generan envios reales.
Antes de pruebas reales verifique con BCP los accesos, IP y cuenta/autorizadores
habilitados del ambiente sandbox.

## Resultado y recuperacion local de documentos

La columna Resultado muestra el body descifrado completo, conservando los campos
devueltos por BCP. Tambien se recupera para envios historicos cuya respuesta
cifrada se haya guardado con los certificados actuales.

En un lote SENT del ambiente de pruebas, Revertir documentos solicita motivo
y confirmacion del riesgo de duplicados. Esta accion libera solo el bloqueo
local de todos los documentos del lote, para volver a listarlos en pendientes.
NO cancela pagos en BCP, NO elimina ni modifica documentos en SAP y NO borra
el historial. Conserva el estado bancario SENT, fecha, usuario y motivo de la
reversion local, y registra eventos de auditoria. La opcion temporal permite
revertir SENT y UNKNOWN terminados SIN exigir conciliacion. Basta registrar
un motivo y aceptar el riesgo de duplicar pagos. Conserva el resultado bancario
original y registra en auditoria que fue una reversion temporal de pruebas.
PROCESSING no se puede liberar mientras el envio este en curso.

Esta opcion requiere BCP_ALLOW_DOCUMENT_REVERSAL=true y la URL exacta del
sandbox ProcessMultiple. Esta habilitada en el .env local de pruebas. Para
produccion configure BCP_ALLOW_DOCUMENT_REVERSAL=false: el backend rechaza
la reversion y la interfaz oculta el boton. La configuracion por defecto y
.env.example la dejan deshabilitada. Con una URL no sandbox tampoco se permite,
aunque se active la variable por error.

Ver respuesta bancaria muestra el JSON original recibido, su body encriptado,
el body desencriptado completo y cualquier error de descifrado. Si no hubo
respuesta HTTP, lo indica explicitamente: no hay un body que recuperar ni
descifrar. No se inventa ni se reutiliza una respuesta de ejemplo para otro lote.

## Logs y sesion SAP

Logs de Actividades centraliza los eventos de pagos y extractos en dos vistas
paginadas. Las actividades ya no aparecen al pie de las paginas operativas.

La sesion vence tras 30 minutos sin consultas exitosas a SAP (o antes si SAP
define un timeout menor o rechaza la sesion). Solo respuestas exitosas de SAP
renuevan el plazo. Consultar logs, historial o auth/me no lo renueva.
El backend bloquea nuevas operaciones al vencer el plazo y el frontend muestra
Sesion expirada: Aceptar lleva al login. No hay reautenticacion automatica ni se
conserva la contrasena SAP.
