let csrfHeader = 'X-XSRF-TOKEN'
let csrfToken = null

async function loadCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new Error('No se pudo iniciar la sesión segura')
  const payload = await response.json()
  csrfHeader = payload.headerName
  csrfToken = payload.token
}

async function request(path, options = {}) {
  const method = options.method ?? 'GET'
  const headers = { Accept: 'application/json', ...(options.headers ?? {}) }
  if (options.body) headers['Content-Type'] = 'application/json'
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())) {
    await loadCsrf()
    headers[csrfHeader] = csrfToken
  }

  const response = await fetch(path, { ...options, method, headers, credentials: 'include' })
  const expiresAt = response.headers.get('X-SAP-Expires-At')
  if (response.ok && expiresAt) window.dispatchEvent(new CustomEvent('sap-session-activity', { detail: expiresAt }))
  if (response.status === 204) return null
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/auth/login') {
      window.dispatchEvent(new Event('sap-session-expired'))
    }
    const error = new Error(payload.message ?? 'No fue posible completar la operación')
    error.status = response.status
    error.fields = payload.fields ?? {}
    throw error
  }
  return payload
}

export const api = {
  login: (username, password) => request('/api/auth/login', {
    method: 'POST', body: JSON.stringify({ username, password }),
  }),
  me: () => request('/api/auth/me'),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  statements: () => request('/api/statements'),
  statementConfig: () => request('/api/statements/config'),
  statement: (id) => request(`/api/statements/${encodeURIComponent(id)}`),
  createStatement: (payload) => request('/api/statements', {
    method: 'POST', body: JSON.stringify(payload),
  }),
  vendorPayments: ({ from, to, department = '', bank = '', sourceAccount = '11010501', page = 0, size = 20 }) => {
    const params = new URLSearchParams({ from, to, sourceAccount, page: String(page), size: String(size) })
    if (department) params.set('department', department)
    if (bank) params.set('bank', bank)
    return request(`/api/sap/vendor-payments?${params}`)
  },
  vendorPayment: (docEntry) => request(`/api/sap/vendor-payments/${encodeURIComponent(docEntry)}`),
  diffusionCatalogs: () => request('/api/sap/vendor-payments/diffusion/catalogs'),
  prepareDiffusion: (docEntries) => request('/api/sap/vendor-payments/diffusion/prepare', {
    method: 'POST', body: JSON.stringify({ docEntries }),
  }),
  previewDiffusion: (payments, sourceAccount) => request('/api/sap/vendor-payments/diffusion/preview', {
    method: 'POST', body: JSON.stringify({ payments, sourceAccount }),
  }),
  batches: () => request('/api/payment-batches'),
  bankPaymentConfig: () => request('/api/bank/multiple-payments/config'),
  bankPaymentHistory: () => request('/api/bank/multiple-payments'),
  releaseDiffusion: (id, reason, acknowledgeDuplicateRisk) => request(`/api/bank/multiple-payments/${id}/release`, {
    method: 'POST', body: JSON.stringify({ reason, acknowledgeDuplicateRisk }),
  }),
  sendDiffusion: (selection, fingerprint, requestId) => request('/api/bank/multiple-payments', {
    method: 'POST', body: JSON.stringify({ selection, fingerprint, requestId }),
  }),
  batch: (id) => request(`/api/payment-batches/${id}`),
  createBatch: (payload) => request('/api/payment-batches', {
    method: 'POST', body: JSON.stringify(payload),
  }),
  audit: (processType, page = 0) => request(`/api/audit-logs?size=20&page=${page}${processType ? `&processType=${processType}` : ''}`),
  encrypt: (jsonText, companyId) => request('/api/crypto/encrypt', {
    method: 'POST', body: JSON.stringify({ jsonText, companyId }),
  }),
  decrypt: (encryptedText, signature) => request('/api/crypto/decrypt', {
    method: 'POST', body: JSON.stringify({ encryptedText, signature: signature || null }),
  }),
}
