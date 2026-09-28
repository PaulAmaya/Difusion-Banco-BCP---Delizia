import { PDFDocument, StandardFonts, rgb } from 'pdf-lib'
import { statementAmount, statementDate, statementPeriod } from './statementModel.js'

const PAGE_WIDTH = 595.28
const PAGE_HEIGHT = 841.89
const MARGIN = 42
const WIDTH = PAGE_WIDTH - MARGIN * 2
const NAVY = rgb(0.06, 0.18, 0.32)
const INK = rgb(0.18, 0.24, 0.31)
const MUTED = rgb(0.38, 0.44, 0.51)
const LINE = rgb(0.82, 0.86, 0.9)
const SOFT = rgb(0.95, 0.97, 0.98)
const COLUMNS = [76, 88, 163, 115, WIDTH - 442]

function safeText(value, font) {
  const source = String(value ?? '').replace(/[\u2010-\u2015]/g, '-').replace(/[\r\n]+/g, ' ')
  return [...source].map((char) => {
    try { font.encodeText(char); return char } catch { return '?' }
  }).join('')
}

function wrap(value, font, size, width) {
  const words = safeText(value, font).split(/\s+/).filter(Boolean)
  if (!words.length) return ['-']
  const lines = []
  let line = ''
  for (const word of words) {
    const candidate = line ? `${line} ${word}` : word
    if (font.widthOfTextAtSize(candidate, size) <= width) { line = candidate; continue }
    if (line) { lines.push(line); line = '' }
    let part = ''
    for (const char of word) {
      if (font.widthOfTextAtSize(part + char, size) > width && part) {
        lines.push(part)
        part = char
      } else part += char
    }
    line = part
  }
  if (line) lines.push(line)
  return lines
}

function drawText(page, value, x, y, font, size = 9, color = INK) {
  page.drawText(safeText(value, font), { x, y, size, font, color })
}

export async function buildStatementPdf(document, response = {}) {
  if (!document || !Array.isArray(document.Transactions)) throw new Error('El extracto no contiene transacciones válidas')
  const pdf = await PDFDocument.create()
  const regular = await pdf.embedFont(StandardFonts.Helvetica)
  const bold = await pdf.embedFont(StandardFonts.HelveticaBold)
  pdf.setTitle(`Extracto BCP ${document.AccountNumber ?? ''} ${document.Period ?? ''}`)
  pdf.setAuthor('Portal de Pagos Delizia')
  const pages = []
  let page
  let y

  function addPage(first = false) {
    page = pdf.addPage([PAGE_WIDTH, PAGE_HEIGHT])
    pages.push(page)
    page.drawRectangle({ x: 0, y: PAGE_HEIGHT - 74, width: PAGE_WIDTH, height: 74, color: NAVY })
    drawText(page, 'EXTRACTO BCP', MARGIN, PAGE_HEIGHT - 38, bold, 18, rgb(1, 1, 1))
    drawText(page, 'Copia generada por el Portal de Pagos Delizia', MARGIN, PAGE_HEIGHT - 56, regular, 9, rgb(1, 1, 1))
    y = PAGE_HEIGHT - 98
    if (!first) {
      drawText(page, `Cuenta ${document.AccountNumber ?? '-'}  |  Periodo ${statementPeriod(document.Period)}`, MARGIN, y, bold, 9)
      y -= 23
    }
  }

  function summaryField(label, value, x, baseline, width) {
    drawText(page, label.toUpperCase(), x, baseline, bold, 7.5, MUTED)
    for (const [index, line] of wrap(value, regular, 10, width).entries()) {
      drawText(page, line, x, baseline - 16 - index * 11, regular, 10)
    }
  }

  function tableHeader() {
    page.drawRectangle({ x: MARGIN, y: y - 22, width: WIDTH, height: 25, color: SOFT })
    let x = MARGIN + 6
    for (const [index, label] of ['FECHA / HORA', 'OPERACION', 'DESCRIPCION / GLOSA', 'CANAL / UBICACION', 'IMPORTE / ID'].entries()) {
      drawText(page, label, x, y - 13, bold, 7, MUTED)
      x += COLUMNS[index]
    }
    y -= 25
  }

  addPage(true)
  summaryField('Cuenta', document.AccountNumber ?? '-', MARGIN, y, 225)
  summaryField('CIC', String(document.Cic ?? '').trim() || '-', MARGIN + 255, y, 225)
  y -= 49
  summaryField('Tipo', document.AccountType ?? '-', MARGIN, y, 225)
  summaryField('Moneda', document.Currency ?? '-', MARGIN + 255, y, 225)
  y -= 49
  summaryField('Periodo', statementPeriod(document.Period), MARGIN, y, 225)
  summaryField('Consulta', String(response.requestedAt ?? '-').replace('T', ' ').slice(0, 16), MARGIN + 255, y, 225)
  y -= 54
  page.drawLine({ start: { x: MARGIN, y }, end: { x: PAGE_WIDTH - MARGIN, y }, thickness: 0.7, color: LINE })
  y -= 21
  summaryField('Saldo inicial', statementAmount(document.InitialBalance), MARGIN, y, 225)
  summaryField('Saldo final', statementAmount(document.EndingBalance), MARGIN + 255, y, 225)
  y -= 56
  drawText(page, `TRANSACCIONES  (${document.Transactions.length})`, MARGIN, y, bold, 11, NAVY)
  y -= 17
  tableHeader()

  if (!document.Transactions.length) {
    drawText(page, 'Sin movimientos en este periodo.', MARGIN + 6, y - 21, regular, 9)
  }

  for (const transaction of document.Transactions) {
    const cells = [
      `${statementDate(transaction?.Date)}\n${transaction?.Hour ?? '-'}`,
      `${transaction?.HostOperationNumber ?? '-'}\nAgencia: ${transaction?.AgencyBranch ?? '-'}`,
      `Descripcion: ${transaction?.Description ?? '-'}\nGlosa: ${transaction?.Gloss ?? '-'}`,
      `${transaction?.Channel ?? '-'}\n${transaction?.Location ?? '-'}`,
      `${statementAmount(transaction?.Amount)}\nID: ${transaction?.Id ?? '-'}`,
    ]
    const wrapped = cells.map((cell, index) => cell.split('\n').flatMap((part) =>
      wrap(part, regular, 7.6, COLUMNS[index] - 12)))
    const rowHeight = Math.max(37, Math.max(...wrapped.map((lines) => lines.length)) * 10 + 11)
    if (y - rowHeight < 73) { addPage(); tableHeader() }
    page.drawLine({ start: { x: MARGIN, y }, end: { x: PAGE_WIDTH - MARGIN, y }, thickness: 0.5, color: LINE })
    let x = MARGIN + 6
    wrapped.forEach((lines, index) => {
      lines.forEach((line, lineIndex) => drawText(page, line, x, y - 12 - lineIndex * 10, regular, 7.6))
      x += COLUMNS[index]
    })
    y -= rowHeight
  }

  for (const [index, sheet] of pages.entries()) {
    sheet.drawLine({ start: { x: MARGIN, y: 53 }, end: { x: PAGE_WIDTH - MARGIN, y: 53 }, thickness: 0.6, color: LINE })
    drawText(sheet, 'Copia del portal. No sustituye un documento bancario oficial.', MARGIN, 36, regular, 8, MUTED)
    drawText(sheet, `Pagina ${index + 1} de ${pages.length}`, PAGE_WIDTH - MARGIN - 68, 36, regular, 8, MUTED)
  }
  return pdf.save()
}
