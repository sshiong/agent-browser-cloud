#!/usr/bin/env python3

"""Bidirectional TCP relay that can be SIGSTOP'ed for deterministic partitions."""

import asyncio
import sys


async def pump(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
) -> None:
    try:
        while data := await reader.read(64 * 1024):
            writer.write(data)
            await writer.drain()
    finally:
        writer.close()
        await writer.wait_closed()


async def relay(
    downstream_reader: asyncio.StreamReader,
    downstream_writer: asyncio.StreamWriter,
    target_host: str,
    target_port: int,
) -> None:
    try:
        upstream_reader, upstream_writer = await asyncio.open_connection(
            target_host,
            target_port,
        )
    except OSError:
        downstream_writer.close()
        await downstream_writer.wait_closed()
        return

    downstream = asyncio.create_task(pump(downstream_reader, upstream_writer))
    upstream = asyncio.create_task(pump(upstream_reader, downstream_writer))
    done, pending = await asyncio.wait(
        {downstream, upstream},
        return_when=asyncio.FIRST_COMPLETED,
    )
    for task in pending:
        task.cancel()
    await asyncio.gather(*done, *pending, return_exceptions=True)


async def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: fault-tcp-proxy.py <listen-port> <target-host> <target-port>"
        )
    listen_port = int(sys.argv[1])
    target_host = sys.argv[2]
    target_port = int(sys.argv[3])
    server = await asyncio.start_server(
        lambda reader, writer: relay(
            reader,
            writer,
            target_host,
            target_port,
        ),
        "127.0.0.1",
        listen_port,
    )
    print(f"fault-tcp-proxy-ready={listen_port}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
