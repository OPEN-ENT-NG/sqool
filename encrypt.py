import os
import hashlib
import pyaes
from pybase64 import b64encode

md4 = '258def5e78a5f18e3477fcfc55104f2e'
CRYPTO_SECRET = 'secret'

secret = CRYPTO_SECRET.encode('utf-8')

salt = os.urandom(16)
iv = os.urandom(16)
key = hashlib.pbkdf2_hmac('sha256', secret, salt, 1000)

encrypter = pyaes.Encrypter(pyaes.AESModeOfOperationCBC(key, iv))
ciphertext = encrypter.feed(md4.encode())
ciphertext += encrypter.feed()

byte_payload = b64encode(salt) + b'$' + b64encode(iv) + b'$' + b64encode(ciphertext)

print(byte_payload.decode())
