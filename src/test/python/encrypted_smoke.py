from pathlib import Path
import hashlib
import hmac
import struct
import sys
import uuid
from cryptography.hazmat.primitives.asymmetric import x25519, ed25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import serialization

sys.path.insert(0, str(Path(__file__).resolve().parent))
from protocol_smoke import Client, string, varint, read_varint


def take_string(data):
    from io import BytesIO
    body = BytesIO(data)
    size = read_varint(body.read)
    return body.read(size).decode(), body.read()


def pack_uuid(value):
    return value.bytes


def hkdf_key(shared, session, client_nonce, server_nonce, client_public, server_public, identity_public):
    transcript = handshake_transcript(session, client_nonce, server_nonce, client_public, server_public, identity_public)
    prk = hmac.new(client_nonce + server_nonce, shared, hashlib.sha256).digest()
    return hmac.new(prk, transcript, hashlib.sha256).digest()


def handshake_transcript(session, client_nonce, server_nonce, client_public, server_public, identity_public):
    result = b"DialogAuth/1"
    for value in (pack_uuid(session), client_nonce, server_nonce, client_public, server_public, identity_public):
        result += struct.pack(">I", len(value)) + value
    return result


class EncryptedClient(Client):
    def __init__(self, port, name):
        self.private = x25519.X25519PrivateKey.generate()
        self.public = self.private.public_key().public_bytes(
                serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.client_nonce = __import__('os').urandom(32)
        self.aes = None
        self.session = None
        super().__init__(port, name)

    def receive(self):
        packet, data = super().receive()
        if packet != 1:
            return packet, data
        channel, payload = take_string(data)
        if channel == "minecraft:brand":
            pass
        elif channel == "minecraft:register":
            channels = [b"dialogauth:challenge", b"dialogauth:server_hello", b"dialogauth:client_hello", b"dialogauth:response"]
            self.send(2, string("minecraft:register") + b"\x00".join(channels))
        elif channel == "dialogauth:challenge":
            session = uuid.UUID(bytes=payload)
            self.session = session
            response = pack_uuid(session) + varint(len(self.client_nonce)) + self.client_nonce + varint(len(self.public)) + self.public
            self.send(2, string("dialogauth:client_hello") + response)
        elif channel == "dialogauth:server_hello":
            from io import BytesIO
            body = BytesIO(payload)
            session = uuid.UUID(bytes=body.read(16))
            values = []
            for _ in range(5):
                size = read_varint(body.read)
                value = body.read(size)
                assert len(value) == size, "Truncated server hello field"
                values.append(value)
            assert not body.read(), "Trailing server hello data"
            client_nonce, server_nonce, server_public, identity_public, signature = values
            assert session == self.session, "Unexpected authentication session"
            assert client_nonce == self.client_nonce, "Unexpected client nonce"
            transcript = handshake_transcript(session, client_nonce, server_nonce,
                    self.public, server_public, identity_public)
            identity = serialization.load_der_public_key(identity_public)
            assert isinstance(identity, ed25519.Ed25519PublicKey), "Unexpected identity key type"
            identity.verify(signature, transcript)
            server_key = serialization.load_der_public_key(server_public)
            assert isinstance(server_key, x25519.X25519PublicKey), "Unexpected server key type"
            shared = self.private.exchange(server_key)
            self.aes = AESGCM(hkdf_key(shared, session, client_nonce, server_nonce,
                    self.public, server_public, identity_public))
        else:
            raise AssertionError(("Unexpected custom payload", channel))
        return self.receive()

    def submit(self, password, confirmation="", duplicate=False):
        assert self.aes is not None, "No encrypted channel"
        password_bytes = password.encode()
        confirmation_bytes = confirmation.encode()
        plaintext = struct.pack(">H", len(password_bytes)) + password_bytes + struct.pack(">H", len(confirmation_bytes)) + confirmation_bytes
        nonce = __import__('os').urandom(12)
        aad = (str(self.session) + ":response:0").encode()
        ciphertext = self.aes.encrypt(nonce, plaintext, aad)
        response = pack_uuid(self.session) + varint(0) + varint(len(nonce)) + nonce + varint(len(ciphertext)) + ciphertext
        self.send(2, string("dialogauth:response") + response)


if __name__ == "__main__":
    client = EncryptedClient(25575, "TestFriend")
    client.submit("SecureTest-12345")
    packet, data = client.receive()
    assert packet == 3, ("Separated encrypted login did not finish configuration", packet, data)
    client.send(3)
    packet, data = client.receive()
    assert packet == 43, ("Separated encrypted login did not reach play login", packet, data)
    client.close()
    print("PASS: separated server and companion encrypted login")
