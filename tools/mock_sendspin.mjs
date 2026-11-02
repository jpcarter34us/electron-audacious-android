// A mock Sendspin server (server role) using only Node built-ins, for
// exercising the Android client on a device. Usage:
//   node mock_sendspin.mjs <port> <pinfile>
// Connection 1 (proxied with {"type":"auth"} if the client sends it, or
// direct): Sentinel PSK, dynamic-PIN pairing (the PIN is read from
// <pinfile> once it exists), the client mints a long-term PSK.
// Connection 2+: that PSK, playback activation, clock sync, a 20 s PCM
// tone in real time, metadata, volume command, a re-handshake, and the
// client's controller commands are logged.
import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";

const PORT = +process.argv[2] || 8927;
const PINFILE = process.argv[3] || "pin.txt";
const TOKEN = "test-token";
const OFFSET_US = 5_000_000n;
const nowUs = () => process.hrtime.bigint() / 1000n;
const serverNowUs = () => nowUs() + OFFSET_US;
const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);
const enc = new TextEncoder();
const dec = new TextDecoder();
const cat = (...a) => Buffer.concat(a.map((x) => Buffer.from(x)));

// ---- primitives ----
const b64u = (b) => Buffer.from(b).toString("base64url");
const unb64u = (s) => Buffer.from(s, "base64url");
const sha256 = (d) => crypto.createHash("sha256").update(d).digest();
const sha512 = (d) => crypto.createHash("sha512").update(d).digest();
const hmac = (alg, k, d) => crypto.createHmac(alg, k).update(d).digest();
function x25519(scalar, point) {
  const priv = crypto.createPrivateKey({ key: cat(Buffer.from("302e020100300506032b656e04220420", "hex"), scalar), format: "der", type: "pkcs8" });
  const pub = crypto.createPublicKey({ key: cat(Buffer.from("302a300506032b656e032100", "hex"), point), format: "der", type: "spki" });
  return crypto.diffieHellman({ privateKey: priv, publicKey: pub });
}
const BASE = Buffer.alloc(32); BASE[0] = 9;
const keyPair = () => { const sk = crypto.randomBytes(32); return { sk, pk: x25519(sk, BASE) }; };
function nonce(n) { const b = Buffer.alloc(12); b.writeBigUInt64LE(BigInt(n), 4); return b; }
function seal(key, n, ad, pt) {
  const c = crypto.createCipheriv("chacha20-poly1305", key, nonce(n), { authTagLength: 16 });
  c.setAAD(ad, { plaintextLength: pt.length });
  return cat(c.update(pt), c.final(), c.getAuthTag());
}
function open(key, n, ad, ct) {
  const d = crypto.createDecipheriv("chacha20-poly1305", key, nonce(n), { authTagLength: 16 });
  d.setAAD(ad, { plaintextLength: ct.length - 16 });
  d.setAuthTag(ct.subarray(ct.length - 16));
  return cat(d.update(ct.subarray(0, ct.length - 16)), d.final());
}

// ---- Noise_KKpsk2_25519_ChaChaPoly_SHA256, initiator ----
class Cipher {
  constructor(k) { this.k = k; this.n = 0; }
  enc(ad, pt) { return this.k ? seal(this.k, this.n++, ad, pt) : Buffer.from(pt); }
  dec(ad, ct) { return this.k ? open(this.k, this.n++, ad, ct) : Buffer.from(ct); }
}
function hkdf(ck, ikm, n) {
  const t = hmac("sha256", ck, ikm);
  const o1 = hmac("sha256", t, Buffer.from([1]));
  const o2 = hmac("sha256", t, cat(o1, [2]));
  return n === 2 ? [o1, o2] : [o1, o2, hmac("sha256", t, cat(o2, [3]))];
}
class Handshake {
  constructor(prologue, s, rs, psk) {
    const name = Buffer.from("Noise_KKpsk2_25519_ChaChaPoly_SHA256");
    this.h = name.length <= 32 ? cat(name, Buffer.alloc(32 - name.length)) : sha256(name);
    this.ck = this.h; this.c = new Cipher(null); this.s = s; this.rs = rs; this.psk = psk;
    this.mixHash(prologue); this.mixHash(s.pk); this.mixHash(rs);
  }
  mixHash(d) { this.h = sha256(cat(this.h, d)); }
  mixKey(d) { const [ck, k] = hkdf(this.ck, d, 2); this.ck = ck; this.c = new Cipher(k); }
  mixKeyAndHash(d) { const [ck, t, k] = hkdf(this.ck, d, 3); this.ck = ck; this.mixHash(t); this.c = new Cipher(k); }
  write1(payload) {
    this.e = keyPair();
    this.mixHash(this.e.pk); this.mixKey(this.e.pk);
    this.mixKey(x25519(this.e.sk, this.rs)); // es
    this.mixKey(x25519(this.s.sk, this.rs)); // ss
    const ct = this.c.enc(this.h, payload); this.mixHash(ct);
    return cat(this.e.pk, ct);
  }
  read2(m) {
    const re = m.subarray(0, 32);
    this.mixHash(re); this.mixKey(re);
    this.mixKey(x25519(this.e.sk, re)); // ee
    this.mixKey(x25519(this.s.sk, re)); // se: initiator's s with responder's e
    this.mixKeyAndHash(this.psk);
    const ct = m.subarray(32); const pt = this.c.dec(this.h, ct); this.mixHash(ct);
    return pt;
  }
  split() { const [a, b] = hkdf(this.ck, Buffer.alloc(0), 2); return { send: new Cipher(a), recv: new Cipher(b) }; }
}
const pskId = (psk) => b64u(sha256(cat(enc.encode("sendspin-psk-id-v1"), psk)));
const SENTINEL = sha256(enc.encode("sendspin-sentinel-psk-v1"));

// ---- CPace initiator (Elligator2 over BigInt) ----
const P = (1n << 255n) - 19n;
const mod = (a) => ((a % P) + P) % P;
const pow = (b, e) => { let r = 1n; b = mod(b); while (e > 0n) { if (e & 1n) r = mod(r * b); b = mod(b * b); e >>= 1n; } return r; };
const inv = (a) => pow(a, P - 2n);
const leToBig = (b) => { let v = 0n; for (let i = b.length - 1; i >= 0; i--) v = (v << 8n) | BigInt(b[i]); return v; };
const bigToLe = (v) => { const b = Buffer.alloc(32); for (let i = 0; i < 32; i++) { b[i] = Number(v & 255n); v >>= 8n; } return b; };
function elligator2(r) {
  const A = 486662n;
  const v = mod(-(A * inv(1n + 2n * r * r)));
  const eps = pow(v * v * v + A * v * v + v, (P - 1n) / 2n);
  return bigToLe(mod(eps * v - (1n - eps) * A * inv(2n)));
}
function prependLen(d) { const out = []; let n = d.length; do { out.push(n < 128 ? n : (n & 127) | 128); n >>= 7; } while (n); return cat(out, d); }
const lv = (...p) => cat(...p.map(prependLen));
function generator(prs, sid) {
  const dsi = enc.encode("CPace255");
  const zpad = Math.max(0, 128 - 1 - prependLen(prs).length - prependLen(dsi).length);
  const h = sha512(lv(dsi, prs, Buffer.alloc(zpad), Buffer.alloc(0), sid));
  const r = Buffer.from(h.subarray(0, 32)); r[31] &= 127;
  return elligator2(mod(leToBig(r)));
}

// ---- WebSocket (server side, by hand) ----
function wsAccept(req, socket, onMessage, onClose) {
  const key = req.headers["sec-websocket-key"];
  const accept = crypto.createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  let buf = Buffer.alloc(0);
  socket.on("data", (d) => {
    buf = cat(buf, d);
    for (;;) {
      if (buf.length < 2) return;
      const op = buf[0] & 15; let len = buf[1] & 127; let off = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
      const masked = buf[1] & 128; const mask = masked ? buf.subarray(off, off + 4) : null; if (masked) off += 4;
      if (buf.length < off + len) return;
      const payload = Buffer.from(buf.subarray(off, off + len));
      if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      buf = buf.subarray(off + len);
      if (op === 8) { socket.end(); onClose(); return; }
      if (op === 9) { send(10, payload); continue; }
      if (op === 1 || op === 2) onMessage(payload, op === 2);
    }
  });
  socket.on("close", onClose);
  socket.on("error", () => {});
  function send(op, data) {
    const d = Buffer.from(data); let head;
    if (d.length < 126) head = Buffer.from([128 | op, d.length]);
    else if (d.length < 65536) { head = Buffer.alloc(4); head[0] = 128 | op; head[1] = 126; head.writeUInt16BE(d.length, 2); }
    else { head = Buffer.alloc(10); head[0] = 128 | op; head[1] = 127; head.writeBigUInt64BE(BigInt(d.length), 2); }
    if (!socket.destroyed) socket.write(cat(head, d));
  }
  return { text: (s) => send(1, enc.encode(s)), binary: (b) => send(2, b), close: () => { send(8, Buffer.from([3, 232])); socket.end(); } };
}

// ---- the script ----
const server = keyPair();
const serverId = b64u(server.pk);
let longTermPsk = null;
let connections = 0;

class Conn {
  constructor(ws) { this.ws = ws; this.queue = []; this.waiters = []; this.closed = false; this.session = null; this.probes = 0; }
  push(data, binary) {
    let msg;
    if (this.session && binary) {
      const plain = this.session.recv.dec(Buffer.alloc(0), data);
      if (plain[0] !== 0) throw new Error("unexpected binary type " + plain[0]);
      msg = { raw: null, json: JSON.parse(dec.decode(plain.subarray(1))) };
    } else msg = { raw: dec.decode(data), json: JSON.parse(dec.decode(data)) };
    const t = msg.json.type;
    if (t === "client/time") return this.answerTime(msg.json);
    if (t === "client/state") { this.state = msg.json.payload; log("client/state", JSON.stringify(msg.json.payload)); }
    if (t === "client/command") log("COMMAND", JSON.stringify(msg.json.payload));
    if (t === "client/goodbye") log("goodbye", JSON.stringify(msg.json.payload));
    const w = this.waiters.shift(); if (w) w(msg); else this.queue.push(msg);
  }
  next() { return this.queue.length ? Promise.resolve(this.queue.shift()) : new Promise((r) => this.waiters.push(r)); }
  async expect(type) {
    for (;;) {
      const m = await this.next();
      if (!m) throw new Error("closed waiting for " + type);
      if (m.json.type === type) return m;
    }
  }
  answerTime(m) {
    this.probes++;
    const t = Number(serverNowUs());
    this.send({ type: "server/time", payload: { client_transmitted: m.payload.client_transmitted, server_received: t, server_transmitted: t } });
  }
  send(obj) { this.ws.binary(this.session.send.enc(Buffer.alloc(0), cat([0], enc.encode(JSON.stringify(obj))))); }
  audio(ts, pcm) { const pt = Buffer.alloc(9 + pcm.length); pt[0] = 4; pt.writeBigInt64BE(ts, 1); pcm.copy(pt, 9); this.ws.binary(this.session.send.enc(Buffer.alloc(0), pt)); }
  async handshake(psk) {
    let first = await this.next();
    if (first.json.type === "auth") {
      if (first.json.token !== TOKEN) throw new Error("bad proxy token " + first.json.token);
      log("proxy auth ok, client_id", first.json.client_id);
      this.ws.text(JSON.stringify({ type: "auth_ok" }));
      first = await this.next();
    }
    if (first.json.type !== "client/init") throw new Error("expected client/init, got " + first.raw);
    const init = first.json.payload;
    log("client/init", first.raw);
    this.clientPk = unb64u(init.client_id);
    const serverInit = JSON.stringify({ type: "server/init", payload: { server_id: serverId, version: 1 } });
    this.ws.text(serverInit);
    const hs = new Handshake(cat(enc.encode(first.raw), enc.encode(serverInit)), server, this.clientPk, psk);
    this.ws.text(JSON.stringify({ type: "noise/handshake", payload: { data: b64u(hs.write1(enc.encode(JSON.stringify({ psk_id: pskId(psk) })))) } }));
    const m2 = await this.next();
    const payload = hs.read2(unb64u(m2.json.payload.data));
    this.session = hs.split(); this.h = hs.h;
    return dec.decode(payload);
  }
  async hello() {
    this.send({ type: "server/hello", payload: { server_id: serverId, name: "Mock", version: 1 } });
    return (await this.expect("client/hello")).json.payload;
  }
}

async function pairing(c) {
  if ((await c.handshake(SENTINEL)) !== "{}") throw new Error("msg2 payload");
  log("handshake with the Sentinel PSK OK");
  const hello = await c.hello();
  log("client/hello", JSON.stringify(hello));
  c.send({ type: "server/activate", payload: { activities: ["pairing"], active_roles: [], pairing: { method: "dynamic_pin", pin_length: 6 } } });
  const init = (await c.expect("client/pair-init")).json.payload;
  const nonceA = crypto.randomBytes(32);
  c.send({ type: "server/pair-init", payload: { nonce_A: b64u(nonceA) } });
  log("WAITING FOR PIN in", PINFILE);
  let typed;
  for (;;) { try { typed = fs.readFileSync(PINFILE, "utf8").trim(); if (typed) break; } catch {} await new Promise((r) => setTimeout(r, 300)); }
  log("PIN typed", typed);
  const sid = cat(enc.encode("sendspin-pair-pake-v1"), c.h, [0, 0, 0, 1]);
  const scalar = crypto.randomBytes(32);
  const shareA = x25519(scalar, generator(enc.encode(typed), sid));
  c.send({ type: "server/pair-auth", payload: { pake_msg_1: b64u(shareA) } });
  const shareB = unb64u((await c.expect("client/pair-auth")).json.payload.pake_msg_2);
  const K = x25519(scalar, shareB);
  const transcript = cat(lv(shareA, enc.encode("server")), lv(shareB, enc.encode("client")));
  const isk = sha512(cat(lv(enc.encode("CPace255_ISK"), sid, K), transcript));
  const macKey = sha512(cat(enc.encode("CPaceMac"), sid, isk));
  c.send({ type: "server/pair-confirm", payload: { server_kc: b64u(hmac("sha512", macKey, lv(shareA, enc.encode("server")))) } });
  const confirm = (await c.expect("client/pair-confirm")).json.payload;
  if (!hmac("sha512", macKey, lv(shareB, enc.encode("client"))).equals(unb64u(confirm.client_kc))) throw new Error("client_kc does not verify");
  if (b64u(sha256(cat(enc.encode("sendspin-pair-commit-v1"), unb64u(confirm.nonce_B)))) !== init.commit_B) throw new Error("commit_B mismatch");
  const fin = (await c.expect("client/pair-finalize")).json.payload;
  const kWrap = sha256(cat(enc.encode("sendspin-pair-psk-wrap-v1"), sid, isk));
  const psk = open(kWrap, 0, Buffer.alloc(0), unb64u(fin.wrapped_psk));
  c.send({ type: "server/pair-finalize", payload: {} });
  log("PAIRED, long-term PSK", pskId(psk));
  return psk;
}

async function playback(c, psk) {
  await c.handshake(psk);
  log("handshake with the long-term PSK OK");
  const hello = await c.hello();
  log("trust_level", hello.trust_level, "formats", JSON.stringify(hello["player@v1_support"].supported_formats));
  c.send({ type: "server/activate", payload: { activities: ["playback"], active_roles: ["player@v1", "controller@v1", "metadata@v1"] } });
  await c.expect("client/state");
  while (c.probes < 8) await new Promise((r) => setTimeout(r, 100));
  log("clock probes answered", c.probes);
  await new Promise((r) => setTimeout(r, 300));
  c.send({ type: "stream/start", payload: { player: { codec: "pcm", sample_rate: 48000, channels: 2, bit_depth: 16 } } });
  c.send({ type: "server/state", payload: { metadata: { title: "Mock Song", artist: "Mock Artist", album: "Mock Album", progress: { track_progress: 1000, track_duration: 180000, playback_speed: 1000 } }, controller: { supported_commands: ["play", "pause", "next", "previous"] } } });
  c.send({ type: "group/update", payload: { playback_state: "playing", group_name: "Kitchen" } });
  // 20 s of a 440/660 Hz tone, 20 ms chunks, sent 300 ms ahead of their play time.
  const start = serverNowUs() + 400_000n;
  let phase = 0;
  for (let i = 0; i < 1000 && !c.closed; i++) {
    const frames = 960, pcm = Buffer.alloc(frames * 4);
    for (let f = 0; f < frames; f++, phase++) {
      pcm.writeInt16LE(Math.round(Math.sin((2 * Math.PI * 440 * phase) / 48000) * 9000), f * 4);
      pcm.writeInt16LE(Math.round(Math.sin((2 * Math.PI * 660 * phase) / 48000) * 9000), f * 4 + 2);
    }
    const at = start + BigInt(i * 20_000);
    while (serverNowUs() < at - 300_000n) await new Promise((r) => setTimeout(r, 5));
    c.audio(at, pcm);
    if (i === 150) { c.send({ type: "server/command", payload: { player: { command: "volume", volume: 42 } } }); log("sent volume 42"); }
    if (i === 300) {
      const hs = new Handshake(c.h, server, c.clientPk, psk);
      c.send({ type: "noise/handshake", payload: { data: b64u(hs.write1(enc.encode(JSON.stringify({ psk_id: pskId(psk) })))) } });
      const m2 = await c.expect("noise/handshake");
      hs.read2(unb64u(m2.json.payload.data));
      c.session = hs.split(); c.h = hs.h;
      c.send({ type: "server/activate", payload: { activities: ["playback"], active_roles: ["player@v1", "controller@v1", "metadata@v1"] } });
      log("RE-HANDSHAKE OK");
    }
  }
  c.send({ type: "stream/end", payload: {} });
  log("stream ended");
}

const httpServer = http.createServer((req, res) => { res.writeHead(404); res.end(); });
httpServer.on("upgrade", (req, socket) => {
  log("upgrade", req.url);
  if (!req.url.startsWith("/sendspin")) { socket.destroy(); return; }
  const n = ++connections;
  let c;
  const ws = wsAccept(req, socket, (d, b) => { try { c.push(d, b); } catch (e) { log("ERROR", e.message); ws.close(); } }, () => {
    if (!c.closed) { c.closed = true; for (const w of c.waiters.splice(0)) w(null); log("connection", n, "closed"); }
  });
  c = new Conn(ws);
  (async () => {
    if (!longTermPsk) { longTermPsk = await pairing(c); setTimeout(() => ws.close(), 300); }
    else await playback(c, longTermPsk);
  })().catch((e) => log("FAIL", n, e.message));
});
httpServer.listen(PORT, "0.0.0.0", () => log("listening on", PORT));
