import { useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, ClipboardList, LoaderCircle, RefreshCw, Search } from 'lucide-react'
import { api } from './api.js'
import { batchResults, paymentGroups } from './batchDetailModel.js'

const amount = (value) => value == null ? '—' : new Intl.NumberFormat('es-BO', {
  minimumFractionDigits: 2, maximumFractionDigits: 2,
}).format(Number(value))
const shown = (value) => value == null || value === '' ? '—' : String(value)

function PaymentLines({ title, rows }) {
  return <div className="batch-payment-lines">
    <h4>{title} <span>{rows.length}</span></h4>
    <div className="table-scroll"><table>
      <thead><tr><th>Linea</th><th>Beneficiario</th><th>Cuenta</th><th>Documento</th><th>Importe</th><th>Estado</th></tr></thead>
      <tbody>{rows.map((row, index) => <tr key={`${row.Line ?? index}-${index}`}>
        <td>{shown(row.Line)}</td>
        <td>{shown(row.TitularName ?? row.Name ?? row.GlossPayment ?? row.FirstDetail)}</td>
        <td className="mono">{shown(row.AccountNumber)}</td>
        <td>{shown(row.DocumentNumber)}</td>
        <td className="amount">{amount(row.Amount)}</td>
        <td>{shown(row.OperationStatusDescription ?? row.StatusOperation ?? row.Status ?? row.Message)}</td>
      </tr>)}</tbody>
    </table></div>
  </div>
}

function BatchResponse({ detail, dateTime, StatusBadge }) {
  const results = batchResults(detail.body)
  return <section className="panel batch-response" aria-live="polite">
    <div className="panel-header"><div><h2>Respuesta del lote {detail.submission.transactionId}</h2><p>Consultado el {dateTime(detail.checkedAt)} · HTTP {shown(detail.httpStatus)}</p></div><StatusBadge status={detail.status} /></div>
    {detail.error && <div className="alert error batch-response-alert" role="alert">{detail.error}</div>}
    {detail.message && <div className={`alert ${detail.status === 'COMPLETED' ? 'success' : 'warning'} batch-response-alert`}>{detail.message}</div>}
    {results.map((result, index) => <div className="batch-result" key={`${result.ProcessBatchId ?? index}-${index}`}>
      <div className="batch-result-heading"><h3>Proceso {shown(result.ProcessBatchId)}</h3><span>{shown(result.StatusOperation)}</span></div>
      <dl className="batch-result-facts">
        <div><dt>Tipo</dt><dd>{shown(result.TypeOperation)}</dd></div>
        <div><dt>Importe</dt><dd>{amount(result.Amount)} {shown(result.Currency)}</dd></div>
        <div><dt>Cuenta origen</dt><dd>{shown(result.SourceAccount)}</dd></div>
        <div><dt>Fecha proceso</dt><dd>{shown(result.DateProcess)}</dd></div>
        <div className="batch-result-wide"><dt>Descripcion</dt><dd>{shown(result.Description)}</dd></div>
      </dl>
      {Array.isArray(result.UserInvolveds) && result.UserInvolveds.length > 0 && <div className="batch-payment-lines">
        <h4>Autorizadores <span>{result.UserInvolveds.length}</span></h4>
        <div className="table-scroll"><table><thead><tr><th>Usuario</th><th>Estado</th><th>Fecha</th></tr></thead><tbody>
          {result.UserInvolveds.map((user, userIndex) => <tr key={userIndex}><td>{shown(user.UserName)}</td><td>{shown(user.UserDescription)}</td><td>{shown(user.DateAction)}</td></tr>)}
        </tbody></table></div>
      </div>}
      {paymentGroups(result).map(([title, rows]) => <PaymentLines key={title} title={title} rows={rows} />)}
    </div>)}
    {detail.body && <details className="batch-response-json"><summary>Ver respuesta completa del banco</summary><pre>{JSON.stringify(detail.body, null, 2)}</pre></details>}
    {!detail.body && !detail.error && <p className="batch-no-result">El banco no devolvio un body de detalle.</p>}
  </section>
}

export default function BatchDetailsPage({ PageTitle, StatusBadge, dateTime }) {
  const [page, setPage] = useState(0)
  const [refreshKey, setRefreshKey] = useState(0)
  const [history, setHistory] = useState(null)
  const [loading, setLoading] = useState(true)
  const [listError, setListError] = useState('')
  const [selectedId, setSelectedId] = useState(null)
  const [querying, setQuerying] = useState(false)
  const [queryError, setQueryError] = useState('')
  const [detail, setDetail] = useState(null)
  const resultRef = useRef(null)

  useEffect(() => {
    let active = true
    setLoading(true)
    setListError('')
    api.bankBatchDetails(page).then((value) => { if (active) setHistory(value) })
      .catch((error) => { if (active) setListError(error.message) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [page, refreshKey])

  useEffect(() => { if (detail) resultRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }, [detail])

  async function query(item) {
    setSelectedId(item.submissionId)
    setDetail(null)
    setQueryError('')
    setQuerying(true)
    try { setDetail(await api.queryBankBatchDetail(item.submissionId)) }
    catch (error) { setQueryError(error.message) }
    finally { setQuerying(false) }
  }

  return <main className="page batch-details-page">
    <PageTitle eyebrow="BCP / PAGOS MULTIPLES" title="Detalle de pagos" description="Consulta el estado de los lotes recibidos exitosamente por BCP." actions={<button className="button secondary" onClick={() => setRefreshKey((key) => key + 1)} disabled={loading}><RefreshCw size={16} /> Actualizar</button>} />
    <section className="panel">
      <div className="panel-header"><div><h2>Lotes con ID de transaccion</h2><p>Solo se muestran envios aceptados con codigo 00.</p></div>{history && <span className="batch-total">{history.totalElements} registros</span>}</div>
      {listError && <div className="alert error batch-response-alert" role="alert">{listError}</div>}
      {loading ? <div className="panel-loading"><LoaderCircle size={18} className="spin" /> Cargando lotes</div>
        : !history?.items?.length ? <div className="empty-state"><div><ClipboardList size={20} /></div><strong>No hay lotes exitosos en esta pagina</strong><p>Los lotes enviados con ID bancario apareceran aqui.</p></div>
          : <div className="table-scroll"><table className="batch-list-table"><thead><tr><th>Enviado</th><th>ID BCP</th><th>Cuenta origen</th><th>Region</th><th>Importe BOL</th><th>Documentos SAP</th><th>Detalle</th></tr></thead><tbody>
            {history.items.map((item) => <tr key={item.submissionId} className={selectedId === item.submissionId ? 'selected-row' : ''}>
              <td>{dateTime(item.sentAt)}<small>{shown(item.requestedBy)}</small></td>
              <td className="mono">{item.transactionId}</td><td className="mono">{item.sourceAccount}</td><td>{item.region}</td>
              <td className="amount">{amount(item.amount)}</td>
              <td>{item.documents?.map((document) => <small key={document.docEntry}>{document.docNum} · {document.cardName}</small>)}</td>
              <td><button className="button secondary" disabled={querying} onClick={() => query(item)}>{querying && selectedId === item.submissionId ? <LoaderCircle size={16} className="spin" /> : <Search size={16} />} Consultar detalle</button></td>
            </tr>)}
          </tbody></table></div>}
      {history?.totalPages > 1 && <div className="batch-pagination"><span>Pagina {history.page + 1} de {history.totalPages}</span><div><button className="icon-button" title="Pagina anterior" aria-label="Pagina anterior" disabled={page === 0 || loading} onClick={() => setPage((value) => value - 1)}><ChevronLeft size={18} /></button><button className="icon-button" title="Pagina siguiente" aria-label="Pagina siguiente" disabled={page + 1 >= history.totalPages || loading} onClick={() => setPage((value) => value + 1)}><ChevronRight size={18} /></button></div></div>}
    </section>
    {queryError && <div className="alert error" role="alert">{queryError}</div>}
    <div ref={resultRef}>{detail && <BatchResponse detail={detail} dateTime={dateTime} StatusBadge={StatusBadge} />}</div>
  </main>
}
