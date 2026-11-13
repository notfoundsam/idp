import { describe, expect, test } from 'vitest'
import { otpauthUri } from './otpauth'
import { qrModules } from './qr'

const squaresOf = (path: string) =>
  [...path.matchAll(/M(\d+) (\d+)h1v1h-1z/g)].map(([, x, y]) => ({ x: Number(x), y: Number(y) }))

describe('the QR code', () => {
  const uri = otpauthUri('JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', 'acme.idp.test', 'someone.with.a.long.name@acme.test')
  const { size, path } = qrModules(uri)
  const squares = squaresOf(path)

  test('is a code of a standard version with four light modules around it', () => {
    const version = (size - 8 - 17) / 4
    expect(Number.isInteger(version)).toBe(true)
    expect(version).toBeGreaterThan(1)
    expect(squares.length).toBeGreaterThan(0)
    for (const { x, y } of squares) {
      expect(Math.min(x, y)).toBeGreaterThanOrEqual(4)
      expect(Math.max(x, y)).toBeLessThan(size - 4)
    }
  })

  test('draws only whole squares, each module once', () => {
    expect(path).toBe(squares.map(({ x, y }) => `M${x} ${y}h1v1h-1z`).join(''))
    expect(new Set(squares.map(({ x, y }) => `${x},${y}`)).size).toBe(squares.length)
  })

  test('has the finder patterns in three corners, a dark ring around a light one around a dark centre', () => {
    const dark = new Set(squares.map(({ x, y }) => `${x},${y}`))
    const last = size - 5
    for (const [left, top] of [
      [4, 4],
      [last - 6, 4],
      [4, last - 6],
    ] as const) {
      expect(dark.has(`${left},${top}`)).toBe(true)
      expect(dark.has(`${left + 6},${top + 6}`)).toBe(true)
      expect(dark.has(`${left + 1},${top + 1}`)).toBe(false)
      expect(dark.has(`${left + 3},${top + 3}`)).toBe(true)
    }
  })

  test('differs for another secret and stays the same for the same one', () => {
    expect(qrModules(uri).path).toBe(path)
    expect(qrModules(otpauthUri('KRSXG5CTMVRXEZLU', 'acme.idp.test', null)).path).not.toBe(path)
  })
})
