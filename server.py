from http.server import BaseHTTPRequestHandler, HTTPServer
import json, os

BASE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(BASE, "history.json")


def read_history():
    try:
        with open(DB) as f:
            return json.load(f)
    except Exception:
        return []


class Handler(BaseHTTPRequestHandler):
    def send_json(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/history":
            return self.send_json(200, read_history())
        with open(os.path.join(BASE, "index.html"), "rb") as f:
            page = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.end_headers()
        self.wfile.write(page)

    def do_POST(self):
        if self.path == "/api/history":
            size = int(self.headers.get("Content-Length", 0))
            item = json.loads(self.rfile.read(size))
            data = read_history()
            data.append({"mode": item["mode"], "op": item["op"], "data": item["data"]})
            with open(DB, "w") as f:
                json.dump(data[-50:], f)
            return self.send_json(201, {"saved": True})
        self.send_json(404, {"error": "not found"})


print("Running at http://localhost:3000")
HTTPServer(("", 3000), Handler).serve_forever()
