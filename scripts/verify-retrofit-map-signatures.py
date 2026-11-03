#!/usr/bin/env python3
"""Verify reflected Retrofit map parameters in a release APK or AAB, after R8."""

import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile


def parameter_types(signature):
    """Split JVM parameter signatures without splitting nested generic arguments."""
    parameters = signature[signature.index('(') + 1:signature.index(')')]
    result, start, depth = [], 0, 0
    for index, char in enumerate(parameters):
        depth += (char == '<') - (char == '>')
        if depth == 0 and (char == ';' or (char in 'BCDFIJSZ' and parameters[start:index].strip('[') == '')):
            result.append(parameters[start:index + 1])
            start = index + 1
    return result


def verify_dump(path, annotations):
    signatures, annotated = {}, []
    method, parameter, parameters = None, None, False
    # Dex strings use modified UTF-8; unrelated string annotations can contain non-UTF-8 bytes.
    with path.open(encoding='utf-8', errors='backslashreplace') as lines:
        for line in lines:
            if line.startswith('Class #'):
                method = None
            match = re.match(r"Annotations on method #(\d+) '([^']+)'( parameters)?", line)
            if match:
                method, parameters = (match[1], match[2]), bool(match[3])
                parameter = None
            elif method and line.startswith('#'):
                parameter = int(line[1:])
            elif method and ' Ldalvik/annotation/Signature;' in line and not parameters:
                signatures[method] = ''.join(re.findall(r'"([^"]*)"', line))
            elif method and parameters and any(f' {annotation}' in line for annotation in annotations):
                annotated.append((method, parameter))
    for method, parameter in annotated:
        signature = signatures.get(method, '')
        types = parameter_types(signature) if '(' in signature and ')' in signature else []
        declared = types[parameter] if parameter is not None and parameter < len(types) else '<missing Signature>'
        if not re.fullmatch(r'Ljava/util/Map<.+>;', declared):
            raise ValueError(f'{path.name}: {method[1]} parameter #{parameter}: expected generic java.util.Map, found {declared}')
    return len(annotated)


def main():
    artifact, mapping = map(Path, sys.argv[1:])
    renamed = dict(re.findall(r'^(retrofit2\.http\.(?:HeaderMap|QueryMap|FieldMap|PartMap)) -> ([^:]+):$',
                              mapping.read_text(), re.MULTILINE))
    annotations = {'L' + renamed.get('retrofit2.http.' + name, 'retrofit2.http.' + name).replace('.', '/') + ';'
                   for name in ('HeaderMap', 'QueryMap', 'FieldMap', 'PartMap')}
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / '.local/android-sdk')
    dexdumps = sorted(sdk.glob('build-tools/*/dexdump'))
    if not dexdumps:
        raise ValueError(f'No build-tools dexdump under {sdk}')
    checked = 0
    with tempfile.TemporaryDirectory(prefix='retrofit-map-') as directory, zipfile.ZipFile(artifact) as archive:
        work = Path(directory)
        dex_entries = [name for name in archive.namelist() if re.fullmatch(r'(?:[^/]+/dex/)?classes\d*\.dex', name)]
        if not dex_entries:
            raise ValueError('Release artifact contains no dex files')
        for index, name in enumerate(dex_entries):
            dex = work / f'{index}.dex'
            dex.write_bytes(archive.read(name))
            dump = work / f'{index}.dump'
            with dump.open('w') as output:
                subprocess.run([str(dexdumps[-1]), '-a', str(dex)], stdout=output, check=True)
            checked += verify_dump(dump, annotations)
    if not checked:
        raise ValueError('Release artifact contains no Retrofit map-annotated parameters')
    print(f'OK: {checked} Retrofit map parameters carry generic java.util.Map in release dex: {artifact}')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(1)
