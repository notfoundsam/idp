const dates = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function dateOf(iso: string): string {
  return dates.format(new Date(iso))
}
