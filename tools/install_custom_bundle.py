#!/usr/bin/env python3
"""Install this custom APK/theme with the standard Android SDK adb. No extra Python packages."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

# Also support isolated/portable Python distributions that omit the script directory.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from update_custom_manifest import BUNDLE, PACKAGE, verify_bundle

COMPONENT = PACKAGE + '/com.gathin.tvhome.MainActivity'
URI = 'content://' + PACKAGE + '.theme/'
LEGACY = '/sdcard/Android/data/' + PACKAGE + '/files/'


def find_adb(explicit):
    if explicit:
        return explicit
    found = shutil.which('adb')
    if found:
        return found
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        sdk = os.environ.get(key)
        if sdk:
            candidate = Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
            if candidate.is_file():
                return str(candidate)
    raise ValueError('adb not found; install Android SDK Platform-Tools or pass --adb PATH')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='adb serial, for example an authorized IP:port')
    parser.add_argument('--adb', help='path to adb executable')
    parser.add_argument('--dry-run', action='store_true', help='verify local files and show the plan; do not access a TV')
    args = parser.parse_args()
    manifest = verify_bundle()
    print(f"Verified APK + {manifest['wallpaper_count']} wallpapers; package {PACKAGE}")
    print('Plan: update APK without clearing app data, replace this app\'s wallpaper collection, apply config, reopen Home')
    if args.dry_run:
        return
    adb = [find_adb(args.adb)] + (['-s', args.serial] if args.serial else [])

    def call(*command, allow_failure=False):
        result = subprocess.run([*adb, *command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding='utf-8', errors='replace', timeout=120)
        if result.returncode and not allow_failure:
            raise RuntimeError(result.stdout.strip() or 'adb command failed')
        return result.stdout.strip()

    def shell(command):
        out = call('shell', command)
        if re.search(r'(?mi)^\s*(Error:|Exception|Security exception|Permission Denial|\[ERROR\])', out):
            raise RuntimeError(out)
        return out

    call('get-state')
    sdk = int(shell('getprop ro.build.version.sdk'))
    if sdk < 26:
        raise ValueError('This launcher requires Android 8.0 / API 26 or newer')
    # Fail on incompatible signatures; never uninstall or clear data to bypass a failed update.
    result = call('install', '-r', str(BUNDLE / 'apk/CleanYourTV.apk'))
    if 'Success' not in result:
        raise RuntimeError(result)
    print(result)
    modern = sdk >= 29
    if modern:
        shell('content delete --uri ' + URI + 'wallpapers')
    else:
        shell('am start -n ' + COMPONENT)
        shell('mkdir -p ' + LEGACY + 'wallpapers')
        shell('rm -f ' + ' '.join(LEGACY + 'wallpapers/' + pattern for pattern in
                                  ('*.[pP][nN][gG]', '*.[jJ][pP][gG]', '*.[jJ][pP][eE][gG]', '*.[wW][eE][bB][pP]')))
    uploads = [f for f in manifest['files'] if f['path'].startswith('theme/wallpapers/')]
    uploads += [f for f in manifest['files'] if f['path'] == 'theme/config.json']
    for entry in uploads:
        relative = entry['path'].removeprefix('theme/')
        stage = '/data/local/tmp/cytv-' + uuid.uuid4().hex
        try:
            call('push', str(BUNDLE / entry['path']), stage)
            if modern:
                shell(f'content write --uri {URI}{relative} < {stage}')
                digest = shell(f'content read --uri {URI}{relative} | sha256sum')
            else:
                shell(f'cp {stage} {LEGACY}{relative}')
                digest = shell(f'sha256sum {LEGACY}{relative}')
            parts = digest.split()
            if not parts or parts[0] != entry['sha256']:
                raise RuntimeError('Device checksum mismatch: ' + relative)
        finally:
            call('shell', 'rm -f ' + stage, allow_failure=True)
        print('Verified on TV: ' + relative)
    # Recreate the Activity after the complete theme is present, without force-stopping key-helper services.
    print(shell('am start -f 0x10008000 -n ' + COMPONENT))
    print('Installed. The system default Home was not changed. Enable Spotify Now Playing in Notification Access if needed.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        print('Install failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
