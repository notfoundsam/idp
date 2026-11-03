/** The limits of `PasswordPolicy` in the backend, which counts code points. */
export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 128
export const PASSWORD_RECOMMENDED_LENGTH = 15

export const PASSWORD_RULES =
  `Use ${PASSWORD_MIN_LENGTH} to ${PASSWORD_MAX_LENGTH} characters, ${PASSWORD_RECOMMENDED_LENGTH} or more is recommended: ` +
  'a few random words are long and easy to remember. A common password, or one that contains your name, your email address or ' +
  'the name of this service, is refused.'
