import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlertCircle, CalendarDays, Eye, Landmark, LoaderCircle, RefreshCw, Search } from 'lucide-react'
import { api } from './api.js'
import { statementQuery } from './statementJson.js'
import { statementPeriod } from './statementModel.js'

export default function StatementsPage({ PageTitle, StatusBadge, dateTime }) {
  const navigate = useNavigate()
  const [config, setConfig] = useState(null)
  const [account, setAccount] = useState('')
  const [period, setPeriod] = useState('')
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const sending = useRef(false)

  useEffect(() => {
    let active = true
    Promise.all([api.statementConfig(), api.statements()]).then(([configuration, rows]) => {
      if (active) {
        setConfig(configuration)
        setAccount(configuration.accountNumber)
        setPeriod(configuration.period)
        setHistory(rows)
      }
    }).catch((requestError) => { if (active) setError(requestError.message) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  async function refresh() {
    setLoading(true)
    try { setHistory(await api.statements()); setError('') }
    catch (requestError) { setError(requestError.message) }
    finally { setLoading(false) }
  }

  async function submit(event) {
    event.preventDefault()
    if (sending.current) return
    let query
    try { query = statementQuery(account, period, config.accounts) }
    catch (requestError) { setError(requestError.message); return }
    sending.current = true
    setSubmitting(true)
    setError('')
    try {
      const result = await api.createStatement(query)
      navigate(`/extractos/${result.id}`)
    } catch (requestError) { setError(requestError.message) }
    finally { sending.current = false; setSubmitting(false) }
  }

  const accounts = config?.accounts ?? []
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
    <section className="panel">
      <div className="panel-header"><h2>Historial de consultas</h2><button className="icon-button" title="Actualizar historial" aria-label="Actualizar historial" disabled={loading || submitting} onClick={refresh}>{loading ? <LoaderCircle size={17} className="spin" /> : <RefreshCw size={17} />}</button></div>
      {!history.length ? <div className="panel-loading">{loading ? 'Cargando consultas' : 'Sin consultas registradas'}</div> : <div className="table-scroll"><table>
        <thead><tr><th>Fecha / usuario</th><th>Cuenta</th><th>Periodo</th><th>Estado</th><th>HTTP</th><th>Detalle</th></tr></thead>
        <tbody>{history.map((item) => <tr key={item.id}>
          <td>{dateTime(item.requestedAt)}<small>{item.requestedBy}</small></td><td className="mono">{item.maskedAccountNumber}</td><td>{statementPeriod(item.period)}</td>
          <td><StatusBadge status={item.status} /></td><td>{item.httpStatus ?? 'Sin respuesta'}</td>
          <td><button className="button secondary" onClick={() => navigate(`/extractos/${item.id}`)}><Eye size={16} /> {item.status === 'COMPLETED' ? 'Ver extracto' : 'Ver resultado'}</button></td>
        </tr>)}</tbody>
      </table></div>}
    </section>
  </main>
}
