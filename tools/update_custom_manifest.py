#!/usr/bin/env python3
"""Create/verify the Git-distributable APK + wallpaper bundle. Standard library only."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / 'distribution'
PACKAGE = 'com.gathin.tvhome.terminal'


def manifest_data():
    images = sorted((BUNDLE / 'theme/wallpapers').iterdir())
    images = [p for p in images if p.is_file() and p.suffix.lower() in ('.png', '.jpg', '.jpeg', '.webp')]
    if not images or len(images) > 100:
        raise ValueError('Wallpaper collection must contain 1–100 images')
    for image in images:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+\.(png|jpg|jpeg|webp)', image.name, re.I) or '..' in image.name:
            raise ValueError('Unsupported wallpaper filename: ' + image.name)
        if not 0 < image.stat().st_size <= 10 * 1024 * 1024:
            raise ValueError('Wallpaper must be nonempty and at most 10 MB: ' + image.name)
    paths = [BUNDLE / 'apk/CleanYourTV.apk', BUNDLE / 'theme/config.json', *images]
    config = json.loads(paths[1].read_text(encoding='utf-8'))
    files = []
    for path in paths:
        data = path.read_bytes()
        files.append({'path': path.relative_to(BUNDLE).as_posix(), 'bytes': len(data),
                      'sha256': hashlib.sha256(data).hexdigest()})
    return {'format_version': 1, 'application_id': PACKAGE, 'wallpaper_count': len(images),
            'rotation_minutes': config['wallpaper_rotation_minutes'], 'order': config['wallpaper_order'],
            'cycle': 'all-images-before-repeat', 'mirror_horizontal': False, 'files': files}


def verify_bundle():
    saved = json.loads((BUNDLE / 'manifest.json').read_text(encoding='utf-8'))
    if saved != manifest_data():
        raise ValueError('Bundle checksum mismatch; run tools/update_custom_manifest.py after updating the APK/images')
    return saved


def main():
    manifest = manifest_data()
    (BUNDLE / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (BUNDLE / 'SHA256SUMS').write_text(''.join(f"{f['sha256']}  {f['path']}\n" for f in manifest['files']), encoding='utf-8')
    print(f"Bundle ready: {manifest['wallpaper_count']} wallpapers, {len(manifest['files'])} verified files")


if __name__ == '__main__':
    main()
