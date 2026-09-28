import test from 'node:test'
import assert from 'node:assert/strict'
import { PDFDocument } from 'pdf-lib'
import { buildStatementPdf } from './statementPdf.js'

test('creates a multipage PDF from every transaction without banking calls', async () => {
  const transactions = Array.from({ length: 80 }, (_, index) => ({
    Id: index + 1, Date: '20260831', Hour: '22:53:12', HostOperationNumber: `003${index}`,
    Description: 'Pago a proveedor de ejemplo', Gloss: 'Glosa de ejemplo', Channel: 'AGENCIA',
    Location: 'AGENCIA CENTRAL LA PAZ', AgencyBranch: '008001', Amount: 5000,
  }))
  const bytes = await buildStatementPdf({
    Cic: '    00273384', AccountNumber: '2015009988370', AccountType: 'CTE',
    Currency: 'BOLIVIANOS', InitialBalance: 0, EndingBalance: 1687135.88,
    Period: '202609', Transactions: transactions,
  }, { requestedAt: '2026-09-23T10:00:00' })
  assert.equal(Buffer.from(bytes).subarray(0, 5).toString(), '%PDF-')
  const pdf = await PDFDocument.load(bytes)
  assert.ok(pdf.getPageCount() > 1)
})

test('does not generate an extract from a rejection message', async () => {
  await assert.rejects(() => buildStatementPdf(null), /extracto/)
})
