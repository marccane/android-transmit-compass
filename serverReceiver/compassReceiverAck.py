#!/usr/bin/env python3
import socket
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 5000))
while True:
    data, addr = sock.recvfrom(64)
    print(f"{addr[0]}: {data.decode().strip()}°")
    sock.sendto(b"OK\n", addr)  # ACK back to sender
