export function parseStatement(value) {
  let data = value
  if (typeof data === 'string') {
    try { data = JSON.parse(data) } catch { return null }
  }
  if (!data || typeof data !== 'object' || Array.isArray(data) || !Array.isArray(data.Transactions)) return null
  return data
}

export function statementDate(value) {
  const text = String(value ?? '')
  if (!/^\d{8}$/.test(text)) return text || '—'
  return `${text.slice(6, 8)}/${text.slice(4, 6)}/${text.slice(0, 4)}`
}

export function statementPeriod(value) {
  const text = String(value ?? '')
  if (!/^\d{6}$/.test(text)) return text || '—'
  return `${text.slice(4, 6)}/${text.slice(0, 4)}`
}

export function statementAmount(value) {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return '—'
  return new Intl.NumberFormat('es-BO', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(value))
}

export const transactionLabels = Object.freeze({
  Id: 'ID', Date: 'Fecha', Hour: 'Hora', HostOperationNumber: 'Nro. de operación',
  Description: 'Descripción', Channel: 'Canal', Gloss: 'Glosa', Location: 'Ubicación',
  Amount: 'Importe', AgencyBranch: 'Código de agencia',
})
