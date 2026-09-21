import { useEffect, useRef, useState } from 'react'
import { RefreshCw, Undo2, LoaderCircle, Eye, X } from 'lucide-react'
import { api } from './api.js'

function formatBody(body) {
  try { return JSON.stringify(JSON.parse(body), null, 2) } catch { return body }
}

export default function BankSubmissionHistory({ history, error, busy, sourceAccounts, refresh, onReleased, onBusy, dateTime, money, StatusBadge }) {
  const [selected, setSelected] = useState(null)
  const [reason, setReason] = useState('')
  const [acknowledge, setAcknowledge] = useState(false)
  const [viewing, setViewing] = useState(null)
  const [saving, setSaving] = useState(false)
  const [releaseError, setReleaseError] = useState('')
  const dialog = useRef(null)
  const opener = useRef(null)

  useEffect(() => {
    if (selected) dialog.current?.querySelector('textarea')?.focus()
    else if (viewing) dialog.current?.querySelector('button')?.focus()
    else opener.current?.focus()
  }, [selected, viewing])

  function open(batch, event) {
    opener.current = event.currentTarget
    setSelected(batch); setReason(''); setAcknowledge(false); setReleaseError('')
  }
  function keys(event) {
    if (event.key === 'Escape' && !saving) { setSelected(null); setViewing(null) }
    if (event.key === 'Tab') {
      const elements = [...dialog.current.querySelectorAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled)')]
      const first = elements[0], last = elements.at(-1)
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
  }
  async function release(event) {
    event.preventDefault(); setSaving(true); onBusy(true); setReleaseError('')
    try {
      await api.releaseDiffusion(selected.id, reason.trim(), acknowledge)
      setSelected(null)
      await onReleased()
    } catch (error) { setReleaseError(error.message) }
    finally { setSaving(false); onBusy(false) }
  }

  return <>
    <section className="panel">
      <div className="panel-header"><h2>Historial de envíos BCP</h2><button className="icon-button" title="Actualizar historial" disabled={busy || saving} onClick={refresh}><RefreshCw size={17} /></button></div>
      {error && <div className="alert error">{error}</div>}
      {!history.length ? <div className="panel-loading">Sin envios registrados</div> : <div className="table-scroll"><table>
        <thead><tr><th>Fecha / usuario</th><th>Documentos SAP</th><th>Cuenta / region</th><th>Importe BOL</th><th>Estado</th><th>Transaccion BCP</th><th>Resultado</th><th>Acciones</th></tr></thead>
        <tbody>{history.map((batch) => <tr key={batch.id}>
          <td>{dateTime(batch.createdAt)}<small>{batch.requestedBy}</small><small className="mono">{batch.requestId}</small></td>
          <td>{batch.documents.map((document) => <small key={document.docEntry}>{document.docNum} · {document.cardCode} · {document.cardName}</small>)}</td>
          <td>{sourceAccounts.find((account) => account.sapAccount === batch.sourceAccount)?.name || batch.sourceAccount}<small>{batch.region}</small></td>
          <td className="amount">{money(batch.amount)}</td><td><StatusBadge status={batch.status} />{batch.releasedAt && <small>Revertido localmente<br />{dateTime(batch.releasedAt)} · {batch.releasedBy}</small>}</td>
          <td className="mono">{batch.bankTransactionId || 'Pendiente'}</td>
          <td className="bank-result"><strong>HTTP: {batch.httpStatus ?? 'Sin respuesta'}</strong><button className="button secondary" onClick={(event) => { opener.current = event.currentTarget; setViewing(batch) }}><Eye size={16} /> Ver respuesta bancaria</button></td>
          <td>{batch.canRevertDocuments ? <button className="button secondary" disabled={busy || saving} onClick={(event) => open(batch, event)}><Undo2 size={16} /> Revertir documentos</button> : <small>{batch.releasedAt ? batch.releaseReason : batch.status === 'PROCESSING' ? 'Envio en proceso: espere su resultado' : ['SENT', 'UNKNOWN'].includes(batch.status) && batch.documents.length ? 'Reversion deshabilitada' : 'Sin documentos bloqueados'}</small>}</td>
        </tr>)}</tbody>
      </table></div>}
    </section>
    {selected && <div className="modal-backdrop" onKeyDown={keys}>
      <section className="bank-confirm" ref={dialog} role="dialog" aria-modal="true" aria-labelledby="release-title" aria-describedby="release-warning">
        <h2 id="release-title">Revertir documentos de pruebas</h2>
        <p id="release-warning">Esto solo elimina el bloqueo local de {selected.documents.length} documentos. No borra documentos SAP ni cancela el lote {selected.bankTransactionId} en BCP. Reenviarlos puede duplicar los pagos. El historial se conserva.</p>
        {selected.status === 'UNKNOWN' && <div className="alert warning">Resultado bancario no confirmado. La opcion temporal de pruebas permite revertir sin conciliacion, pero reenviar puede duplicar pagos en BCP.</div>}
        {releaseError && <div className="alert error" role="alert">{releaseError}</div>}
        <form onSubmit={release} className="form-stack">
          <label><span>Motivo</span><textarea required maxLength={500} value={reason} disabled={saving} onChange={(event) => setReason(event.target.value)} /></label>
          <label className="risk-ack"><input type="checkbox" required checked={acknowledge} disabled={saving} onChange={(event) => setAcknowledge(event.target.checked)} /><span>Confirmo que es una prueba y entiendo el riesgo de duplicar pagos.</span></label>
          <div className="diffusion-actions"><button type="button" className="button secondary" disabled={saving} onClick={() => setSelected(null)}>Cancelar</button><button className="button primary" disabled={saving || !acknowledge || !reason.trim()}>{saving ? <LoaderCircle size={16} className="spin" /> : <Undo2 size={16} />} Revertir localmente</button></div>
        </form>
      </section>
    </div>}
    {viewing && <div className="modal-backdrop" onKeyDown={keys}><section className="bank-confirm bank-response-dialog" ref={dialog} role="dialog" aria-modal="true" aria-labelledby="response-title">
      <div className="panel-header"><h2 id="response-title">Respuesta bancaria</h2><button className="icon-button" title="Cerrar respuesta" onClick={() => setViewing(null)}><X size={18} /></button></div>
      <p>HTTP: {viewing.httpStatus ?? 'Sin respuesta'} · Estado: {viewing.status}</p>
      <p>{viewing.message}</p>
      {viewing.bankResponse?.error && <div className="alert warning">{viewing.bankResponse.error}</div>}
      <h3>Respuesta original BCP</h3><pre className="bank-body">{viewing.bankResponse?.rawResponse ? formatBody(viewing.bankResponse.rawResponse) : 'No hay una respuesta bancaria guardada para este envio.'}</pre>
      <h3>Body encriptado</h3><pre className="bank-body">{viewing.bankResponse?.encryptedBody || 'No disponible'}</pre>
      <h3>Body desencriptado</h3><pre className="bank-body">{viewing.decryptedBody ? formatBody(viewing.decryptedBody) : 'No disponible'}</pre>
    </section></div>}
  </>
}
