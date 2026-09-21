import { useEffect, useRef, useState } from 'react'
import { AlertCircle, Braces, Check, Copy, LoaderCircle, Send, X } from 'lucide-react'
import { api } from './api.js'

const accountFor = (payment, index) => index === '' || index == null ? undefined
  : payment.businessPartner?.bankAccounts?.find((account) => account.index === Number(index))
const regionFor = (payment, regions) => regions?.find((region) => region.code === payment.businessPartner?.region?.code)

function initialSelection(payment, selected, catalogs) {
  const partner = payment.businessPartner
  const requestedIndex = selected?.businessPartner?.selectedBankAccountIndex
  const index = accountFor(payment, requestedIndex)?.index ?? partner?.selectedBankAccountIndex ?? ''
  const account = accountFor(payment, index)
  return {
    docEntry: payment.docEntry,
    bankAccountIndex: index,
    bankCode: account?.bankCode ?? '',
    accountNumber: account?.accountNumber ?? '',
    region: regionFor(payment, catalogs.regions)?.code ?? '',
    documentNumber: partner?.documentNumber ?? '',
    documentType: partner?.documentType ?? '',
    documentExtension: partner?.documentExtension ?? '',
    documentComplement: '',
  }
}

export default function DiffusionPreview({ selectedPayments, sourceAccount, onClose, onGenerated, onSent, onBusy }) {
  const [prepared, setPrepared] = useState(null)
  const [selections, setSelections] = useState([])
  const [preview, setPreview] = useState(null)
  const [loading, setLoading] = useState(true)
  const [generating, setGenerating] = useState(false)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [bankConfig, setBankConfig] = useState(null)
  const [confirming, setConfirming] = useState(false)
  const [sending, setSending] = useState(false)
  const [result, setResult] = useState(null)
  const [uncertain, setUncertain] = useState(false)
  const [requestId, setRequestId] = useState(null)
  const sendLock = useRef(false)
  const confirmationRef = useRef(null)

  useEffect(() => {
    if (!confirming) return
    const previouslyFocused = document.activeElement
    const handleKey = (event) => {
      if (event.key === 'Escape') { event.preventDefault(); setConfirming(false) }
      if (event.key !== 'Tab') return
      const buttons = confirmationRef.current?.querySelectorAll('button:not(:disabled)')
      if (!buttons?.length) return
      const first = buttons[0], last = buttons[buttons.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', handleKey)
    return () => { document.removeEventListener('keydown', handleKey); previouslyFocused?.focus() }
  }, [confirming])

  useEffect(() => {
    let active = true
    async function prepare() {
      try {
        const result = await api.prepareDiffusion(selectedPayments.map((payment) => payment.docEntry))
        if (!active) return
        const initial = result.payments.map((payment) => initialSelection(payment,
          selectedPayments.find((selected) => selected.docEntry === payment.docEntry), result.catalogs))
        setPrepared(result)
        setSelections(initial)
        if (initial.every((item) => item.bankAccountIndex !== '' && item.region)) {
          const output = await api.previewDiffusion(initial, sourceAccount)
          if (active) { setPreview(output); onGenerated?.() }
        }
      } catch (requestError) { if (active) setError(requestError.message) }
      finally { if (active) setLoading(false) }
    }
    prepare()
    api.bankPaymentConfig().then((config) => { if (active) setBankConfig(config) })
      .catch(() => { if (active) setBankConfig({ ready: false, message: 'No se pudo obtener la configuracion del banco' }) })
    return () => { active = false }
  }, [])

  function update(docEntry, field, value) {
    if (sendLock.current || uncertain || result) return
    setPreview(null)
    setCopied(false)
    setError('')
    setSelections((current) => current.map((item) => {
      if (item.docEntry !== docEntry) return item
      if (field !== 'bankAccountIndex') return { ...item, [field]: value }
      const payment = prepared.payments.find((payment) => payment.docEntry === docEntry)
      const account = value === '' ? null : accountFor(payment, value)
      return { ...item, bankAccountIndex: value === '' ? '' : Number(value),
        bankCode: account?.bankCode ?? '', accountNumber: account?.accountNumber ?? '',
        region: regionFor(payment, prepared.catalogs.regions)?.code ?? '' }
    }))
  }

  async function generate(event) {
    event.preventDefault()
    if (sendLock.current || uncertain || result) return
    setGenerating(true)
    setError('')
    setPreview(null)
    setCopied(false)
    try {
      const output = await api.previewDiffusion(selections, sourceAccount)
      setPreview(output)
      onGenerated?.()
    } catch (requestError) { setError(requestError.message) }
    finally { setGenerating(false) }
  }

  async function copyJson() {
    try {
      await navigator.clipboard.writeText(JSON.stringify(preview.payload, null, 2))
      setCopied(true)
    } catch { setError('No se pudo copiar el JSON al portapapeles.') }
  }

  async function send() {
    if (sendLock.current || !preview || !bankConfig?.ready || result || uncertain) return
    const id = requestId || crypto.randomUUID()
    setRequestId(id)
    sendLock.current = true
    setSending(true)
    setConfirming(false)
    setError('')
    onBusy?.(true)
    try {
      const output = await api.sendDiffusion({ payments: selections, sourceAccount }, preview.fingerprint, id)
      setResult(output)
      onSent?.(output)
    } catch (requestError) {
      setError(requestError.message)
      // A lost browser response may follow a completed bank POST. Never offer a new automatic send.
      if (!requestError.status || requestError.status >= 500) {
        setUncertain(true)
        onSent?.({ status: 'UNKNOWN', requestId: id, message: 'Respuesta no confirmada. Revise el historial; no reenviar.' })
      } else if (requestError.status === 409) {
        setUncertain(true)
        onSent?.({ status: 'PROCESSING', requestId: id, message: requestError.message })
      } else {
        setRequestId(null)
        setPreview(null)
      }
    } finally {
      sendLock.current = false
      setSending(false)
      onBusy?.(false)
    }
  }

  const regions = new Set(selections.map((item) => item.region).filter(Boolean))
  const mixedRegions = regions.size > 1
  const incomplete = selections.some((item) => item.bankAccountIndex === '' || !item.region)

  return <section className="panel diffusion-preview" aria-label="Previsualización de difusión">
    <div className="panel-header"><div><h2>Previsualización de difusión</h2><p>{selectedPayments.length} pagos · {prepared?.catalogs.sourceAccounts?.find((entry) => entry.sapAccount === sourceAccount)?.name || sourceAccount} · {bankConfig?.environment === 'PRODUCTION' ? 'Producción' : 'Pruebas'}</p></div><button type="button" className="icon-button" title="Cerrar previsualización" disabled={sending} onClick={onClose}><X size={19} /></button></div>
    {loading ? <div className="panel-loading"><LoaderCircle className="spin" size={22} />Preparando lote desde SAP</div> : <>
      {error && <div className="alert error diffusion-alert" role="alert"><AlertCircle size={18} />{error}</div>}
      {result && <div className={`alert ${result.status === 'SENT' ? 'success' : 'warning'} diffusion-alert`} role="status"><span>{result.message}{result.bankTransactionId ? ` · Transaccion ${result.bankTransactionId}` : ''}</span></div>}
      {uncertain && <div className="alert warning diffusion-alert" role="status">Respuesta no confirmada. Consulte el historial y concilie con el banco antes de reenviar. Referencia: {requestId}</div>}
      {prepared && <form onSubmit={generate}>
        <fieldset className="diffusion-fields" disabled={generating || sending || uncertain || Boolean(result)}>
        <div className="table-scroll"><table className="diffusion-table"><thead><tr><th>Pago / proveedor</th><th>Cuenta beneficiaria</th><th>Región</th><th>Documento</th><th>Tipo</th><th>Extensión</th><th>Complemento</th></tr></thead><tbody>{prepared.payments.map((payment) => {
          const selection = selections.find((item) => item.docEntry === payment.docEntry)
          const partner = payment.businessPartner
          const account = selection.bankAccountIndex === '' ? null : accountFor(payment, selection.bankAccountIndex)
          const region = regionFor(payment, prepared.catalogs.regions)
          const bank = prepared.catalogs.banks.find((bank) => bank.code === (account?.bcpBankCode || account?.bankCode))
          return <tr key={payment.docEntry}>
            <td><strong>{payment.docNum}</strong><small>{payment.cardName}</small></td>
            <td><select aria-label={`Cuenta del pago ${payment.docNum}`} value={selection.bankAccountIndex} onChange={(event) => update(payment.docEntry, 'bankAccountIndex', event.target.value)} required><option value="">Seleccionar cuenta</option>{partner?.bankAccounts?.map((account) => <option key={account.index} value={account.index}>{account.accountNumber} · {account.bankName || account.bankCode}</option>)}</select><small>{account?.bankName || bank?.name || 'Banco sin equivalencia'} · {bank ? `${bank.code} · ${bank.code === '1005' ? 'PROV' : 'ACH'}` : 'Sin equivalencia'}</small></td>
            <td><strong>{region?.name ?? 'Sin región válida'}</strong><small>U_CITY: {partner?.sapCity || 'vacío'}{region ? ` · ${region.cityCode}` : ''}</small></td>
            <td><input aria-label={`Documento del pago ${payment.docNum}`} value={selection.documentNumber} maxLength={40} onChange={(event) => update(payment.docEntry, 'documentNumber', event.target.value)} readOnly={Boolean(partner?.documentNumber)} title={partner?.documentNumber ? 'FederalTaxID de SAP' : 'Pendiente en SAP'} /></td>
            <td><select aria-label={`Tipo de documento del pago ${payment.docNum}`} value={selection.documentType} onChange={(event) => update(payment.docEntry, 'documentType', event.target.value)} disabled={Boolean(partner?.documentType)} title={partner?.documentType ? 'U_TIPDOC de SAP' : 'Pendiente en SAP'}><option value="">Pendiente</option>{prepared.catalogs.documentTypes.map((entry) => <option key={entry.code} value={entry.code}>{entry.code} · {entry.name}</option>)}</select></td>
            <td><select aria-label={`Extensión del pago ${payment.docNum}`} value={selection.documentExtension} onChange={(event) => update(payment.docEntry, 'documentExtension', event.target.value)} disabled={Boolean(partner?.documentExtension)} title={partner?.documentExtension ? 'Extensión según U_CITY de SAP' : 'Pendiente en SAP'}><option value="">Pendiente</option>{prepared.catalogs.documentExtensions.map((entry) => <option key={entry.code} value={entry.code}>{entry.code} · {entry.name}</option>)}</select></td>
            <td><input aria-label={`Complemento del pago ${payment.docNum}`} value={selection.documentComplement} maxLength={20} onChange={(event) => update(payment.docEntry, 'documentComplement', event.target.value)} disabled={bank?.code === '1005'} /></td>
          </tr>
        })}</tbody></table></div>
        {mixedRegions && <div className="alert error diffusion-alert"><AlertCircle size={18} />Los pagos seleccionados pertenecen a distintas regiones. Cree un lote por región.</div>}
        {incomplete && <div className="alert warning diffusion-alert"><AlertCircle size={18} />Seleccione una cuenta para cada pago. BusinessPartners.U_CITY debe contener una región válida.</div>}
        <div className="diffusion-actions"><button className="button primary" disabled={generating || mixedRegions || incomplete}>{generating ? <LoaderCircle size={17} className="spin" /> : <Braces size={17} />}{generating ? 'Generando' : 'Generar JSON del lote'}</button></div>
        </fieldset>
      </form>}
      {preview && <>
        <div className="panel-header"><div><h2>JSON del lote · {preview.region.name}</h2><p>{preview.docEntries.length} pagos · {preview.correlationId}</p></div><button className="button secondary" type="button" onClick={copyJson}>{copied ? <Check size={16} /> : <Copy size={16} />}{copied ? 'Copiado' : 'Copiar JSON'}</button></div>
        <div className="diffusion-output"><ul className="diffusion-warnings">{preview.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul><textarea aria-label="JSON de difusión" className="code-editor" value={JSON.stringify(preview.payload, null, 2)} readOnly spellCheck="false" /></div>
        <div className="diffusion-actions"><span>{bankConfig?.message || 'Consultando configuracion bancaria'}</span><button type="button" className="button primary" disabled={!bankConfig?.ready || !preview.fingerprint || sending || Boolean(result) || uncertain} onClick={() => setConfirming(true)}>{sending ? <LoaderCircle className="spin" size={17} /> : <Send size={17} />}{sending ? 'Enviando' : bankConfig?.environment === 'PRODUCTION' ? 'Enviar a BCP · Producción' : 'Enviar a BCP · Pruebas'}</button></div>
      </>}
    </>}
    {confirming && <div className="modal-backdrop"><section ref={confirmationRef} className="bank-confirm" role="dialog" aria-modal="true" aria-labelledby="bank-confirm-title"><h2 id="bank-confirm-title">Confirmar envío a BCP</h2><dl><dt>Ambiente</dt><dd>{bankConfig?.environment === 'PRODUCTION' ? 'BCP Producción' : 'BCP Sandbox'}</dd><dt>Documentos SAP</dt><dd>{preview.docEntries.length}</dd><dt>Departamento</dt><dd>{preview.region.name}</dd><dt>Cuenta de origen</dt><dd>{preview.payload.sourceAccount}</dd><dt>Importe total</dt><dd>{new Intl.NumberFormat('es-BO', { minimumFractionDigits: 2 }).format(preview.payload.amount)} BOL</dd></dl><p>El lote se preparara en el banco y quedara pendiente de autorizacion.</p><div className="diffusion-actions"><button type="button" className="button secondary" autoFocus onClick={() => setConfirming(false)}><X size={17} />Cancelar</button><button type="button" className="button primary" onClick={send}><Send size={17} />Confirmar envio</button></div></section></div>}
  </section>
}
