#!/usr/bin/env python3
"""Isolated generated-media browser test. Never contacts an iPhone or reads identity files.

Run with: python3 desktop/tests/browser_fixture.py
Open http://127.0.0.1:8791 in the browser, inspect the test bars, then stop with Ctrl+C.
Requires ffmpeg with libx264. This is test tooling, not a receiver transport.
"""
import base64
import hashlib
import json
import pathlib
import socketserver
import struct
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
raw = subprocess.check_output([
    "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15",
    "-frames:v", "30", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
    "-profile:v", "baseline", "-x264-params", "aud=1:keyint=15:repeat-headers=1", "-f", "h264", "pipe:1",
])

def nal_units(data):
    import re
    starts = list(re.finditer(b"\x00\x00\x00?\x01", data))
    return [data[match.end():starts[index+1].start() if index+1<len(starts) else len(data)] for index,match in enumerate(starts)]

nalus = nal_units(raw)
sps = next(n for n in nalus if n[0] & 31 == 7)
pps = next(n for n in nalus if n[0] & 31 == 8)
avcc = bytes([1,sps[1],sps[2],sps[3],255,225]) + struct.pack(">H",len(sps))+sps+bytes([1])+struct.pack(">H",len(pps))+pps
frames, frame = [], bytearray()
for nalu in nalus:
    if nalu[0] & 31 == 9 and frame:
        frames.append(bytes(frame)); frame.clear()
    frame.extend(b"\x00\x00\x00\x01"+nalu)
if frame:
    frames.append(bytes(frame))
adts = subprocess.check_output(["ffmpeg","-hide_banner","-loglevel","error","-f","lavfi","-i","sine=frequency=440:sample_rate=48000:duration=2",
    "-c:a","aac","-b:a","64k","-f","adts","pipe:1"])
aac_packets=[]
offset=0
while offset+7<=len(adts):
    length=((adts[offset+3]&3)<<11)|(adts[offset+4]<<3)|(adts[offset+5]>>5)
    aac_packets.append(adts[offset:offset+length]);offset+=length
ogg = subprocess.check_output(["ffmpeg","-hide_banner","-loglevel","error","-f","lavfi","-i","sine=frequency=660:sample_rate=48000:duration=2",
    "-c:a","libopus","-b:a","64k","-f","ogg","pipe:1"])
opus_packets=[];offset=0;packet=bytearray()
while offset<len(ogg):
    segments=ogg[offset+26];lengths=ogg[offset+27:offset+27+segments];body=offset+27+segments
    for length in lengths:
        packet.extend(ogg[body:body+length]);body+=length
        if length<255:
            if not packet.startswith((b"OpusHead",b"OpusTags")):opus_packets.append(bytes(packet))
            packet.clear()
    offset=body
pcm = subprocess.check_output(["ffmpeg","-hide_banner","-loglevel","error","-f","lavfi","-i","sine=frequency=880:sample_rate=48000:duration=2",
    "-f","s16be","pipe:1"])
status = {"event":"status","phase":"fixture","message":"Generated H.264 test bars — no iPhone connection.",
          "identityAvailable":False,"startedAt":0,"frames":0,"audioPackets":0}
results = {"rendered":False,"clientActions":[]}

class Http(BaseHTTPRequestHandler):
    def do_GET(self):
        name = self.path.split("?")[0]
        if name == "/api/bootstrap":
            value = {"token":"fixture","mediaPort":8792,"config":{"bluetoothAddress":"","ssid":"","channel":0,"fps":30,"width":320,"height":180,"microphone":False,"passwordSaved":False},
                     "networks":[],"status":status,"logs":[{"time":"2026-10-01T00:00:00Z","message":"GENERATED MEDIA TEST. This page does not connect to an iPhone."}]}
            content=json.dumps(value).encode(); mime="application/json"
        elif name == "/api/status":
            content=json.dumps(status).encode(); mime="application/json"
        elif name == "/results":
            content=json.dumps(results).encode(); mime="application/json"
        elif name == "/audio-test.js":
            content=b"""import {unlockAudio,mediaStats} from '/app.js';
document.getElementById('audio-test').onclick=async()=>{await unlockAudio();document.getElementById('audio-test-result').textContent='Audio enabled. Waiting for decoded samples...';};
setInterval(()=>{if(mediaStats.audioBuffers)document.getElementById('audio-test-result').textContent=`PASS: ${mediaStats.audioBuffers} decoded audio buffers scheduled`;},500);"""
            mime="text/javascript"
        elif name in ("/","/app.js","/app.css","/microphone.js"):
            filename="index.html" if name=="/" else name[1:]
            content=(ROOT/"web"/filename).read_bytes()
            if filename=="index.html":
                content=content.replace(b"CarPlay, within reach.",b"Generated media test.")
                content=content.replace(b"<main>",b"<main><button id='audio-test'>Enable generated audio test</button><p id='audio-test-result'>Audio test waiting for user gesture</p>")
                content=content.replace(b"</head>",b"<script type='module' src='/audio-test.js'></script></head>")
            mime={"html":"text/html","js":"text/javascript","css":"text/css"}[filename.split(".")[-1]]
        else:
            self.send_error(404); return
        self.send_response(200); self.send_header("Content-Type",mime); self.send_header("Content-Length",str(len(content))); self.end_headers(); self.wfile.write(content)
    def log_message(self, *_): pass

class Media(socketserver.StreamRequestHandler):
    def handle(self):
        first=self.rfile.readline()
        headers={}
        while True:
            line=self.rfile.readline()
            if line in (b"\r\n",b""):break
            key,value=line.decode().split(":",1);headers[key.lower()]=value.strip()
        if b"/media?token=fixture " not in first:return
        key=headers["sec-websocket-key"]
        accept=base64.b64encode(hashlib.sha1((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.wfile.write(f"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n".encode()); self.wfile.flush()
        lock=threading.Lock()
        def send(data,opcode=1):
            length=len(data); header=bytes([0x80|opcode,length]) if length<126 else bytes([0x80|opcode,126])+struct.pack(">H",length) if length<65536 else bytes([0x80|opcode,127])+struct.pack(">Q",length)
            with lock:self.wfile.write(header+data);self.wfile.flush()
        def read_actions():
            try:
                while True:
                    head=self.rfile.read(2)
                    if len(head)!=2:return
                    opcode=head[0]&15; length=head[1]&127
                    if length==126:length=struct.unpack(">H",self.rfile.read(2))[0]
                    elif length==127:length=struct.unpack(">Q",self.rfile.read(8))[0]
                    mask=self.rfile.read(4) if head[1]&128 else None
                    data=self.rfile.read(length)
                    if mask:data=bytes(value ^ mask[i%4] for i,value in enumerate(data))
                    if opcode==8:return
                    if opcode==9:send(data,10)
                    if opcode==1:
                        value=json.loads(data);results["clientActions"].append(value)
                        if value.get("action")=="rendered":results["rendered"]=True
            except (OSError,ValueError):pass
        threading.Thread(target=read_actions,daemon=True).start()
        def audio():
            try:
                time.sleep(5)
                for codec,packets,samples in [("AAC_LC",aac_packets,1024),("OPUS",opus_packets,960),("LPCM",[pcm[i:i+1920] for i in range(0,len(pcm),1920)],960)]:
                    send(json.dumps({"event":"audioConfig","stream":110,"codec":codec,"sampleRate":48000,"channels":1}).encode())
                    for index,packet in enumerate(packets):
                        send(struct.pack(">BIQ",3,110,index*samples)+packet,2);time.sleep(samples/48000)
                    send(json.dumps({"event":"log","message":f"{codec} generated audio packets sent","time":"2026-10-01T00:00:00Z"}).encode());time.sleep(1)
            except OSError:pass
        threading.Thread(target=audio,daemon=True).start()
        try:
            send(json.dumps(status).encode())
            send(json.dumps({"event":"videoConfig","stream":110,"config":base64.b64encode(avcc).decode()}).encode())
            for _ in range(10):
                for frame in frames:
                    send(struct.pack(">BIQ",2,110,time.monotonic_ns()//1000)+frame,2);time.sleep(1/15)
        except OSError:pass

class MediaServer(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True

if __name__ == "__main__":
    media=MediaServer(("127.0.0.1",8792),Media)
    threading.Thread(target=media.serve_forever,daemon=True).start()
    print("Generated-media browser test: http://127.0.0.1:8791",flush=True)
    try:ThreadingHTTPServer(("127.0.0.1",8791),Http).serve_forever()
    finally:media.shutdown();media.server_close()
