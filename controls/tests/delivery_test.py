"""Fault injection against the real Java transport, strictly on loopback.

The sequence oracle is geckolib's separate 192..255 command range, not our
encoder. OFF loss/retry behavior is deliberately exercised independently of
the always-successful bridge used in network_test.py.
"""
import re
import socket
import subprocess
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENVELOPE = re.compile(rb"<PACKT><SRCCN>([^<>]+)</SRCCN><DESCN>([^<>]+)</DESCN><DATAS>(.*)</DATAS></PACKT>", re.S)
JAVA = "/opt/homebrew/opt/openjdk@21/bin/java"


def scenario(name):
    status = bytearray((ROOT / "tests/status-fixture.bin").read_bytes())
    status[256] = 1
    status[269] |= 1
    status[295] |= 4
    commands, errors = [], []
    stop = threading.Event()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind(("127.0.0.1", 0))
        sock.settimeout(.1)
        def serve():
            try:
                while not stop.is_set():
                    try:
                        packet, addr = sock.recvfrom(4096)
                    except socket.timeout:
                        continue
                    assert addr[0] == "127.0.0.1"
                    match = ENVELOPE.fullmatch(packet)
                    assert match, "Bad wire envelope"
                    src, dst, body = match.groups()
                    def reply(value):
                        sock.sendto(b"<PACKT><SRCCN>" + dst + b"</SRCCN><DESCN>" + src +
                                    b"</DESCN><DATAS>" + value + b"</DATAS></PACKT>", addr)
                    if body.startswith(b"SFILE"):
                        reply(b"FILES,MrSt_C03.xml,MrSt_S03.xml")
                    elif body.startswith(b"STATU"):
                        start, length = int.from_bytes(body[6:8], 'big'), int.from_bytes(body[8:10], 'big')
                        data = status[start:start + length]
                        chunks = [data[i:i+39] for i in range(0, len(data), 39)]
                        for i, chunk in enumerate(chunks):
                            reply(b"STATV" + bytes([i, i+1 if i+1 < len(chunks) else 0, len(chunk)]) + chunk)
                    elif body.startswith(b"SPACK"):
                        commands.append((src, body))
                        if name == "drop-first-stop" and len(commands) == 1:
                            continue
                        if name in ("drop-all-stop", "drop-start"):
                            continue
                        if name in ("drop-first-stop", "lose-stop-receipts"):
                            assert body[11:] == b"\x01\x00\x00", "Retries may only set UserMode OFF"
                            status[256] = 0
                            status[269] &= ~1
                            status[295] &= ~4
                        if name != "lose-stop-receipts":
                            reply(b"PACKS")
                    else:
                        raise AssertionError("Unexpected protocol verb")
            except BaseException as error:
                errors.append(error)
        thread = threading.Thread(target=serve, daemon=True)
        thread.start()
        try:
            result = subprocess.run([JAVA, "-cp", str(ROOT / "build/test-classes"),
                                     "local.steam.controls.DeliveryTest", "--loopback",
                                     str(sock.getsockname()[1]), name], timeout=20)
        finally:
            stop.set()
            thread.join(2)
    assert not errors, errors
    assert result.returncode == 0, f"{name}: Java assertion failed"
    if name == "sequences":
        observed = [body[5] for _, body in commands]
        assert observed == list(range(192, 256)) + [192, 193], observed
    elif name == "drop-start":
        assert len(commands) == 1, "Start must never retry automatically"
    else:
        expected = 2 if name == "drop-first-stop" else 3
        assert len(commands) == expected, (name, len(commands))
        assert all(command == commands[0] for command in commands), "OFF retries retain identity and sequence"
    print(f"Loopback delivery passed: {name} ({len(commands)} packets)")


if __name__ == "__main__":
    import sys
    for name in sys.argv[1:] or ("sequences", "drop-first-stop", "lose-stop-receipts", "drop-all-stop", "drop-start"):
        scenario(name)
