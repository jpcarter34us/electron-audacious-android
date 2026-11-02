"""A tiny OpenSubsonic server over the test media, for exercising the app.
Run: python3 -I mock_subsonic.py <media dir> [port]. Logs each request."""
import hashlib, json, os, sys, time, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

MEDIA = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 4533
USER, PASSWORD = "test", "secret"
files = sorted(f for f in os.listdir(MEDIA) if not f.startswith("."))
songs = [{"id": f"s{i}", "title": os.path.splitext(f)[0], "artist": "Test Artist", "album": "Test Album",
          "albumId": "al1", "coverArt": "al1", "suffix": f.rsplit(".", 1)[1], "duration": 30, "track": i + 1, "file": f}
         for i, f in enumerate(files)]
album = {"id": "al1", "name": "Test Album", "artist": "Test Artist", "artistId": "ar1", "songCount": len(songs), "coverArt": "al1", "year": 2026}
log = []


def public(s):
    return {k: v for k, v in s.items() if k != "file"}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, body):
        data = json.dumps({"subsonic-response": dict(status="ok", version="1.16.1", type="mock", serverVersion="0.1", openSubsonic=True, **body)}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        method = u.path.rsplit("/", 1)[-1].replace(".view", "")
        print(time.strftime("%H:%M:%S"), method, {k: v for k, v in q.items() if k not in ("t", "s", "u", "v", "c", "f")}, flush=True)
        ok = q.get("u") == USER and q.get("t") == hashlib.md5((PASSWORD + q.get("s", "")).encode()).hexdigest()
        if not ok:
            data = json.dumps({"subsonic-response": {"status": "failed", "error": {"code": 40, "message": "Wrong username or password"}}}).encode()
            self.send_response(200); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
            return
        if method == "ping": return self.reply({})
        if method == "getOpenSubsonicExtensions": return self.reply({"openSubsonicExtensions": [{"name": "apiKeyAuthentication", "versions": [1]}]})
        if method == "getArtists": return self.reply({"artists": {"index": [{"name": "T", "artist": [{"id": "ar1", "name": "Test Artist", "albumCount": 1}]}]}})
        if method == "getArtist": return self.reply({"artist": {"id": "ar1", "name": "Test Artist", "album": [album]}})
        if method == "getAlbumList2": return self.reply({"albumList2": {"album": [album] if int(q.get("offset", 0)) == 0 else []}})
        if method == "getAlbum": return self.reply({"album": dict(album, song=[public(s) for s in songs])})
        if method == "search3":
            off = int(q.get("songOffset", 0))
            return self.reply({"searchResult3": {"song": [public(s) for s in songs][off:off + int(q.get("songCount", 20))]}})
        if method in ("getPlaylists", "getStarred2", "getRandomSongs"):
            return self.reply({"playlists": {"playlist": []}, "starred2": {}, "randomSongs": {"song": [public(s) for s in songs]}})
        if method == "scrobble": return self.reply({})
        if method == "getCoverArt":
            self.send_response(404); self.end_headers(); return
        if method == "stream":
            s = next((s for s in songs if s["id"] == q.get("id")), None)
            path = os.path.join(MEDIA, s["file"])
            size = os.path.getsize(path)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(path, "rb") as f:
                # Slowly, so playback starts before the download ends.
                while True:
                    chunk = f.read(65536)
                    if not chunk: break
                    try:
                        self.wfile.write(chunk)
                    except BrokenPipeError:
                        return
                    time.sleep(0.05)
            return
        self.reply({})


ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
