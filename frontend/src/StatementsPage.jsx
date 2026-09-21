import { useEffect, useRef, useState } from 'react'
import { AlertCircle, CalendarDays, Copy, Eye, FileText, Landmark, LoaderCircle, RefreshCw, Search, X } from 'lucide-react'
import { api } from './api.js'
import { formatStatementData, statementQuery } from './statementJson.js'

export default function StatementsPage({ PageTitle, StatusBadge, dateTime }) {
  const [config, setConfig] = useState(null)
  const [account, setAccount] = useState('')
  const [period, setPeriod] = useState('')
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [reading, setReading] = useState(null)
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const sending = useRef(false)
  const resultPanel = useRef(null)

  useEffect(() => {
    let active = true
    Promise.all([api.statementConfig(), api.statements()]).then(([configuration, rows]) => {
      if (active) {
        setConfig(configuration); setAccount(configuration.accountNumber)
        setPeriod(configuration.period); setHistory(rows)
      }
    }).catch((error) => { if (active) setError(error.message) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])
  useEffect(() => { if (result) resultPanel.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }, [result])

  async function refresh() {
    setLoading(true)
    try { setHistory(await api.statements()) } catch (error) { setError(error.message) }
    finally { setLoading(false) }
  }
  async function submit(event) {
    event.preventDefault()
    if (sending.current) return
    let query
    try { query = statementQuery(account, period, config.accounts) }
    catch (error) { setError(error.message); return }
    sending.current = true
    setSubmitting(true); setError('')
    try {
      setResult(await api.createStatement(query))
      await refresh()
    } catch (error) { setError(error.message) }
    finally { sending.current = false; setSubmitting(false) }
  }
  async function view(id) {
    setReading(id); setError('')
    try { setResult(await api.statement(id)) }
    catch (error) { setError(error.message) }
    finally { setReading(null) }
  }
  async function copy(text) {
    try { await navigator.clipboard.writeText(text) }
    catch { setError('No se pudo copiar al portapapeles.') }
  }
  const accounts = config?.accounts ?? []
  const bank = result?.bankResponse
  const decoded = bank?.isOk === true ? bank.decryptedBody : bank?.decryptedMessage
  const responseText = formatStatementData(decoded)

  return <main className="page">
    <PageTitle eyebrow="TESORERIA / CONSULTAS" title="Extractos BCP" />
    {error && <div className="alert error" role="alert"><AlertCircle size={18} />{error}</div>}
    {config && !config.availability.ready && <div className="alert warning" role="alert">{config.availability.message}</div>}
    <section className="panel statement-composer">
      <div className="panel-header"><h2>Nueva consulta</h2>{!config && loading && <LoaderCircle size={18} className="spin" />}</div>
      <form onSubmit={submit}>
        <div className="statement-query-fields">
          <label><span>Cuenta bancaria</span><div className="input-icon"><Landmark size={17} /><select value={account} required disabled={!config || submitting} onChange={(event) => setAccount(event.target.value)}>
            {!accounts.length && <option value="">Sin cuentas disponibles</option>}
            {accounts.map((item) => <option key={item.accountNumber} value={item.accountNumber}>{item.name} · {item.accountNumber}</option>)}
          </select></div></label>
          <label><span>Periodo</span><div className="input-icon"><CalendarDays size={17} /><input type="month" required min="1900-01" max="2099-12" value={period ? `${period.slice(0, 4)}-${period.slice(4)}` : ''} disabled={!config || submitting} onChange={(event) => setPeriod(event.target.value.replace('-', ''))} /></div></label>
        </div>
        <div className="statement-submit"><button className="button primary" disabled={submitting || !config?.availability.ready || !accounts.length}>{submitting ? <LoaderCircle size={17} className="spin" /> : <Search size={17} />} {submitting ? 'Consultando BCP' : 'Consultar extractos'}</button></div>
      </form>
    </section>
    {result && <section className="panel statement-result" ref={resultPanel}>
      <div className="panel-header"><h2>Respuesta bancaria</h2><button className="icon-button" title="Cerrar respuesta" aria-label="Cerrar respuesta" onClick={() => setResult(null)}><X size={18} /></button></div>
      <div className="statement-result-meta"><strong>HTTP: {result.httpStatus ?? 'Sin respuesta'}</strong><StatusBadge status={result.status} /><span className="mono subdued">{result.correlationId}</span></div>
      {bank?.error && <div className="alert warning" role="alert">{bank.error}</div>}
      {!bank && <div className="alert warning">{result.detail}</div>}
      <div className="statement-response-toolbar"><strong><FileText size={16} /> Resultado</strong><button className="icon-button" title="Copiar resultado" aria-label="Copiar resultado" onClick={() => copy(responseText)}><Copy size={17} /></button></div>
      <pre className="statement-response-json mono">{responseText}</pre>
    </section>}
    <section className="panel">
      <div className="panel-header"><h2>Historial de consultas</h2><button className="icon-button" title="Actualizar historial" aria-label="Actualizar historial" disabled={loading || submitting} onClick={refresh}>{loading ? <LoaderCircle size={17} className="spin" /> : <RefreshCw size={17} />}</button></div>
      {!history.length ? <div className="panel-loading">{loading ? 'Cargando consultas' : 'Sin consultas registradas'}</div> : <div className="table-scroll"><table>
        <thead><tr><th>Fecha / usuario</th><th>Cuenta</th><th>Periodo</th><th>Estado</th><th>HTTP</th><th>Resultado</th></tr></thead>
        <tbody>{history.map((item) => <tr key={item.id}>
          <td>{dateTime(item.requestedAt)}<small>{item.requestedBy}</small></td><td className="mono">{item.maskedAccountNumber}</td><td className="mono">{item.period}</td>
          <td><StatusBadge status={item.status} /></td><td>{item.httpStatus ?? 'Sin respuesta'}</td>
          <td><button className="button secondary" disabled={reading !== null || submitting} onClick={() => view(item.id)}>{reading === item.id ? <LoaderCircle size={16} className="spin" /> : <Eye size={16} />} Ver respuesta bancaria</button></td>
        </tr>)}</tbody>
      </table></div>}
    </section>
  </main>
}
