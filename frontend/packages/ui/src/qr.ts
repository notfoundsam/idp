import { encode } from 'uqr'

/** The standard asks for four light modules around the code, which scanners need to find it. */
const QUIET_ZONE = 4

export interface QrModules {
  /** Modules per side, quiet zone included. */
  size: number
  /** One square per dark module, as the `d` of an SVG path in module units. */
  path: string
}

/** Drawn as SVG elements, so the page needs no image source beyond what the CSP allows. */
export function qrModules(text: string): QrModules {
  const { size, data } = encode(text, { ecc: 'M', border: QUIET_ZONE })
  const squares = data.flatMap((row, y) => row.flatMap((dark, x) => (dark ? [`M${x} ${y}h1v1h-1z`] : [])))
  return { size, path: squares.join('') }
}
