import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AlertCircle, ArrowLeft, Download, Eye, LoaderCircle, X } from 'lucide-react'
import { api } from './api.js'
import { formatStatementData } from './statementJson.js'
import { parseStatement, statementAmount, statementDate, statementPeriod, transactionLabels } from './statementModel.js'

const PAGE_SIZE = 20

function displayValue(key, value) {
  if (value == null || value === '') return '—'
  if (key === 'Date') return statementDate(value)
  if (key === 'Amount') return statementAmount(value)
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

export default function StatementDetailPage({ PageTitle, StatusBadge, dateTime }) {
  const { id } = useParams()
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [pdfBusy, setPdfBusy] = useState(false)
  const [page, setPage] = useState(0)
  const [selectedIndex, setSelectedIndex] = useState(null)

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    api.statement(id).then((data) => { if (active) setResult(data) })
      .catch((requestError) => { if (active) setError(requestError.message) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [id])

  const bank = result?.bankResponse
  const document = bank?.isOk === true ? parseStatement(bank.decryptedBody) : null
  const transactions = document?.Transactions ?? []
  const totalPages = Math.ceil(transactions.length / PAGE_SIZE)
  const visible = transactions.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
  const selected = selectedIndex == null ? null : transactions[selectedIndex]

  async function download() {
    setPdfBusy(true)
    setError('')
    try {
      const { buildStatementPdf } = await import('./statementPdf.js')
      const bytes = await buildStatementPdf(document, result)
      const blob = new Blob([bytes], { type: 'application/pdf' })
      const url = URL.createObjectURL(blob)
      const link = window.document.createElement('a')
      link.href = url
      link.download = `Extracto_BCP_${String(document.AccountNumber ?? '').replace(/\D/g, '')}_${String(document.Period ?? '').replace(/\D/g, '')}.pdf`
      window.document.body.append(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 60000)
    } catch (requestError) { setError(`No se pudo generar el PDF: ${requestError.message}`) }
    finally { setPdfBusy(false) }
  }

  return <main className="page statement-detail-page">
    <PageTitle eyebrow="EXTRACTOS BCP / DETALLE" title={document ? `Extracto ${statementPeriod(document.Period)}` : 'Detalle de consulta'}
      description={document ? `Cuenta ${document.AccountNumber ?? result?.maskedAccountNumber} · ${document.Currency ?? 'Moneda no indicada'}` : result ? `Cuenta ${result.maskedAccountNumber} · Periodo ${statementPeriod(result.period)}` : ''}
      actions={<div className="statement-detail-actions"><Link className="button secondary" to="/extractos"><ArrowLeft size={17} /> Volver</Link>{document && <button className="button primary" disabled={pdfBusy} onClick={download}>{pdfBusy ? <LoaderCircle size={17} className="spin" /> : <Download size={17} />} Descargar PDF</button>}</div>} />
    {error && <div className="alert error" role="alert"><AlertCircle size={18} />{error}</div>}
    {loading && <div className="panel-loading"><LoaderCircle size={20} className="spin" /> Cargando extracto</div>}
    {result && <>
      <div className="statement-detail-status"><strong>HTTP: {result.httpStatus ?? 'Sin respuesta'}</strong><StatusBadge status={result.status} /><span>Consultado el {dateTime(result.requestedAt)} por {result.requestedBy}</span></div>
      {bank?.error && <div className="alert warning" role="alert">{bank.error}</div>}
      {document ? <>
        <section className="statement-summary" aria-label="Cabecera del extracto">
          <h2>Datos de la cuenta</h2>
          <dl className="statement-summary-grid">
            <div><dt>CIC</dt><dd>{String(document.Cic ?? '').trim() || '—'}</dd></div>
            <div><dt>Número de cuenta</dt><dd className="mono">{document.AccountNumber ?? '—'}</dd></div>
            <div><dt>Tipo de cuenta</dt><dd>{document.AccountType ?? '—'}</dd></div>
            <div><dt>Moneda</dt><dd>{document.Currency ?? '—'}</dd></div>
            <div><dt>Periodo</dt><dd>{statementPeriod(document.Period)}</dd></div>
            <div><dt>Saldo inicial</dt><dd>{statementAmount(document.InitialBalance)}</dd></div>
            <div><dt>Saldo final</dt><dd>{statementAmount(document.EndingBalance)}</dd></div>
            <div><dt>Movimientos</dt><dd>{transactions.length}</dd></div>
          </dl>
        </section>
        <section className="panel statement-transactions">
          <div className="panel-header"><h2>Transacciones</h2><span className="subdued">{transactions.length} movimientos</span></div>
          {!transactions.length ? <div className="panel-loading">Sin movimientos en este periodo</div> : <>
            <div className="table-scroll"><table>
              <thead><tr><th>Fecha / hora</th><th>Operación</th><th>Descripción / glosa</th><th>Canal / ubicación</th><th className="amount">Importe</th><th>Detalle</th></tr></thead>
              <tbody>{visible.map((transaction, index) => {
                const absoluteIndex = page * PAGE_SIZE + index
                return <tr key={`${transaction?.Id ?? 'movimiento'}-${absoluteIndex}`} className={selectedIndex === absoluteIndex ? 'selected-row' : ''}>
                  <td>{statementDate(transaction?.Date)}<small>{transaction?.Hour || '—'}</small></td>
                  <td className="mono">{transaction?.HostOperationNumber || '—'}<small>ID {transaction?.Id ?? '—'}</small></td>
                  <td><strong>{transaction?.Description || '—'}</strong><small>{transaction?.Gloss || '—'}</small></td>
                  <td>{transaction?.Channel || '—'}<small>{transaction?.Location || '—'}</small></td>
                  <td className="amount"><strong>{statementAmount(transaction?.Amount)}</strong></td>
                  <td><button className="icon-button" title={`Ver transacción ${transaction?.Id ?? absoluteIndex + 1}`} aria-label={`Ver transacción ${transaction?.Id ?? absoluteIndex + 1}`} onClick={() => setSelectedIndex(absoluteIndex)}><Eye size={17} /></button></td>
                </tr>
              })}</tbody>
            </table></div>
            {totalPages > 1 && <div className="pagination"><span>Mostrando {page * PAGE_SIZE + 1}-{Math.min((page + 1) * PAGE_SIZE, transactions.length)} de {transactions.length}</span><div className="pagination-controls"><button className="button secondary" disabled={page === 0} onClick={() => { setPage(page - 1); setSelectedIndex(null) }}>Anterior</button><strong>{page + 1} / {totalPages}</strong><button className="button secondary" disabled={page + 1 >= totalPages} onClick={() => { setPage(page + 1); setSelectedIndex(null) }}>Siguiente</button></div></div>}
          </>}
        </section>
        {selected && <section className="statement-transaction-detail" aria-label="Detalle de transacción">
          <div className="statement-detail-heading"><h2>Transacción {selected.Id ?? selectedIndex + 1}</h2><button className="icon-button" title="Cerrar detalle" onClick={() => setSelectedIndex(null)}><X size={17} /></button></div>
          <dl>{Object.entries(selected).map(([key, value]) => <div key={key}><dt>{transactionLabels[key] ?? key}</dt><dd>{displayValue(key, value)}</dd></div>)}</dl>
        </section>}
        <p className="statement-pdf-note">El PDF es una copia generada por el portal a partir de la respuesta recibida del banco.</p>
      </> : <section className="panel statement-result">
        <div className="panel-header"><h2>{bank?.isOk === false ? 'Consulta rechazada por BCP' : 'Respuesta bancaria'}</h2></div>
        <div className="statement-result-message">{bank?.decryptedMessage || result.detail || 'No hay un extracto estructurado disponible para esta consulta.'}</div>
        {bank?.isOk === true && bank.decryptedBody && <pre className="statement-response-json mono">{formatStatementData(bank.decryptedBody)}</pre>}
      </section>}
    </>}
  </main>
}
