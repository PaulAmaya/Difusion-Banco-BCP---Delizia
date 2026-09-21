import { useEffect, useRef, useState } from 'react'
import { Clock3, LoaderCircle } from 'lucide-react'
import { api } from './api.js'

export default function SessionGuard({ user, onExpired, children }) {
  const [expired, setExpired] = useState(false)
  const [closing, setClosing] = useState(false)
  const deadline = useRef(Date.parse(user.sapExpiresAt) || Date.now() + 30 * 60 * 1000)
  const checking = useRef(false)

  useEffect(() => {
    let active = true
    function expire() { if (active) setExpired(true) }
    function activity(event) {
      const timestamp = Date.parse(event.detail)
      if (Number.isFinite(timestamp)) deadline.current = Math.max(deadline.current, timestamp)
    }
    async function verify() {
      if (checking.current || expired) return
      checking.current = true
      try {
        const current = await api.me()
        const timestamp = Date.parse(current.sapExpiresAt)
        if (Number.isFinite(timestamp)) deadline.current = Math.max(deadline.current, timestamp)
        if (Date.now() >= deadline.current) expire()
      } catch (error) {
        if (error.status === 401 || Date.now() >= deadline.current) expire()
      } finally { checking.current = false }
    }
    function checkDeadline() { if (Date.now() >= deadline.current) verify() }
    window.addEventListener('sap-session-expired', expire)
    window.addEventListener('sap-session-activity', activity)
    window.addEventListener('focus', verify)
    const timer = window.setInterval(checkDeadline, 1000)
    // Checking the portal session does not query SAP or extend its inactivity timeout.
    const poll = window.setInterval(verify, 60000)
    return () => {
      active = false
      window.clearInterval(timer); window.clearInterval(poll)
      window.removeEventListener('sap-session-expired', expire)
      window.removeEventListener('sap-session-activity', activity)
      window.removeEventListener('focus', verify)
    }
  }, [expired])

  async function accept() {
    setClosing(true)
    try { await api.logout() } catch { /* The backend may already have invalidated this session. */ }
    finally { onExpired() }
  }

  return <>
    <div inert={expired ? true : undefined}>{children}</div>
    {expired && <div className="modal-backdrop session-expired">
      <section className="bank-confirm" role="alertdialog" aria-modal="true" aria-labelledby="session-title" aria-describedby="session-message">
        <Clock3 size={24} />
        <h2 id="session-title">Sesión expirada</h2>
        <p id="session-message">Su sesión SAP ha expirado. Después de 30 minutos sin consultas a SAP debe iniciar sesión nuevamente.</p>
        <div className="diffusion-actions"><button autoFocus className="button primary" disabled={closing} onClick={accept} onKeyDown={(event) => { if (event.key === 'Tab') event.preventDefault() }}>{closing && <LoaderCircle size={16} className="spin" />}Aceptar</button></div>
      </section>
    </div>}
  </>
}
