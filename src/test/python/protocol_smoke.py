import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import uuid
import zipfile


def varint(value):
    result = bytearray()
    while True:
        byte = value & 127
        value >>= 7
        result.append(byte | (128 if value else 0))
        if not value:
            return bytes(result)


def string(value):
    data = value.encode("utf-8")
    return varint(len(data)) + data


def read_varint(read):
    result = 0
    for shift in range(0, 35, 7):
        raw = read(1)
        if not raw:
            raise EOFError("Connection closed")
        byte = raw[0]
        result |= (byte & 127) << shift
        if not byte & 128:
            return result
    raise ValueError("Invalid varint")


class Client:
    def __init__(self, port, name, expect_disconnect=False):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=10)
        self.socket.settimeout(15)
        self.dialog = None
        self.disconnection = None
        self.send(0, varint(771) + string("localhost") + struct.pack(">H", port) + varint(2))
        self.send(0, string(name) + uuid.uuid4().bytes)
        packet, data = self.receive()
        assert packet == 2, ("Expected login success", packet, data)
        self.send(3)
        while True:
            packet, data = self.receive()
            if packet == 14:
                self.send(7, varint(0))
            elif packet == 18:
                self.dialog = data
                break
            elif packet == 2:
                self.disconnection = data
                if expect_disconnect:
                    break
                raise AssertionError(("Disconnected before dialog", data))
        if expect_disconnect:
            assert self.disconnection is not None, "Expected disconnect before dialog"

    def send(self, packet, data=b""):
        data = varint(packet) + data
        self.socket.sendall(varint(len(data)) + data)

    def exact(self, length):
        result = bytearray()
        while len(result) < length:
            data = self.socket.recv(length - len(result))
            if not data:
                raise EOFError("Connection closed")
            result.extend(data)
        return bytes(result)

    def receive(self):
        from io import BytesIO
        size = read_varint(self.exact)
        body = BytesIO(self.exact(size))
        packet = read_varint(body.read)
        data = body.read()
        if packet == 4:
            self.send(4, data)
            return self.receive()
        if packet == 5:
            self.send(5, data)
            return self.receive()
        return packet, data

    def submit(self, password, confirmation="", duplicate=False):
        fields = {"password": password, "password_confirm": confirmation}
        nbt = b"\x0a"
        for name, value in fields.items():
            key = name.encode()
            raw = value.encode()
            nbt += b"\x08" + struct.pack(">H", len(key)) + key + struct.pack(">H", len(raw)) + raw
        nbt += b"\x00"
        payload = string("dialogauth:submit") + varint(len(nbt)) + nbt
        self.send(8, payload)
        if duplicate:
            self.send(8, payload)

    def close(self):
        self.socket.close()


ONLINE_UUID = uuid.UUID("12345678-1234-4321-8765-123456789abc")
ONLINE_SALT = b"0123456789abcdef"
ONLINE_PASSWORD = "onlinePassword123"
ONLINE_HASH = hashlib.pbkdf2_hmac("sha256", ONLINE_PASSWORD.encode(), ONLINE_SALT, 210000)


def offline_uuid(name):
    raw = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    raw[6] = raw[6] & 15 | 48
    raw[8] = raw[8] & 63 | 128
    return uuid.UUID(bytes=bytes(raw))


def finish_login(client, label, duplicate=False, confirmation=""):
    client.submit(ONLINE_PASSWORD if label == "Migration" else "testPassword123",
                  confirmation, duplicate=duplicate)
    packet, data = client.receive()
    assert packet == 3, (label + " did not finish configuration", packet, data)
    client.send(3)
    packet, data = client.receive()
    assert packet == 43, (label + " did not reach play login", packet, data)
    client.close()


def blocked_scenario(port, directory):
    blocked = Client(port, "OfflineBlocked", expect_disconnect=True)
    assert "allow-offline-mode=true".encode() in blocked.disconnection
    blocked.close()
    config = (directory / "config/dialogauth/config.properties").read_text()
    assert "allow-offline-mode=false" in config
    assert "unencrypted Minecraft connection" in config
    assert not (directory / "config/dialogauth/accounts.json").exists()
    print("PASS: offline server with default config fails closed before the dialog")


def allowed_scenarios(port, directory):
    migrated = Client(port, "LDP_e")
    assert b"password_confirm" not in migrated.dialog
    finish_login(migrated, "Migration", duplicate=True)
    accounts_file = directory / "config/dialogauth/accounts.json"
    accounts = json.loads(accounts_file.read_text())["accounts"]
    assert len(accounts) == 1
    assert str(ONLINE_UUID) in accounts
    assert str(offline_uuid("LDP_e")) not in accounts
    record = accounts[str(ONLINE_UUID)]
    assert record["salt"] == base64.b64encode(ONLINE_SALT).decode()
    assert record["hash"] == base64.b64encode(ONLINE_HASH).decode()
    assert record["username"] == "LDP_e"
    assert accounts_file.stat().st_mode & 0o777 == 0o600
    time.sleep(1.1)

    wrong = Client(port, "LDP_E")
    assert b"password_confirm" not in wrong.dialog
    wrong.submit("incorrectPassword")
    packet, data = wrong.receive()
    assert packet == 18, ("Wrong password did not return login dialog", packet, data)
    wrong.close()
    accounts = json.loads(accounts_file.read_text())["accounts"]
    assert len(accounts) == 1 and str(offline_uuid("LDP_E")) not in accounts
    blocked = Client(port, "ldp_e")
    blocked.submit(ONLINE_PASSWORD)
    packet, data = blocked.receive()
    assert packet == 2, ("Case variant bypassed offline rate limit", packet, data)
    assert "Слишком частые".encode() in data
    blocked.close()
    time.sleep(1.1)

    name = "DialogAuthSmoke"
    first = Client(port, name)
    assert b"password_confirm" in first.dialog
    finish_login(first, "Registration", duplicate=True, confirmation="testPassword123")
    accounts = json.loads(accounts_file.read_text())["accounts"]
    assert len(accounts) == 2 and str(offline_uuid(name)) in accounts
    time.sleep(1.1)

    case_variant = Client(port, "dialogauthsmoke")
    assert b"password_confirm" not in case_variant.dialog
    finish_login(case_variant, "Case-variant login", duplicate=True)
    accounts = json.loads(accounts_file.read_text())["accounts"]
    assert len(accounts) == 2 and str(offline_uuid("dialogauthsmoke")) not in accounts
    print("PASS: production offline registration/login, online-UUID migration by password, "
          "no aliases or credential overwrite, case-insensitive duplicate protection and rate limit")


def prepare_online_account(directory):
    config = directory / "config/dialogauth"
    config.mkdir(parents=True)
    record = {
        "username": "LDP_e",
        "salt": base64.b64encode(ONLINE_SALT).decode(),
        "hash": base64.b64encode(ONLINE_HASH).decode(),
        "iterations": 210000,
        "createdAt": "2025-01-01T00:00:00Z",
        "lastLogin": "2025-01-01T00:00:00Z",
    }
    (config / "accounts.json").write_text(json.dumps({
        "formatVersion": 1,
        "accounts": {str(ONLINE_UUID): record},
    }))


def read_mod_version(jar):
    with zipfile.ZipFile(jar) as archive:
        with archive.open("fabric.mod.json") as raw:
            metadata = json.loads(raw.read())
    return metadata["version"]


def find_client_kit_zip(kit_path):
    stable = kit_path / "dialog-auth-client.zip"
    if stable.is_file():
        return stable
    if kit_path.is_file():
        return kit_path
    return None


def parse_properties(text):
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            continue
        result[key.strip()] = value.strip()
    return result


def client_kit_scenario(port, directory, download_url, kit_path, version, fabric_version,
                        server_jar, fabric_api_jar, modpack_url, modpack_servers):
    vanilla = Client(port, "NoCompanion", expect_disconnect=True)
    assert vanilla.disconnection is not None, "Vanilla offline client was not disconnected"
    assert download_url.encode() in vanilla.disconnection, \
        ("Disconnect does not advertise client-download-url", vanilla.disconnection)
    vanilla.close()

    archive_path = find_client_kit_zip(kit_path)
    assert archive_path is not None, f"client kit zip was not created at {kit_path}"
    required = {
        f"mods/dialog-auth-{version}.jar",
        f"mods/dialog-auth-companion-{version}.jar",
        f"mods/fabric-api-{fabric_version}.jar",
        "INSTALL.txt",
        "config/dialogauth/modpack.properties",
    }
    allowed_client_config = {"config/dialogauth/modpack.properties"}
    with zipfile.ZipFile(archive_path) as archive:
        names = set(archive.namelist())
        missing = required - names
        assert not missing, ("client kit zip is missing entries", sorted(missing), sorted(names))
        forbidden = []
        for name in names:
            lower = name.lower()
            top = name.split("/")[0]
            if name in allowed_client_config:
                continue
            if (lower.endswith("accounts.json") or lower.endswith(".key") or lower.endswith(".pem")
                    or "private" in lower):
                forbidden.append(name)
            elif top in ("config", "world", "worlds"):
                forbidden.append(name)
        assert not forbidden, ("client kit zip contains forbidden entries", forbidden)
        for name in required:
            assert archive.getinfo(name).file_size > 0, ("client kit zip entry is empty", name)
        install_bytes = archive.read("INSTALL.txt")
        try:
            install = install_bytes.decode("utf-8")
        except UnicodeDecodeError:
            install = install_bytes.decode("cp1251", errors="replace")
        assert any("\u0400" <= char <= "\u04ff" for char in install), "INSTALL.txt is not in Russian"
        assert "prism" in install.lower(), "INSTALL.txt does not mention Prism"
        migration_markers = ("1.3.0", "удал", "dialog-auth-1.3.0.jar", "dialog-auth-companion-1.3.0.jar")
        assert all(marker in install for marker in migration_markers), \
            ("INSTALL.txt lacks the 1.3.0 migration warning", install)
        modpack = parse_properties(archive.read("config/dialogauth/modpack.properties").decode("utf-8"))
        assert modpack == {"url": modpack_url, "servers": modpack_servers}, \
            ("client kit modpack.properties has unexpected content", modpack)
        assert archive.read(f"mods/dialog-auth-{version}.jar") == Path(server_jar).read_bytes(), \
            "client kit server jar differs from production JAR"
        assert archive.read(f"mods/fabric-api-{fabric_version}.jar") == Path(fabric_api_jar).read_bytes(), \
            "client kit Fabric API differs from installed Fabric API"
        with zipfile.ZipFile(io.BytesIO(archive.read(f"mods/dialog-auth-companion-{version}.jar"))) as companion:
            with companion.open("fabric.mod.json") as raw:
                companion_metadata = json.loads(raw.read())
        assert companion_metadata["id"] == "dialogauth-companion", \
            "client kit companion jar has the wrong mod id"
    print("PASS: offline no-companion disconnect advertises client kit and server-generated zip")


def write_config(directory, config):
    if config is None:
        return
    target = directory / "config/dialogauth"
    target.mkdir(parents=True, exist_ok=True)
    lines = [f"{key}={value}\n" for key, value in config.items()]
    (target / "config.properties").write_text("".join(lines))


def run_server(args, directory, config, scenario):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "mods").mkdir()
    shutil.copy2(args.mod, directory / "mods" / args.mod.name)
    shutil.copy2(args.fabric_api, directory / "mods" / args.fabric_api.name)
    shutil.copy2(args.minecraft_server, directory / "server.jar")
    (directory / "eula.txt").write_text("eula=true\n")
    write_config(directory, config)
    with socket.socket() as available:
        available.bind(("127.0.0.1", 0))
        port = available.getsockname()[1]
    (directory / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n"
        "network-compression-threshold=-1\nlevel-type=minecraft:flat\n"
        "generate-structures=false\nview-distance=2\nsimulation-distance=2\n"
        "enable-query=false\nenable-rcon=false\nmax-players=4\n")
    process = subprocess.Popen([args.java, "-Xms256M", "-Xmx1G", "-jar", str(args.launcher.resolve()), "nogui"],
                               cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True)
    ready = threading.Event()
    logs = []

    def collect():
        for line in process.stdout:
            logs.append(line)
            if "Done (" in line:
                ready.set()

    thread = threading.Thread(target=collect, daemon=True)
    thread.start()
    try:
        assert ready.wait(240), "Test server did not become ready"
        scenario(port, directory)
    except BaseException:
        print("".join(logs))
        raise
    finally:
        if process.poll() is None:
            process.stdin.write("stop\n")
            process.stdin.flush()
            try:
                process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=15)
        thread.join(timeout=5)
    assert process.returncode == 0, (process.returncode, "".join(logs))
    return "".join(logs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--minecraft-server", type=Path, required=True)
    parser.add_argument("--mod", type=Path, required=True)
    parser.add_argument("--fabric-api", type=Path, required=True)
    parser.add_argument("--accept-test-eula", action="store_true", required=True)
    args = parser.parse_args()
    version = read_mod_version(args.mod)
    fabric_version = read_mod_version(args.fabric_api)
    root = Path(__file__).resolve().parents[3]
    (root / "build").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="protocol-smoke-", dir=root / "build") as temporary:
        root_directory = Path(temporary)
        blocked_logs = run_server(args, root_directory / "blocked", None, blocked_scenario)
        assert "offline support is disabled" in blocked_logs
        assert "Blocked offline-mode authentication" in blocked_logs
        allowed_directory = root_directory / "allowed"
        allowed_directory.mkdir()
        prepare_online_account(allowed_directory)
        allowed_logs = run_server(args, allowed_directory,
                                  {"allow-offline-mode": "true", "require-encrypted-auth": "false"},
                                  allowed_scenarios)
        assert "Microsoft does not verify usernames" in allowed_logs
        assert "dialog passwords travel unencrypted" in allowed_logs
        assert "unregistered name can be claimed" in allowed_logs

        kit_directory = root_directory / "kit"
        kit_directory.mkdir()
        download_url = "https://example.com/dialog-auth-client.zip"
        modpack_url = "https://example.com/modpack/manifest.json"
        modpack_servers = "example.com:25565"
        kit_path = (kit_directory / "client-kit").resolve()
        kit_logs = run_server(args, kit_directory, {
            "allow-offline-mode": "true",
            "require-encrypted-auth": "true",
            "client-download-url": download_url,
            "client-kit-path": str(kit_path),
            "client-modpack-manifest-url": modpack_url,
            "client-modpack-servers": modpack_servers,
        }, lambda port, directory: client_kit_scenario(
            port, directory, download_url, kit_path, version, fabric_version,
            args.mod, args.fabric_api, modpack_url, modpack_servers))
        assert "Dialog Auth initialized" in kit_logs


if __name__ == "__main__":
    main()
