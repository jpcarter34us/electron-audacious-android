"""A tiny DAAP share (OwnTone-like) over the test media, with an optional
password. Run: python3 -I mock_daap.py <media dir> [port] [password]."""
import base64, os, struct, sys, time, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

MEDIA = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 3689
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else None
files = sorted(f for f in os.listdir(MEDIA) if not f.startswith("."))


def tag(name, value):
    if isinstance(value, bytes):
        body = value
    elif isinstance(value, str):
        body = value.encode()
    elif isinstance(value, tuple):  # (int, width)
        body = value[0].to_bytes(value[1], "big")
    else:
        body = value.to_bytes(4, "big")
    return name.encode() + struct.pack(">I", len(body)) + body


def song(i, f):
    title, ext = os.path.splitext(f)
    return tag("mlit", b"".join([
        tag("miid", i + 1), tag("minm", title.replace("tone-", "").upper() + " over DAAP"),
        tag("asar", "DAAP Artist"), tag("asal", "DAAP Album"), tag("asgn", "Electronic"),
        tag("astm", 30000), tag("astn", (i + 1, 2)), tag("asyr", (2026, 2)), tag("asfm", ext[1:]), tag("asdk", (0, 1)),
    ]))


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, body, ctype="application/x-dmap-tagged"):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        print(time.strftime("%H:%M:%S"), u.path, u.query[:80], flush=True)
        if PASSWORD and u.path != "/server-info":
            auth = self.headers.get("Authorization", "")
            ok = auth.startswith("Basic ") and base64.b64decode(auth[6:]).decode().split(":", 1)[-1] == PASSWORD
            if not ok:
                self.send_response(401); self.send_header("Content-Length", "0"); self.end_headers(); return
        p = u.path
        if p == "/server-info":
            return self.reply(tag("msrv", tag("mstt", 200) + tag("minm", "Mock DAAP Share") + tag("apro", (0x30000, 4))))
        if p == "/login":
            return self.reply(tag("mlog", tag("mstt", 200) + tag("mlid", 42)))
        if p == "/update":
            return self.reply(tag("mupd", tag("mstt", 200) + tag("musr", 7)))
        if p == "/databases":
            return self.reply(tag("avdb", tag("mstt", 200) + tag("mlcl", tag("mlit", tag("miid", 1) + tag("minm", "Mock Library")))))
        if p == "/databases/1/items":
            return self.reply(tag("adbs", tag("mstt", 200) + tag("mlcl", b"".join(song(i, f) for i, f in enumerate(files)))))
        if p == "/databases/1/containers":
            return self.reply(tag("aply", tag("mstt", 200) + tag("mlcl",
                tag("mlit", tag("miid", 100) + tag("minm", "Library") + tag("abpl", (1, 1))) +
                tag("mlit", tag("miid", 101) + tag("minm", "Favourites") + tag("mimc", 2)))))
        if p == "/databases/1/containers/101/items":
            return self.reply(tag("apso", tag("mstt", 200) + tag("mlcl", tag("mlit", tag("miid", 2)) + tag("mlit", tag("miid", 4)))))
        if p.startswith("/databases/1/items/") and "extra_data" not in p:
            item = int(p.rsplit("/", 1)[1].split(".")[0])
            with open(os.path.join(MEDIA, files[item - 1]), "rb") as fh:
                return self.reply(fh.read(), "audio/*")
        self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()


ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
