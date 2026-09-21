import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, Navigate, Route, Routes, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  Activity, AlertCircle, ArrowLeft, Banknote, Braces, Building2, CalendarDays, Check,
  ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, CircleDollarSign, Eye, EyeOff, FileClock, FileText,
  Copy, Settings, Landmark, LoaderCircle, LockKeyhole, LogOut, Menu,
  RefreshCw, Search, ShieldCheck, SlidersHorizontal, UserRound, Users,
  WalletCards, X,
} from 'lucide-react'
import { api } from './api.js'
import DiffusionPreview from './DiffusionPreview.jsx'
import SessionGuard from './SessionGuard.jsx'
import BankSubmissionHistory from './BankSubmissionHistory.jsx'
import StatementsPage from './StatementsPage.jsx'
import deliziaLogo from './img/logo-negativo.png'
import { portalRelease } from './portalRelease.js'

const money = (amount, currency = 'BOB') => new Intl.NumberFormat('es-BO', {
  style: 'currency', currency, minimumFractionDigits: 2,
}).format(Number(amount ?? 0))

const dateTime = (value) => value
  ? new Intl.DateTimeFormat('es-BO', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : '—'

const dateOnly = (value) => value
  ? new Intl.DateTimeFormat('es-BO', { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(value))
  : '—'

const toDateInput = (value) => {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'America/La_Paz',
    year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(value)
  const part = (type) => parts.find((entry) => entry.type === type).value
  const year = part('year'), month = part('month'), day = part('day')
  return `${year}-${month}-${day}`
}

const statusLabels = {
  PENDING_INTEGRATION: 'Pendiente de integración', PROCESSING: 'Procesando', SENT: 'Enviado',
  AUTHORIZED: 'Autorizado', REJECTED: 'Rechazado', FAILED: 'Error', COMPLETED: 'Completado',
  UNKNOWN: 'Por conciliar',
}

function App() {
  const [user, setUser] = useState(undefined)

  useEffect(() => {
    api.me().then(setUser).catch(() => setUser(null))
  }, [])

  if (user === undefined) return <FullScreenLoader label="Preparando portal seguro" />

  return (
    <Routes>
      <Route path="/login" element={user ? <Navigate to="/pagos" replace /> : <LoginPage onLogin={setUser} />} />
      <Route path="/*" element={user ? <SessionGuard key={user.username} user={user} onExpired={() => setUser(null)}><PortalLayout user={user} onLogout={() => setUser(null)} /></SessionGuard> : <Navigate to="/login" replace />} />
    </Routes>
  )
}

function FullScreenLoader({ label }) {
  return <div className="fullscreen-loader"><LoaderCircle className="spin" /><span>{label}</span></div>
}

function Logo({ compact = false }) {
  return (
    <div className={`brand ${compact ? 'brand-compact' : ''}`}>
      <div className="brand-logo"><img src={deliziaLogo} alt="Delizia" width="180" height="101" /></div>
      {!compact && <div><strong>Portal de Pagos</strong><small>Integración BCP</small></div>}
    </div>
  )
}

function LoginPage({ onLogin }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  async function submit(event) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      onLogin(await api.login(username, password))
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <div className="login-status"><span className="dot success" /> Autenticación conectada a SAP Business One</div>
      <section className="login-card">
        <div className="login-stripe" />
        <div className="login-content">
          <div className="login-heading">
            <Logo />
            <h1>Acceso con SAP</h1>
            <p>Gestión de pagos múltiples y extractos bancarios.</p>
          </div>
          <div className="security-note"><ShieldCheck size={18} /><span>Acceso exclusivo para personal autorizado.</span></div>
          {error && <div className="alert error"><AlertCircle size={18} /><span>{error}</span></div>}
          <form onSubmit={submit} className="form-stack">
            <label>
              <span>Usuario SAP</span>
              <div className="input-icon"><UserRound size={18} /><input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required placeholder="Usuario de SAP Business One" /></div>
            </label>
            <label>
              <span>Contraseña</span>
              <div className="input-icon"><LockKeyhole size={18} /><input value={password} onChange={(e) => setPassword(e.target.value)} type={showPassword ? 'text' : 'password'} autoComplete="current-password" required placeholder="Contraseña" /><button className="icon-button" type="button" onClick={() => setShowPassword(!showPassword)} title={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}>{showPassword ? <EyeOff size={18} /> : <Eye size={18} />}</button></div>
            </label>
            <button className="button primary login-button" disabled={submitting}>
              {submitting ? <><LoaderCircle size={18} className="spin" /> Verificando</> : <>Iniciar sesión <ChevronRight size={18} /></>}
            </button>
          </form>
          <div className="login-security"><LockKeyhole size={15} /> Las credenciales se validan directamente en SAP</div>
        </div>
      </section>
      <footer>Portal de difusión de pagos · Versión {portalRelease.version}</footer>
    </main>
  )
}

function PortalLayout({ user, onLogout }) {
  const location = useLocation()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)

  async function logout() {
    try { await api.logout() } finally { onLogout(); navigate('/login') }
  }

  const nav = [
    { to: '/pagos', label: 'Pagos múltiples', icon: WalletCards },
    { to: '/extractos', label: 'Extractos BCP', icon: FileText },
    { to: '/logs', label: 'Logs de Actividades', icon: FileClock },
    { to: '/configuracion', label: 'Configuración', icon: Settings },
  ]

  return (
    <div className="portal-shell">
      <aside className={`sidebar ${menuOpen ? 'open' : ''}`}>
        <div className="sidebar-top"><Logo /><button className="icon-button sidebar-close" onClick={() => setMenuOpen(false)}><X size={20} /></button></div>
        <nav>
          <p className="nav-label">OPERACIONES</p>
          {nav.map(({ to, label, icon: Icon }) => <Link key={to} to={to} onClick={() => setMenuOpen(false)} className={location.pathname.startsWith(to) ? 'active' : ''}><Icon size={19} /><span>{label}</span></Link>)}
        </nav>
        <div className="sidebar-user">
          <div className="avatar">{user.username.slice(0, 2).toUpperCase()}</div>
          <div><strong>{user.username}</strong><small>{user.companyDb ?? 'SAP Business One'}</small></div>
          <button className="icon-button inverse" onClick={logout} title="Cerrar sesión"><LogOut size={18} /></button>
        </div>
      </aside>
      {menuOpen && <button className="sidebar-backdrop" onClick={() => setMenuOpen(false)} aria-label="Cerrar menú" />}
      <div className="workspace">
        <header className="topbar">
          <button className="icon-button mobile-menu" onClick={() => setMenuOpen(true)}><Menu size={21} /></button>
          <div className="environment"><span className="dot success" /> Backend conectado</div>
          <div className="integration-status"><span>SAP</span><b>Conectado</b><span>Portal</span><b>v{portalRelease.version}</b></div>
        </header>
        <Routes>
          <Route path="/" element={<Navigate to="/pagos" replace />} />
          <Route path="/pagos" element={<PaymentsPage />} />
          <Route path="/pagos/:docEntry" element={<VendorPaymentDetailPage />} />
          <Route path="/extractos" element={<StatementsPage PageTitle={PageTitle} StatusBadge={StatusBadge} dateTime={dateTime} />} />
          <Route path="/configuracion" element={<ConfigurationPage />} />
          <Route path="/logs" element={<LogsPage />} />
          <Route path="*" element={<Navigate to="/pagos" replace />} />
        </Routes>
      </div>
    </div>
  )
}

function PageTitle({ eyebrow, title, description, actions }) {
  return <div className="page-title"><div><small>{eyebrow}</small><h1>{title}</h1><p>{description}</p></div><div className="page-actions">{actions}</div></div>
}

function Metric({ label, value, icon: Icon, tone = 'blue' }) {
  return <div className={`metric ${tone}`}><div><span>{label}</span><strong>{value}</strong></div><Icon size={21} /></div>
}

function StatusBadge({ status }) {
  const tone = status === 'COMPLETED' || status === 'AUTHORIZED' ? 'success' : status === 'FAILED' || status === 'REJECTED' ? 'danger' : 'warning'
  return <span className={`badge ${tone}`}><span />{statusLabels[status] ?? status}</span>
}

function PaymentsPage() {
  const navigate = useNavigate()
  const [query, setQuery] = useSearchParams()
  const defaultPaymentDateTo = toDateInput(new Date())
  const initialFrom = query.get('from') || query.get('to') || defaultPaymentDateTo
  const initialTo = query.get('to') || query.get('from') || defaultPaymentDateTo
  const paymentRequest = useRef(0)
  const initialDepartment = query.get('department') || ''
  const initialBank = query.get('bank') || ''
  const initialSourceAccount = query.get('sourceAccount') || '11010501'
  const initialPage = Math.max(0, Number.parseInt(query.get('page') || '0', 10) || 0)
  const initialSize = [10, 20, 50].includes(Number(query.get('size'))) ? Number(query.get('size')) : 20
  const [selectedPayments, setSelectedPayments] = useState([])
  const [previewOpen, setPreviewOpen] = useState(false)
  const [bankBusy, setBankBusy] = useState(false)
  const [bankHistory, setBankHistory] = useState([])
  const [historyError, setHistoryError] = useState('')
  const [bankNotice, setBankNotice] = useState('')
  const [search, setSearch] = useState('')
  const [dateFrom, setDateFrom] = useState(initialFrom)
  const [dateTo, setDateTo] = useState(initialTo)
  const [department, setDepartment] = useState(initialDepartment)
  const [departments, setDepartments] = useState([])
  const [bank, setBank] = useState(initialBank)
  const [banks, setBanks] = useState([])
  const [sourceAccount, setSourceAccount] = useState(initialSourceAccount)
  const [sourceAccounts, setSourceAccounts] = useState([])
  const [catalogError, setCatalogError] = useState('')
  const [appliedRange, setAppliedRange] = useState({ from: initialFrom, to: initialTo, department: initialDepartment, bank: initialBank, sourceAccount: initialSourceAccount })
  const [pageSize, setPageSize] = useState(initialSize)
  const [pagination, setPagination] = useState({ page: initialPage, size: initialSize, totalElements: 0, totalPages: 0, hasPrevious: false, hasNext: false })
  const [payments, setPayments] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    if (!term) return payments
    return payments.filter((payment) =>
      `${payment.cardName} ${payment.cardCode} ${payment.docNum} ${payment.docEntry} ${payment.reference1 ?? ''}`
        .toLowerCase()
        .includes(term))
  }, [payments, search])
  const selected = selectedPayments.map((payment) => payment.docEntry)
  const chosen = selectedPayments
  const selectedTotal = chosen.reduce((sum, payment) => sum + Number(payment.transferSum ?? 0), 0)
  const total = payments.reduce((sum, payment) => sum + Number(payment.transferSum ?? 0), 0)
  const currency = payments[0]?.currency ?? 'BOB'
  const allVisibleSelected = filtered.length > 0 && filtered.every((payment) => selected.includes(payment.docEntry))

  async function loadPayments(requestedPage, requestedSize, from, to, requestedDepartment = appliedRange.department, requestedBank = appliedRange.bank, requestedSourceAccount = appliedRange.sourceAccount) {
    const requestId = ++paymentRequest.current
    if (!from || !to || from > to) {
      setError('Seleccione un rango de fechas válido.')
      setLoading(false)
      return
    }
    setLoading(true)
    setError('')
    try {
      const paymentData = await api.vendorPayments({ from, to, department: requestedDepartment, bank: requestedBank, sourceAccount: requestedSourceAccount, page: requestedPage, size: requestedSize })
      if (requestId !== paymentRequest.current) return
      setPayments(paymentData.items ?? [])
      setPagination(paymentData)
      setAppliedRange({ from, to, department: requestedDepartment, bank: requestedBank, sourceAccount: requestedSourceAccount })
      setQuery({ from, to, sourceAccount: requestedSourceAccount, ...(requestedDepartment ? { department: requestedDepartment } : {}), ...(requestedBank ? { bank: requestedBank } : {}), page: String(paymentData.page), size: String(paymentData.size) }, { replace: true })
      if (from !== appliedRange.from || to !== appliedRange.to || requestedDepartment !== appliedRange.department || requestedBank !== appliedRange.bank || requestedSourceAccount !== appliedRange.sourceAccount) {
        setSelectedPayments([])
        setPreviewOpen(false)
      }
      setError('')
    } catch (requestError) { if (requestId === paymentRequest.current) setError(requestError.message) }
    finally { if (requestId === paymentRequest.current) setLoading(false) }
  }

  useEffect(() => {
    loadPayments(initialPage, initialSize, initialFrom, initialTo, initialDepartment, initialBank, initialSourceAccount)
    refreshBankHistory()
    api.diffusionCatalogs().then((catalogs) => { setDepartments(catalogs.regions ?? []); setBanks(catalogs.banks ?? []); setSourceAccounts(catalogs.sourceAccounts ?? []) })
      .catch((requestError) => setCatalogError(requestError.message))
    return () => { paymentRequest.current++ }
  }, [])

  function submitRange(event) {
    event.preventDefault()
    loadPayments(0, pageSize, dateFrom, dateTo, department, bank, sourceAccount)
  }

  function changePage(nextPage) {
    loadPayments(nextPage, pageSize, appliedRange.from, appliedRange.to)
  }

  function changePageSize(event) {
    const nextSize = Number(event.target.value)
    setPageSize(nextSize)
    loadPayments(0, nextSize, appliedRange.from, appliedRange.to)
  }

  function toggle(docEntry) {
    if (!selected.includes(docEntry) && selected.length >= 100) {
      setError('Puede incluir hasta 100 pagos por lote.')
      return
    }
    setPreviewOpen(false)
    setSelectedPayments((current) => current.some((payment) => payment.docEntry === docEntry)
      ? current.filter((payment) => payment.docEntry !== docEntry)
      : [...current, payments.find((payment) => payment.docEntry === docEntry)])
  }

  function toggleVisible(checked) {
    const visibleIds = filtered.map((payment) => payment.docEntry)
    const next = checked
      ? [...selectedPayments, ...filtered.filter((payment) => !selected.includes(payment.docEntry))]
      : selectedPayments.filter((payment) => !visibleIds.includes(payment.docEntry))
    if (next.length > 100) { setError('Puede incluir hasta 100 pagos por lote.'); return }
    setPreviewOpen(false)
    setSelectedPayments(next)
  }

  function changeBeneficiaryAccount(docEntry, rawIndex) {
    const index = rawIndex === '' ? null : Number(rawIndex)
    const update = (payment) => {
      if (payment.docEntry !== docEntry) return payment
      const account = payment.businessPartner?.bankAccounts?.find((account) => account.index === index)
      return { ...payment, transferAccount: account?.accountNumber ?? null,
        businessPartner: { ...payment.businessPartner, selectedBankAccountIndex: index } }
    }
    setPayments((current) => current.map(update))
    setSelectedPayments((current) => current.map(update))
    setPreviewOpen(false)
  }

  async function afterRelease() {
    setSelectedPayments([]); setPreviewOpen(false)
    setBankNotice('Documentos liberados localmente. El lote sigue en BCP; reenviarlo puede duplicar pagos.')
    await loadPayments(0, pageSize, appliedRange.from, appliedRange.to)
    await refreshBankHistory()
  }

  async function refreshBankHistory() {
    try { setBankHistory(await api.bankPaymentHistory()); setHistoryError('') }
    catch (requestError) { setHistoryError(requestError.message) }
  }

  async function afterBankSend(result) {
    setBankNotice(result.status === 'SENT'
      ? `Lote enviado al banco. Transaccion ${result.bankTransactionId}. Pendiente de autorizacion.`
      : result.message)
    if (result.status !== 'REJECTED') {
      setSelectedPayments([])
      setPreviewOpen(false)
    }
    await loadPayments(0, pageSize, appliedRange.from, appliedRange.to)
    await refreshBankHistory()
  }

  return (
    <main className="page">
      <PageTitle eyebrow="TESORERÍA / SAP" title="Pagos múltiples" description="Pagos a proveedores PBL por rango de fecha, ordenados desde el más reciente." actions={<button className="button secondary" onClick={() => loadPayments(pagination.page, pageSize, appliedRange.from, appliedRange.to)} disabled={loading}>{loading ? <LoaderCircle size={17} className="spin" /> : <RefreshCw size={17} />} Actualizar SAP</button>} />
      <div className="stage-banner"><Activity size={17} /><span>VendorPayments · CardCode comienza con PBL · {dateOnly(appliedRange.from)} al {dateOnly(appliedRange.to)} · {appliedRange.department ? departments.find((region) => region.code === appliedRange.department)?.name ?? appliedRange.department : 'Todos los departamentos'} · {appliedRange.bank ? banks.find((entry) => entry.code === appliedRange.bank)?.name ?? appliedRange.bank : 'Todos los bancos'}.</span></div>
      {error && <div className="alert error"><AlertCircle size={18} /><span>{error}</span></div>}
      {catalogError && <div className="alert error"><AlertCircle size={18} /><span>{catalogError}</span></div>}
      {bankNotice && <div className="alert warning" role="status"><span>{bankNotice}</span><button className="icon-button" title="Cerrar mensaje" onClick={() => setBankNotice('')}><X size={16} /></button></div>}

      <fieldset className="diffusion-fields" disabled={bankBusy}>

      <section className="panel payment-filter-panel">
        <form className="payment-filters" onSubmit={submitRange}>
          <label><span>Fecha desde</span><div className="input-icon"><CalendarDays size={17} /><input type="date" value={dateFrom} max={dateTo} onChange={(event) => setDateFrom(event.target.value)} required /></div></label>
          <label><span>Fecha hasta</span><div className="input-icon"><CalendarDays size={17} /><input type="date" value={dateTo} min={dateFrom} max={defaultPaymentDateTo} onChange={(event) => setDateTo(event.target.value)} required /></div></label>
          <label><span>Departamento</span><select aria-label="Departamento" value={department} onChange={(event) => setDepartment(event.target.value)} disabled={loading || !departments.length}><option value="">Todos los departamentos</option>{departments.map((region) => <option key={region.code} value={region.code}>{region.name}</option>)}</select></label>
          <label><span>Banco</span><select aria-label="Banco" value={bank} onChange={(event) => setBank(event.target.value)} disabled={loading || !banks.length}><option value="">Todos los bancos</option>{banks.map((entry) => <option key={entry.code} value={entry.code}>{entry.name}</option>)}</select></label>
          <label><span>Cuenta de origen</span><select aria-label="Cuenta de origen" value={sourceAccount} onChange={(event) => setSourceAccount(event.target.value)} disabled={loading || !sourceAccounts.length} required>{sourceAccounts.map((entry) => <option key={entry.sapAccount} value={entry.sapAccount}>{entry.name} · {entry.sapAccount}</option>)}</select></label>
          <button className="button primary" disabled={loading}>{loading ? <LoaderCircle size={17} className="spin" /> : <Search size={17} />} Consultar pagos</button>
        </form>
      </section>

      <section className="metrics-grid four">
        <Metric label="Total encontrados" value={pagination.totalElements} icon={Users} />
        <Metric label="Monto de esta página" value={money(total, currency)} icon={CircleDollarSign} tone="green" />
        <Metric label="Último documento" value={payments[0]?.docNum ?? '—'} icon={FileClock} tone="orange" />
        <Metric label="Página" value={pagination.totalPages ? `${pagination.page + 1} de ${pagination.totalPages}` : '0 de 0'} icon={CalendarDays} tone="gray" />
      </section>

      <section className="panel">
        <div className="panel-header"><div><h2>Pagos de proveedores</h2><p>{pagination.totalElements} registros dentro del rango seleccionado.</p></div><div className="search-box"><Search size={17} /><input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Buscar dentro de esta página" /></div></div>
        {loading ? <div className="panel-loading"><LoaderCircle size={22} className="spin" /><span>Consultando VendorPayments</span></div> : payments.length === 0 ? <EmptyState icon={WalletCards} title="Sin pagos PBL" text="No hay pagos que coincidan con los filtros seleccionados." /> : <div className="table-scroll">
          <table>
            <thead><tr><th className="check-cell"><input type="checkbox" checked={allVisibleSelected} onChange={(e) => toggleVisible(e.target.checked)} aria-label="Seleccionar pagos visibles" /></th><th>Proveedor</th><th>Documento SAP</th><th>CardCode</th><th>Fecha</th><th>Cuenta beneficiaria</th><th className="amount">Monto</th><th>Estado SAP</th><th /></tr></thead>
            <tbody>{filtered.map((payment) => <tr key={payment.docEntry} className={selected.includes(payment.docEntry) ? 'selected-row' : ''}>
              <td className="check-cell"><input type="checkbox" checked={selected.includes(payment.docEntry)} onChange={() => toggle(payment.docEntry)} aria-label={`Seleccionar pago ${payment.docNum}`} /></td>
              <td><strong>{payment.cardName}</strong><small>{payment.journalRemarks || 'Sin comentario'}</small></td>
              <td><strong>{payment.docNum}</strong><small>DocEntry {payment.docEntry}</small></td><td className="mono">{payment.cardCode}</td><td>{dateOnly(payment.docDate)}</td>
              <td><BeneficiaryAccount payment={payment} onChange={(value) => changeBeneficiaryAccount(payment.docEntry, value)} /></td>
              <td className="amount"><strong>{money(payment.transferSum, payment.currency)}</strong></td><td><SapPaymentStatus payment={payment} /></td>
              <td><button className="icon-button table-action" title="Ver pago" onClick={() => navigate(`/pagos/${payment.docEntry}`, { state: { returnTo: `/pagos?${query}` } })}><ChevronRight size={18} /></button></td>
            </tr>)}</tbody>
          </table>
        </div>}
        {!loading && (payments.length > 0 || selected.length > 0) && <div className="selection-bar">
          <span><b>{selected.length}</b> pagos seleccionados</span><strong>{money(selectedTotal, chosen[0]?.currency ?? currency)}</strong>
          <button className="icon-button" title="Limpiar selección" disabled={!selected.length} onClick={() => { setSelectedPayments([]); setPreviewOpen(false) }}><X size={17} /></button>
          <button className="button primary" disabled={!selected.length} onClick={() => setPreviewOpen(true)}><Eye size={17} /> Previsualizar difusión</button>
        </div>}
        {!loading && <div className="pagination"><span>{pagination.totalElements === 0 ? 'Sin registros' : `Mostrando ${pagination.page * pagination.size + 1}-${Math.min((pagination.page + 1) * pagination.size, pagination.totalElements)} de ${pagination.totalElements}`}</span><div className="pagination-controls"><button className="icon-button" type="button" title="Primera página" disabled={!pagination.hasPrevious} onClick={() => changePage(0)}><ChevronsLeft size={17} /></button><button className="icon-button" type="button" title="Página anterior" disabled={!pagination.hasPrevious} onClick={() => changePage(pagination.page - 1)}><ChevronLeft size={17} /></button><strong>{pagination.totalPages ? pagination.page + 1 : 0} / {pagination.totalPages}</strong><button className="icon-button" type="button" title="Página siguiente" disabled={!pagination.hasNext} onClick={() => changePage(pagination.page + 1)}><ChevronRight size={17} /></button><button className="icon-button" type="button" title="Última página" disabled={!pagination.hasNext} onClick={() => changePage(pagination.totalPages - 1)}><ChevronsRight size={17} /></button></div><label className="page-size"><span>Filas</span><select value={pageSize} onChange={changePageSize} disabled={loading}><option value="10">10</option><option value="20">20</option><option value="50">50</option></select></label></div>}
      </section>
      </fieldset>
      {previewOpen && <DiffusionPreview key={chosen.map((payment) => `${payment.docEntry}:${payment.businessPartner?.selectedBankAccountIndex}`).join(',')} selectedPayments={chosen} sourceAccount={appliedRange.sourceAccount} onClose={() => setPreviewOpen(false)} onSent={afterBankSend} onBusy={setBankBusy} />}
      <BankSubmissionHistory history={bankHistory} error={historyError} busy={bankBusy} sourceAccounts={sourceAccounts} refresh={refreshBankHistory} onReleased={afterRelease} onBusy={setBankBusy} dateTime={dateTime} money={money} StatusBadge={StatusBadge} />
    </main>
  )
}

function BeneficiaryAccount({ payment, onChange }) {
  const partner = payment.businessPartner
  const accounts = partner?.bankAccounts ?? []
  const account = accounts.find((account) => account.index === partner.selectedBankAccountIndex)
  if (!accounts.length) return <><span>Sin cuenta SAP</span><small>BPBankAccounts vacío</small></>
  return <div className="beneficiary-account">
    {accounts.length > 1 ? <select aria-label={`Cuenta beneficiaria del pago ${payment.docNum}`} value={partner.selectedBankAccountIndex ?? ''} onChange={(event) => onChange(event.target.value)}><option value="">Seleccionar cuenta</option>{accounts.map((account) => <option key={account.index} value={account.index}>{account.accountNumber} · {account.bankName || account.bankCode}</option>)}</select> : <span className="mono">{account?.accountNumber || '—'}</span>}
    <small>{account ? `${account.bankName || account.bankCode} · ${partner.region?.name || 'Sin departamento'}` : 'Varias cuentas: confirme una'}</small>
  </div>
}

function SapPaymentStatus({ payment }) {
  if (payment.cancelled === 'tYES') return <span className="badge danger"><span />Cancelado</span>
  const labels = {
    pasWithout: 'Sin autorización requerida',
    pasPending: 'Pendiente de autorización',
    pasApproved: 'Autorizado en SAP',
    pasRejected: 'Rechazado en SAP',
  }
  const danger = payment.authorizationStatus === 'pasRejected'
  return <span className={`badge ${danger ? 'danger' : 'warning'}`}><span />{labels[payment.authorizationStatus] ?? payment.authorizationStatus ?? 'Registrado'}</span>
}

function VendorPaymentDetailPage() {
  const { docEntry } = useParams()
  const [payment, setPayment] = useState(null)
  const [error, setError] = useState('')
  useEffect(() => { api.vendorPayment(docEntry).then(setPayment).catch((requestError) => setError(requestError.message)) }, [docEntry])
  if (error) return <main className="page"><div className="alert error"><AlertCircle size={18} />{error}</div><Link className="button secondary" to="/pagos"><ArrowLeft size={17} /> Volver</Link></main>
  if (!payment) return <FullScreenLoader label="Consultando pago en SAP" />
  const partner = payment.businessPartner
  return <main className="page payment-detail"><PageTitle eyebrow="PAGOS MÚLTIPLES / SAP" title={`Pago ${payment.docNum}`} description={`DocEntry ${payment.docEntry} · ${payment.cardCode}`} actions={<Link className="button secondary" to="/pagos"><ArrowLeft size={17} /> Volver</Link>} /><section className="metrics-grid four"><Metric label="Monto transferido" value={money(payment.transferSum, payment.currency)} icon={CircleDollarSign} tone="green" /><Metric label="Fecha contable" value={dateOnly(payment.docDate)} icon={CalendarDays} /><Metric label="Facturas aplicadas" value={payment.paymentInvoices.length} icon={FileText} tone="orange" /><Metric label="Moneda SAP" value={payment.sapCurrency || '—'} icon={Banknote} tone="gray" /></section><section className="panel"><div className="panel-header"><div><h2>Datos del pago</h2><p>Información obtenida de VendorPayments({payment.docEntry}).</p></div><SapPaymentStatus payment={payment} /></div><div className="detail-grid"><DetailValue label="Proveedor" value={payment.cardName} /><DetailValue label="CardCode" value={payment.cardCode} mono /><DetailValue label="Documento SAP" value={payment.docNum} /><DetailValue label="Tipo" value={payment.docType} /><DetailValue label="Fecha de vencimiento" value={dateOnly(payment.dueDate)} /><DetailValue label="Fecha de transferencia" value={dateOnly(payment.transferDate)} /><DetailValue label="Cuenta de transferencia" value={payment.transferAccount} mono /><DetailValue label="Referencia" value={payment.reference1} mono /><DetailValue label="Sucursal" value={payment.branchName} /><DetailValue label="Comentario" value={payment.journalRemarks} wide /></div></section><section className="panel"><div className="panel-header"><div><h2>Socio de negocio relacionado</h2><p>Relación realizada con el CardCode del pago.</p></div></div><div className={`partner-match ${partner?.found ? 'found' : 'missing'}`}>{partner?.found ? <ShieldCheck size={22} /> : <AlertCircle size={22} />}<div><strong>{partner?.found ? 'Business Partner encontrado' : 'Business Partner no encontrado'}</strong><span>{partner?.found ? `${partner.cardCode} · ${partner.cardName}` : payment.cardCode}</span><code>{partner?.resource}</code></div></div></section><section className="panel"><div className="panel-header"><div><h2>Facturas aplicadas</h2><p>Detalle incluido en PaymentInvoices.</p></div></div>{payment.paymentInvoices.length === 0 ? <EmptyState icon={FileText} title="Sin facturas aplicadas" text="Este pago no contiene líneas en PaymentInvoices." /> : <div className="table-scroll"><table><thead><tr><th>Línea</th><th>Documento</th><th>Proveedor</th><th>NIT</th><th>Cuenta</th><th>Tipo</th><th className="amount">Monto aplicado</th></tr></thead><tbody>{payment.paymentInvoices.map((invoice) => <tr key={`${invoice.lineNum}-${invoice.docEntry}`}><td>{invoice.lineNum + 1}</td><td><strong>{invoice.documentNumber || invoice.docEntry}</strong><small>DocEntry {invoice.docEntry}</small></td><td>{invoice.businessName || payment.cardName}</td><td className="mono">{invoice.taxId || '—'}</td><td className="mono">{invoice.accountNumber || '—'}</td><td className="mono">{invoice.invoiceType}</td><td className="amount"><strong>{money(invoice.sumApplied, payment.currency)}</strong></td></tr>)}</tbody></table></div>}</section></main>
}

function DetailValue({ label, value, mono = false, wide = false }) {
  return <div className={`detail-value ${wide ? 'wide' : ''}`}><span>{label}</span><strong className={mono ? 'mono' : ''}>{value || '—'}</strong></div>
}

function ConfigurationPage() {
  return <main className="page">
    <PageTitle eyebrow="PORTAL" title="Configuración" />
    <section className="panel">
      <div className="panel-header"><h2>Historial de versiones</h2><Settings size={19} /></div>
      <div className="release-body">
        <div className="release-heading"><strong className="release-version">Versión {portalRelease.version}</strong><time className="subdued" dateTime={portalRelease.date}>{dateOnly(portalRelease.date)}</time></div>
        <p className="release-description">{portalRelease.description}</p>
      </div>
    </section>
  </main>
}

function LogsPage() {
  const [process, setProcess] = useState('MULTIPLE_PAYMENT')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState({ content: [], totalPages: 0 })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    setLoading(true); setError(''); setResult({ content: [], totalPages: 0 })
    api.audit(process, page).then((data) => { if (active) setResult(data) })
      .catch((error) => { if (active) setError(error.message) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [process, page, revision])
  return <main className="page">
    <PageTitle eyebrow="AUDITORÍA" title="Logs de Actividades" actions={<button className="button secondary" disabled={loading} onClick={() => setRevision((value) => value + 1)}><RefreshCw size={17} /> Actualizar</button>} />
    <div className="segmented-control" aria-label="Tipo de actividad">
      {[['MULTIPLE_PAYMENT', 'Pagos múltiples'], ['BANK_STATEMENT', 'Extractos BCP']].map(([value, label]) => <button key={value} className={process === value ? 'active' : ''} onClick={() => { setProcess(value); setPage(0) }}>{value === 'MULTIPLE_PAYMENT' ? <WalletCards size={16} /> : <FileText size={16} />}{label}</button>)}
    </div>
    {error && <div className="alert error" role="alert">{error}</div>}
    <AuditTable title={process === 'MULTIPLE_PAYMENT' ? 'Actividad de pagos' : 'Actividad de extractos'} events={result.content ?? []} loading={loading} />
    <div className="pagination"><span>{result.totalElements ?? 0} eventos</span><div className="pagination-controls"><button className="icon-button" title="Página anterior de logs" disabled={loading || page === 0} onClick={() => setPage((value) => value - 1)}><ChevronLeft size={17} /></button><strong>{result.totalPages ? `${page + 1} / ${result.totalPages}` : '0 / 0'}</strong><button className="icon-button" title="Página siguiente de logs" disabled={loading || page + 1 >= result.totalPages} onClick={() => setPage((value) => value + 1)}><ChevronRight size={17} /></button></div></div>
  </main>
}

function AuditTable({ title, events, loading }) {
  return <section className="panel"><div className="panel-header"><div><h2>{title}</h2><p>Registro de auditoría del proceso.</p></div>{loading ? <LoaderCircle size={18} className="spin" /> : <FileClock size={19} />}</div>{!loading && events.length === 0 ? <EmptyState icon={FileClock} title="Sin actividad" text="Los nuevos eventos aparecerán en esta sección." /> : <div className="table-scroll"><table><thead><tr><th>Fecha y hora</th><th>Acción</th><th>Actor</th><th>Origen</th><th>Estado</th><th>Detalle</th><th>Correlación</th></tr></thead><tbody>{events.map((event) => <tr key={event.id}><td>{dateTime(event.occurredAt)}</td><td className="mono">{event.actionName}</td><td>{event.actor}</td><td>{event.triggerType === 'AUTOMATIC' ? 'Automático' : 'Manual'}</td><td><StatusBadge status={event.status} /></td><td>{event.message}</td><td className="mono subdued">{event.correlationId}</td></tr>)}</tbody></table></div>}</section>
}

function EmptyState({ icon: Icon, title, text }) {
  return <div className="empty-state"><div><Icon size={24} /></div><strong>{title}</strong><p>{text}</p></div>
}

export default App
