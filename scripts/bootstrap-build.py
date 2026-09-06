"""Download pinned build tools into the ignored workspace directory. Python 3.12+."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tooling'
TOOLS.mkdir(exist_ok=True)

def download(url, name, checksum=None, algorithm='sha256'):
    target = TOOLS / name
    if not target.exists():
        print('Downloading', name, flush=True)
        with urllib.request.urlopen(url, timeout=120) as response, target.open('wb') as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
    if checksum and hashlib.new(algorithm, target.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Checksum mismatch: ' + name)
    return target

def gradle():
    url = 'https://services.gradle.org/distributions/gradle-8.13-bin.zip'
    sha = urllib.request.urlopen(url + '.sha256').read().decode().strip()
    archive = download(url, 'gradle-8.13-bin.zip', sha)
    if not (TOOLS / 'gradle-8.13').exists():
        with zipfile.ZipFile(archive) as z: z.extractall(TOOLS)

def java():
    url = 'https://api.github.com/repos/adoptium/temurin21-binaries/releases/tags/jdk-21.0.12.1%2B1'
    assets = json.load(urllib.request.urlopen(url))['assets']
    asset = next(a for a in assets if a['name'].startswith('OpenJDK21U-jdk_x64_windows_hotspot_') and a['name'].endswith('.zip'))
    sha_asset = next(a for a in assets if a['name'] == asset['name'] + '.sha256.txt')
    sha = urllib.request.urlopen(sha_asset['browser_download_url']).read().decode().split()[0]
    archive = download(asset['browser_download_url'], 'jdk21.zip', sha)
    dest = TOOLS / 'jdk'
    if not dest.exists():
        with zipfile.ZipFile(archive) as z: z.extractall(dest)

def platform():
    root = ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml').read())
    # Legacy repository XML repeats this path for extension SDKs. Select the base SDK archive.
    package = next(p for p in root if p.tag.endswith('remotePackage') and p.attrib.get('path') == 'platforms;android-36' and p.findtext('.//complete/url') == 'platform-36_r02.zip')
    complete = package.find('.//complete')
    url = complete.findtext('url')
    archive = download('https://dl.google.com/android/repository/' + url, 'platform-36_r02.zip', complete.findtext('checksum'), 'sha1')
    dest = TOOLS / 'sdk' / 'platforms'
    if not (dest / 'android-36').exists():
        with zipfile.ZipFile(archive) as z: z.extractall(dest)
        for child in dest.iterdir():
            if child.is_dir() and child.name != 'android-36': child.rename(dest / 'android-36')

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        for result in executor.map(lambda fn: fn(), [gradle, java, platform]): pass
    print('Build tools ready.', flush=True)
