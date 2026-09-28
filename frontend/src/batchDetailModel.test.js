import assert from 'node:assert/strict'
import test from 'node:test'
import { batchResults, paymentGroups } from './batchDetailModel.js'

test('reads bank batch results and ignores malformed entries', () => {
  assert.deepEqual(batchResults({ Result: [{ ProcessBatchId: 5 }, null, 'bad'] }), [{ ProcessBatchId: 5 }])
  assert.deepEqual(batchResults({ Message: 'Rejected' }), [])
})

test('shows only payment groups present in the decrypted response', () => {
  assert.deepEqual(paymentGroups({ Spreadsheet: {
    FormProvidersPayments: [{ Line: 1 }], FormAchPayments: [],
  } }), [['Pagos BCP', [{ Line: 1 }]]])
})
