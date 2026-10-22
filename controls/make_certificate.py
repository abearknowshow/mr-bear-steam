"""Creates the Mac's TLS key and the certificate both apps pin (sign-in handoff, timeline, Remote Support).

The certificate names your Mac's address from household.json and 127.0.0.1. Both files are git-ignored.
Replacing the certificate means rebuilding and reinstalling both apps, so an existing one is kept unless --force.
"""
from pathlib import Path
import shutil, subprocess, sys, tempfile
import household

ROOT = Path(__file__).resolve().parent
KEY, CERT = ROOT / 'private/setup-server.key', ROOT / 'assets/setup-server.crt'
SUPPORT_CERT = ROOT.parent / 'remote-support/assets/setup-server.crt'


def main():
    h = household.load()
    if CERT.exists() and '--force' not in sys.argv:
        sys.exit(f'{CERT} already exists. Installed apps pin it; pass --force only if you will rebuild and reinstall both apps.')
    KEY.parent.mkdir(mode=0o700, exist_ok=True)
    config = ('[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=Bear Steam local setup\n[ext]\n'
              f'subjectAltName=IP:{h["mac_host"]},IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\n'
              'keyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
    with tempfile.NamedTemporaryFile('w', suffix='.cnf') as cnf:
        cnf.write(config); cnf.flush()
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '1825', '-config', cnf.name,
                        '-keyout', KEY, '-out', CERT], check=True, capture_output=True)
    KEY.chmod(0o600)
    SUPPORT_CERT.parent.mkdir(exist_ok=True)
    shutil.copyfile(CERT, SUPPORT_CERT)
    print(f'Created {KEY.relative_to(ROOT.parent)} and the pinned certificate in both apps\' assets.')


if __name__ == '__main__':
    main()
