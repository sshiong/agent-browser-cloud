#!/usr/bin/env python3

"""Small RFB 3.8 server used to verify the real noVNC client and input loop."""

import json
import os
import socket
import struct
import sys
import threading
import time


def argument(name: str) -> str | None:
    try:
        return sys.argv[sys.argv.index(name) + 1]
    except (ValueError, IndexError):
        return None


EVENT_LOG = os.environ.get("FAKE_VNC_EVENT_LOG")
EVENT_LOG_LOCK = threading.Lock()


def record(event: dict[str, object]) -> None:
    if not EVENT_LOG:
        return
    event["at"] = time.time()
    with EVENT_LOG_LOCK:
        with open(EVENT_LOG, "a", encoding="utf-8") as stream:
            stream.write(json.dumps(event, separators=(",", ":")) + "\n")


if argument("-remote") is not None:
    record({"type": "release", "command": argument("-remote")})
    raise SystemExit(0)

port_value = argument("-rfbport")
if port_value is None:
    raise SystemExit("fake x11vnc requires -rfbport")

WIDTH = 320
HEIGHT = 200
FRAME_INTERVAL_SECONDS = 1 / 30


def read_exact(connection: socket.socket, length: int) -> bytes:
    data = bytearray()
    while len(data) < length:
        chunk = connection.recv(length - len(data))
        if not chunk:
            raise EOFError
        data.extend(chunk)
    return bytes(data)


def pixels(pixel_format: bytes) -> bytes:
    bits_per_pixel, _, big_endian, true_colour = pixel_format[:4]
    if not true_colour or bits_per_pixel not in (16, 32):
        bits_per_pixel = 32
        big_endian = 0
        red_max = green_max = blue_max = 255
        red_shift, green_shift, blue_shift = 16, 8, 0
    else:
        red_max, green_max, blue_max = struct.unpack(">HHH", pixel_format[4:10])
        red_shift, green_shift, blue_shift = pixel_format[10:13]
    byte_width = bits_per_pixel // 8
    order = "big" if big_endian else "little"
    result = bytearray()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            band = (x // 40 + y // 40) % 2
            red = int((35 if band else 11) * red_max / 255)
            green = int((214 if band else 81) * green_max / 255)
            blue = int((190 if band else 100) * blue_max / 255)
            value = (red << red_shift) | (green << green_shift) | (blue << blue_shift)
            result.extend(value.to_bytes(byte_width, order))
    return bytes(result)


def serve(connection: socket.socket) -> None:
    connection.settimeout(20)
    connection.sendall(b"RFB 003.008\n")
    read_exact(connection, 12)
    connection.sendall(b"\x01\x01")
    selected = read_exact(connection, 1)
    if selected != b"\x01":
        raise EOFError
    connection.sendall(struct.pack(">I", 0))
    read_exact(connection, 1)
    pixel_format = bytes(
        [
            32,
            24,
            0,
            1,
            0,
            255,
            0,
            255,
            0,
            255,
            16,
            8,
            0,
            0,
            0,
            0,
        ]
    )
    name = b"Browser Cloud Fake Desktop"
    connection.sendall(
        struct.pack(">HH", WIDTH, HEIGHT)
        + pixel_format
        + struct.pack(">I", len(name))
        + name
    )
    framebuffer = pixels(pixel_format)
    last_frame_at = 0.0
    record({"type": "connected"})

    while True:
        message_type = read_exact(connection, 1)[0]
        if message_type == 0:
            body = read_exact(connection, 19)
            pixel_format = body[3:]
            framebuffer = pixels(pixel_format)
        elif message_type == 2:
            header = read_exact(connection, 3)
            encoding_count = struct.unpack(">H", header[1:])[0]
            read_exact(connection, encoding_count * 4)
        elif message_type == 3:
            read_exact(connection, 9)
            now = time.monotonic()
            remaining = FRAME_INTERVAL_SECONDS - (now - last_frame_at)
            if remaining > 0:
                time.sleep(remaining)
            rectangle = (
                b"\x00\x00"
                + struct.pack(">H", 1)
                + struct.pack(">HHHHI", 0, 0, WIDTH, HEIGHT, 0)
                + framebuffer
            )
            connection.sendall(rectangle)
            last_frame_at = time.monotonic()
            record({"type": "frame", "width": WIDTH, "height": HEIGHT})
        elif message_type == 4:
            body = read_exact(connection, 7)
            down = body[0] == 1
            keysym = struct.unpack(">I", body[3:])[0]
            record({"type": "key", "down": down, "keysym": keysym})
        elif message_type == 5:
            body = read_exact(connection, 5)
            mask = body[0]
            x, y = struct.unpack(">HH", body[1:])
            record({"type": "pointer", "mask": mask, "x": x, "y": y})
        elif message_type == 6:
            header = read_exact(connection, 7)
            length = struct.unpack(">I", header[3:])[0]
            read_exact(connection, length)
        elif message_type == 150:
            read_exact(connection, 9)
        else:
            record({"type": "unsupported", "messageType": message_type})
            raise EOFError


listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", int(port_value)))
listener.listen(4)
record({"type": "listening", "port": int(port_value)})


def serve_client(client: socket.socket) -> None:
    try:
        serve(client)
    except (EOFError, ConnectionError, OSError, socket.timeout) as error:
        record({"type": "connection_error", "error": type(error).__name__})
    finally:
        client.close()
        record({"type": "disconnected"})


while True:
    client, _ = listener.accept()
    record({"type": "accepted"})
    threading.Thread(target=serve_client, args=(client,), daemon=True).start()
