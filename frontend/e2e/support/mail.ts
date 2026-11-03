import type { APIRequestContext } from '@playwright/test'

const MAILPIT = 'https://mail.idp.test'

interface Summary {
  ID: string
  Created: string
}

interface Wanted {
  to: string
  subject: string
  path: string
  /** Mails from before this moment are left out, so a link spent earlier is not taken. */
  since?: number
}

/** Waits for the newest mail that matches and returns its link to [path], token included. */
export async function linkFromMail(request: APIRequestContext, { to, subject, path, since = 0 }: Wanted): Promise<string> {
  const query = encodeURIComponent(`to:"${to}" subject:"${subject}"`)
  const link = new RegExp(`https://[a-z0-9.-]+${path}#token=[A-Za-z0-9_-]+`)
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    const found = (await (await request.get(`${MAILPIT}/api/v1/search?query=${query}`)).json()) as { messages: Summary[] }
    const mail = found.messages.find((it) => Date.parse(it.Created) >= since)
    if (mail !== undefined) {
      const message = (await (await request.get(`${MAILPIT}/api/v1/message/${mail.ID}`)).json()) as { Text: string }
      const match = link.exec(message.Text)
      if (match === null) throw new Error(`The mail "${subject}" to ${to} has no link to ${path}`)
      return match[0]
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`No mail "${subject}" reached ${to}`)
}
