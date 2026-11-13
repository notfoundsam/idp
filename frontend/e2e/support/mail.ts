import type { APIRequestContext } from '@playwright/test'

const MAILPIT = 'https://mail.idp.test'

interface Summary {
  ID: string
}

interface Wanted {
  to: string
  subject: string
  path: string
  /**
   * Mails already there before the action, from `mailIds`, which are left out so a link spent earlier is not taken. IDs and not
   * times, because Mailpit stamps a mail with the clock of the Docker VM, which may lag or lead the clock of the test.
   */
  skip?: readonly string[]
}

async function search(request: APIRequestContext, to: string, subject: string): Promise<Summary[]> {
  const query = encodeURIComponent(`to:"${to}" subject:"${subject}"`)
  return ((await (await request.get(`${MAILPIT}/api/v1/search?query=${query}`)).json()) as { messages: Summary[] }).messages
}

/** The mails that match now, taken before the action whose mail a test waits for. */
export async function mailIds(request: APIRequestContext, to: string, subject: string): Promise<string[]> {
  return (await search(request, to, subject)).map((it) => it.ID)
}

/** Waits for the newest mail that matches and returns its link to [path], token included. */
export async function linkFromMail(request: APIRequestContext, { to, subject, path, skip = [] }: Wanted): Promise<string> {
  const link = new RegExp(`https://[a-z0-9.-]+${path}#token=[A-Za-z0-9_-]+`)
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    const mail = (await search(request, to, subject)).find((it) => !skip.includes(it.ID))
    if (mail !== undefined) {
      const message = (await (await request.get(`${MAILPIT}/api/v1/message/${mail.ID}`)).json()) as { Text: string }
      const match = link.exec(message.Text)
      if (match === null) throw new Error(`The mail "${subject}" to ${to} has no link to ${path}`)
      return match[0]
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`No new mail "${subject}" reached ${to}`)
}
