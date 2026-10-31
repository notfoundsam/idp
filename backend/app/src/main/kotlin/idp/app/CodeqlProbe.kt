package idp.app

import javax.crypto.Cipher

class CodeqlProbe {
    fun weakCipher(): Cipher = Cipher.getInstance("DES")
}
