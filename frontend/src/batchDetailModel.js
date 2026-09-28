export function batchResults(body) {
  return Array.isArray(body?.Result) ? body.Result.filter((item) => item && typeof item === 'object') : []
}

export function paymentGroups(result) {
  const spreadsheet = result?.Spreadsheet ?? {}
  return [
    ['Pagos BCP', spreadsheet.FormProvidersPayments],
    ['Pagos ACH', spreadsheet.FormAchPayments],
    ['Otros pagos', spreadsheet.FormOddPayments],
  ].filter(([, rows]) => Array.isArray(rows) && rows.length)
}
