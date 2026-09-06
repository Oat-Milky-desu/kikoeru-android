"""Local, synthetic Kikoeru protocol fixture. Never use as a real media server.

Run: python scripts/fixture-server.py [--auth]
Android emulator URL: http://10.0.2.2:18765
Test-only credentials: listener / test-password
"""
import argparse
import io
import json
import math
import struct
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

WORKS = [
    dict(id=1001, title='雨夜书房 · 测试音声', circle=dict(id=1, name='测试社团'), vas=[dict(id='voice-1', name='测试声优')], tags=[dict(id=2, name='雨声'), dict(id=3, name='放松')], release='2026-09-01', nsfw=False),
    dict(id=1002, title='森林午后 · 测试音声', circle=dict(id=1, name='测试社团'), vas=[], tags=[dict(id=3, name='放松')], release='2026-08-20', nsfw=False),
]
buffer = io.BytesIO()
with wave.open(buffer, 'wb') as audio:
    audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(16000)
    # Very quiet synthetic tone, not copyrighted audio.
    audio.writeframes(b''.join(struct.pack('<h', int(80 * math.sin(2 * math.pi * 220 * i / 16000))) for i in range(16000 * 90)))
WAV = buffer.getvalue()
# A valid patterned PNG makes successful image decoding and privacy blur observable.
import zlib
def png_chunk(kind, data):
    return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
raw=b''.join(b'\0'+b''.join(bytes((45 if (x//16+y//16)%2 else 220, 100+x//2, 80+y//2)) for x in range(256)) for y in range(256))
PNG=b'\x89PNG\r\n\x1a\n'+png_chunk(b'IHDR',struct.pack('>2I5B',256,256,8,2,0,0,0))+png_chunk(b'IDAT',zlib.compress(raw))+png_chunk(b'IEND',b'')


class Handler(BaseHTTPRequestHandler):
    def json(self, data, status=200):
        content = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status); self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(content))); self.end_headers(); self.wfile.write(content)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        if self.path == '/api/auth/me' and body == {'name': 'listener', 'password': 'test-password'}:
            self.json({'token': 'fixture-token'})
        else: self.json({'error': 'invalid credentials'}, 401)

    def do_GET(self):
        parsed = urlparse(self.path); path = parsed.path; query = parse_qs(parsed.query)
        if AUTH and self.headers.get('Authorization') != 'Bearer fixture-token':
            return self.json({'error': 'login required'}, 401)
        if path == '/api/auth/me': return self.json({'auth': AUTH, 'user': {'name': 'listener' if AUTH else 'admin', 'group': 'user'}})
        if path == '/api/lyric/translate':
            return self.json({'tasks': [dict(id=101, work_id=1001, audio_path='本篇/01. 雨声.wav', status=3), dict(id=102, work_id=1001, audio_path='本篇/02. 轻声.wav', status=2), dict(id=103, work_id=1001, audio_path='附录/01. 雨声.wav', status=3)]})
        if path == '/api/lyric/translate/lrc':
            return self.json({'lrcContent': '[00:00.00]AI 测试第一句\n[00:05.00]AI 测试第二句\n[00:10.00]AI 测试第三句\n[01:30.00]'})
        if path in ['/api/works', '/api/search'] or path.endswith('/works'):
            works = [w for w in WORKS if query.get('keyword', [''])[0] in w['title']]
            return self.json({'works': works, 'pagination': {'currentPage': 1, 'pageSize': 12, 'totalCount': len(works)}})
        if path.startswith('/api/work/'):
            work = next((w for w in WORKS if str(w['id']) == path.split('/')[-1]), None)
            return self.json(work, 200 if work else 404)
        if path.startswith('/api/tracks/'):
            wid = path.split('/')[-1]
            def track(i, title): return dict(type='audio', title=title, hash=f'{wid}/{i}', duration=90, mediaStreamUrl=f'/api/media/stream/{wid}/{i}')
            return self.json([dict(type='folder', title='本篇', children=[track(0, '01. 雨声.wav'), track(1, '02. 轻声.wav'), dict(type='text', title='01. 雨声.lrc', mediaStreamUrl=f'/api/media/stream/{wid}/lrc'), dict(type='text', title='02. 轻声.srt', mediaStreamUrl=f'/api/media/stream/{wid}/srt')]), dict(type='folder', title='附录', children=[track(2, '01. 雨声.wav')]), dict(type='image', title='cover.jpg')])
        if path.startswith('/api/media/stream/') and path.split('/')[-1] in ['lrc', 'srt']:
            content = ('[00:00.00]本地测试第一句\n[00:05.00]本地测试第二句\n[00:10.00]本地测试第三句\n[01:30.00]' if path.endswith('/lrc') else '1\n00:00:00,000 --> 00:00:04,000\nSRT 测试第一句\n双行字幕\n\n2\n00:00:08,000 --> 00:00:15,000\nSRT 测试第二句').encode('utf-8')
            self.send_response(200); self.send_header('Content-Type','text/plain; charset=utf-8'); self.send_header('Content-Length',str(len(content))); self.end_headers(); self.wfile.write(content); return
        if path.startswith('/api/media/stream/'):
            start, end = 0, len(WAV) - 1
            requested = self.headers.get('Range')
            if requested:
                span = requested.removeprefix('bytes=').split('-')
                start = int(span[0] or 0); end = min(int(span[1]), end) if len(span) > 1 and span[1] else end
            if start >= len(WAV):
                self.send_response(416); self.send_header('Content-Range', f'bytes */{len(WAV)}'); self.end_headers(); return
            self.send_response(206 if requested else 200)
            self.send_header('Content-Type', 'audio/wav'); self.send_header('Accept-Ranges', 'bytes')
            if requested: self.send_header('Content-Range', f'bytes {start}-{end}/{len(WAV)}')
            self.send_header('Content-Length', str(end-start+1)); self.end_headers()
            try: self.wfile.write(WAV[start:end+1])
            except (BrokenPipeError, ConnectionResetError): pass
            return
        if path.startswith('/api/cover/'):
            if query.get('type', ['main'])[0] != 'main':
                return self.json({'error': 'unsupported cover type'}, 400)
            self.send_response(200); self.send_header('Content-Type', 'image/png'); self.send_header('Content-Length', str(len(PNG))); self.end_headers(); self.wfile.write(PNG); return
        self.json({'error': 'not found'}, 404)

    def log_message(self, fmt, *args):
        # Do not log headers or request bodies, even in fixtures.
        print(fmt % args, flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--auth', action='store_true'); args = parser.parse_args(); AUTH = args.auth
    print('Synthetic fixture listening on 127.0.0.1:18765', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 18765), Handler).serve_forever()
