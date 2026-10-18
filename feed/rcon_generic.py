#!/usr/bin/env python3
"""Generic minimal RCON client. Usage: SR_PW=pw python3 rcon_generic.py <host> <port> <cmdfile>"""
import socket, struct, sys, os

def pkt(pid, ptype, body: bytes):
    return struct.pack("<ii", pid, ptype) + body + b"\x00\x00"

def recv_pkt(sock):
    ln = sock.recv(4)
    (length,) = struct.unpack("<i", ln)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    rid, rtype = struct.unpack("<ii", data[:8])
    return rid, rtype, data[8:-2]

def main():
    host, port, cmdfile = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    cmds = [l.strip() for l in open(cmdfile) if l.strip()]
    s = socket.create_connection((host, port), timeout=15)
    def snd(p):
        s.sendall(struct.pack("<i", len(p)) + p)
    snd(pkt(1, 3, os.environ.get("SR_PW", "").encode()))
    rid, _, _ = recv_pkt(s)
    if rid == -1:
        print("AUTH FAILED")
        return
    for c in cmds:
        snd(pkt(2, 2, c.encode()))
        _, _, resp = recv_pkt(s)
        print("> " + c)
        if resp.strip():
            print(resp.decode(errors="replace"))
    s.close()

if __name__ == "__main__":
    main()
