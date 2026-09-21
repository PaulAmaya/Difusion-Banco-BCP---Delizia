export function statementQuery(accountNumber, period, accounts) {
  if (typeof accountNumber !== 'string' || !/^\d{1,40}$/.test(accountNumber)
    || !accounts.some((account) => account.accountNumber === accountNumber)) {
    throw new Error('Seleccione una cuenta BCP disponible.')
  }
  if (typeof period !== 'string' || !/^(19|20)\d{2}(0[1-9]|1[0-2])$/.test(period)) {
    throw new Error('Seleccione un periodo valido.')
  }
  return { accountNumber, period }
}

export function formatStatementData(value) {
  if (value == null || value === '') return 'No disponible'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value || 'No disponible' }
}
