"""Repeatable UI smoke test on the project's isolated emulator and auth fixture.

Requires scripts/fixture-server.py --auth and emulator-5580. Does not modify a real server.
"""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tooling/sdk/platform-tools/adb.exe'
OUT = ROOT / '.tooling/smoke'
OUT.mkdir(exist_ok=True)
ENV = dict(os.environ, ANDROID_USER_HOME=str(ROOT / '.tooling/android'))

def adb(*args):
    result = subprocess.run([str(ADB), '-s', 'emulator-5580', *args], env=ENV, capture_output=True, timeout=30)
    if result.returncode: raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout

def dump():
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/kikoeru-smoke.xml')
    if b'UI hierchary dumped' not in result:
        raise ET.ParseError('Activity is transitioning; do not reuse the previous XML')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/kikoeru-smoke.xml'))

def wait_node(predicate, timeout=20):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        try:
            found = next((n for n in dump().iter('node') if predicate(n)), None)
            if found is not None: return found
        except ET.ParseError: pass
        time.sleep(.5)
    raise AssertionError('Expected UI node not found')

def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def tap_text(text):
    tap_node(wait_node(lambda n: n.get('text') == text))

def fill(index, value):
    nodes = [n for n in dump().iter('node') if n.get('class') == 'android.widget.EditText']
    tap_node(nodes[index])
    # Ctrl+A is not reliably delivered by Android's shell input command to Compose.
    adb('shell', 'input', 'keyevent', '123')
    adb('shell', 'input', 'keyevent', *(['67'] * (len(nodes[index].get('text', '')) + 5)))
    adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', '4')

def session():
    text = adb('shell', 'dumpsys', 'media_session').decode('utf-8')
    section = text.split('package=app.kikoeru.android', 1)[-1].split('queueTitle=', 1)[0]
    match = re.search(r'state=PlaybackState \{state=(\w+)\(\d+\), position=(\d+),.*?speed=([\d.]+)', section)
    return (match[1], int(match[2]), float(match[3])) if match else None

def wait_state(expected, timeout=25):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        current = session()
        if current and current[0] == expected: return current
        time.sleep(.5)
    raise AssertionError(f'Expected {expected}, got {session()}')

def screenshot(name):
    (OUT / name).write_bytes(adb('exec-out', 'screencap', '-p'))

if __name__ == '__main__':
    adb('shell', 'input', 'keyevent', '127')
    adb('shell', 'am', 'force-stop', 'app.kikoeru.android')
    adb('shell', 'am', 'start', '-n', 'app.kikoeru.android/.MainActivity')
    time.sleep(2)
    wait_node(lambda n: n.get('text') == '音声库')
    tap_text('设置')
    tap_node(wait_node(lambda n: n.get('text', '').startswith('http://') and '服务器' not in n.get('text', '')))
    fill(0, 'http://10.0.2.2:18765')
    tap_text('连接并进入音声库')
    wait_node(lambda n: n.get('text') == '服务器需要登录，请填写用户名和密码')
    fill(1, 'listener')
    fill(2, 'test-password')
    tap_text('连接并进入音声库')
    time.sleep(2)
    tap_text('作品')
    wait_node(lambda n: n.get('text') == '2 部作品')
    print('PASS: authenticated login and server switch', flush=True)
    screenshot('library.png')
    tap_text('雨夜书房 · 测试音声')
    tap_text('播放全部')
    wait_state('PLAYING')
    pause = wait_node(lambda n: n.get('content-desc') == '暂停')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', pause.attrib['bounds']))
    adb('shell', 'input', 'tap', str(max(100, x1 - 240)), str((y1+y2)//2))
    tap_text('定时')
    tap_text('当前曲目结束时')
    # Wait for the 90-second fixture naturally; a tap during sheet dismissal can be lost.
    stopped = wait_state('PAUSED', 190)
    assert stopped[1] >= 85000, stopped
    print('PASS: stop at track end', flush=True)
    screenshot('player.png')
    tap_node(wait_node(lambda n: n.get('content-desc') == '播放'))
    wait_state('PLAYING')
    tap_text('定时')
    fill(0, '1')
    tap_text('设置')
    adb('shell', 'input', 'keyevent', '3')
    print('Checking one-minute sleep timer in background…', flush=True)
    deadline = time.monotonic() + 70
    while time.monotonic() < deadline:
        status = session()
        if status and status[0] == 'PAUSED': break
        time.sleep(1)
    else: raise AssertionError('Sleep timer did not pause playback')
    print('PASS: background sleep timer', flush=True)
    adb('shell', 'am', 'force-stop', 'app.kikoeru.android')
    adb('shell', 'am', 'start', '-n', 'app.kikoeru.android/.MainActivity')
    wait_node(lambda n: n.get('text') == '2 部作品')
    print('PASS: encrypted login persistence after process restart', flush=True)
