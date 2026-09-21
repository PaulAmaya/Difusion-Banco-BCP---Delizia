import test from 'node:test'
import assert from 'node:assert/strict'
import { statementQuery, formatStatementData } from './statementJson.js'

const accounts = [{ accountNumber: '2015009988370' }, { accountNumber: '20150838488388' }]
test('browser request contains only account and period, never credentials', () => {
  assert.deepEqual(statementQuery(accounts[0].accountNumber, '202609', accounts), { accountNumber: '2015009988370', period: '202609' })
})
test('BCP SC is selectable and test or arbitrary accounts are rejected', () => {
  assert.equal(statementQuery(accounts[1].accountNumber, '202506', accounts).accountNumber, '20150838488388')
  for (const account of ['20150735205363', '', '1234', 2015009988370]) assert.throws(() => statementQuery(account, '202609', accounts))
})
test('account numbers retain leading zeros', () => {
  assert.equal(statementQuery('00123', '202601', [{ accountNumber: '00123' }]).accountNumber, '00123')
})
test('invalid calendar months and types are rejected before submission', () => {
  for (const period of ['202600', '202613', '2026-09', '', 202609]) assert.throws(() => statementQuery(accounts[0].accountNumber, period, accounts))
})
test('decrypted JSON and plain rejection messages are readable', () => {
  assert.equal(formatStatementData('{"amount":10}'), '{\n  "amount": 10\n}')
  assert.equal(formatStatementData('Solicitud rechazada'), 'Solicitud rechazada')
  assert.equal(formatStatementData(null), 'No disponible')
})
