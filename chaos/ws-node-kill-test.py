"""2-node chat kill test: cross-node delivery via Redis pub/sub, then node kill.
Usage: uv run --with websockets python chat_test.py
"""
import asyncio
import json
import subprocess
import sys

import websockets

NODE1 = "ws://localhost:8090/ws?user=alice&room=deal-42"
NODE2 = "ws://localhost:8096/ws?user=bob&room=deal-42"


async def main():
    a = await websockets.connect(NODE1)
    b = await websockets.connect(NODE2)

    # 1. Cross-node: alice (node1) -> bob (node2) through Redis pub/sub
    await a.send(json.dumps({"text": "hello from node 8090"}))
    frame = json.loads(await asyncio.wait_for(b.recv(), 5))
    assert frame["from"] == "alice" and "8090" in frame["text"], frame
    print("PASS cross-node delivery:", frame["text"])

    # 2. Kill node 1; alice reconnects to node 2; chat continues
    a_ws = a
    await a_ws.close()
    # env vars don't show in ps args — find the node by its listening port
    subprocess.run(["sh", "-c",
                    "kill $(lsof -tiTCP:8090 -sTCP:LISTEN) 2>/dev/null || true"], check=False)
    await asyncio.sleep(1)

    a2 = await websockets.connect("ws://localhost:8096/ws?user=alice&room=deal-42")
    await a2.send(json.dumps({"text": "survived the node kill"}))
    frame2 = json.loads(await asyncio.wait_for(b.recv(), 5))
    assert frame2["from"] == "alice" and "survived" in frame2["text"], frame2
    print("PASS post-kill delivery on survivor node:", frame2["text"])

    await a2.close()
    await b.close()
    print("ALL CHAT TESTS PASS")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as e:
        print("FAIL:", e)
        sys.exit(1)
