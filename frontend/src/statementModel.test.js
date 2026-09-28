import test from 'node:test'
import assert from 'node:assert/strict'
import { parseStatement, statementAmount, statementDate, statementPeriod } from './statementModel.js'

const sample = { AccountNumber: '00123', Period: '202609', Transactions: [{ Id: 1, Amount: 5 }] }

test('parses saved decrypted body without changing account digits', () => {
  assert.deepEqual(parseStatement(JSON.stringify(sample)), sample)
  assert.equal(parseStatement(sample), sample)
})

test('rejects plain bank messages and envelopes without transactions', () => {
  for (const value of [null, '', 'Solicitud rechazada', '{}', '[]', '{"Transactions":null}']) {
    assert.equal(parseStatement(value), null)
  }
})

test('formats BCP dates, periods and numeric amounts', () => {
  assert.equal(statementDate('20260831'), '31/08/2026')
  assert.equal(statementPeriod('202609'), '09/2026')
  assert.equal(statementAmount(1687135.88), '1.687.135,88')
  assert.equal(statementAmount(null), '—')
})
