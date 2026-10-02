#!/usr/bin/env python3
"""Create private local service credentials without overwriting an existing file."""
import argparse
import os
from pathlib import Path
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=Path('.env.service'))
args = parser.parse_args()
try:
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    print(f'{args.output}: existing credentials preserved')
else:
    with os.fdopen(descriptor, 'w') as file:
        file.write(f'CONCERT_DB_PASSWORD={secrets.token_hex(24)}\n')
        file.write(f'CONCERT_JWT_SECRET={secrets.token_hex(32)}\n')
    print(f'{args.output}: private credentials created; do not commit this file')
