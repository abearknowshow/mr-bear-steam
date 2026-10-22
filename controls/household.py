"""Household and vendor settings for one install, kept out of the repository.

household.json (repo root, git-ignored) holds the values for your own panel; household.example.json
holds made-up values that every test runs against. See docs/SETUP.md for how to find yours.
"""
from pathlib import Path
import ipaddress, json, os, re, sys

REPO = Path(__file__).resolve().parents[1]
EXAMPLE = REPO / 'household.example.json'
OWN = REPO / 'household.json'
MAC = r'[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}'


def load(path=None, example=False):
    """The validated settings. Real builds need household.json; tests always use the example."""
    # BEAR_HOUSEHOLD points everything (servers included) at another file; verify.py uses it for the example.
    path = Path(path) if path else EXAMPLE if example else Path(os.environ.get('BEAR_HOUSEHOLD', OWN))
    if not path.exists():
        sys.exit(f'{path.name} is missing. Copy household.example.json to household.json and fill in your values (docs/SETUP.md).')
    h = json.loads(path.read_text())
    v = h['vendor_cloud']
    prefix = h['lan_prefix']
    if not re.fullmatch(r'(\d{1,3}\.){3}', prefix) or not ipaddress.ip_address(prefix + '1').is_private:
        raise ValueError('lan_prefix must be a private /24 prefix such as "192.168.1."')
    for key in ('mac_host', 'panel_host'):
        if not h[key].startswith(prefix) or not ipaddress.ip_address(h[key]).is_private:
            raise ValueError(f'{key} must be an address inside lan_prefix')
    if not re.fullmatch('SPA' + MAC, h['bridge_id']):
        raise ValueError('bridge_id looks like "SPA" followed by the bridge MAC address')
    if not re.fullmatch('su-' + MAC + '-dev', h['light_thing']):
        raise ValueError('light_thing looks like "su-<panel MAC>-dev"')
    if not re.fullmatch(r'[a-z]{2}-[a-z]+-\d', v['region']):
        raise ValueError('vendor_cloud.region looks like "us-east-2"')
    for key in ('user_pool_id', 'app_client_id', 'identity_pool_id', 'iot_endpoint', 'devices_url'):
        if not re.fullmatch(r'[A-Za-z0-9:_./-]{8,200}', v[key]):
            raise ValueError(f'vendor_cloud.{key} is missing or malformed')
    if not v['devices_url'].startswith('https://'):
        raise ValueError('vendor_cloud.devices_url must be https')
    return h


def java(h, package, out_dir):
    """Writes <out_dir>/<package path>/Household.java and returns its path."""
    v = h['vendor_cloud']
    fields = {'MAC_HOST': h['mac_host'], 'LAN_PREFIX': h['lan_prefix'], 'BRIDGE_ID': h['bridge_id'], 'LIGHT_THING': h['light_thing'],
              'REGION': v['region'], 'USER_POOL_ID': v['user_pool_id'], 'APP_CLIENT_ID': v['app_client_id'],
              'IDENTITY_POOL_ID': v['identity_pool_id'], 'IOT_ENDPOINT': v['iot_endpoint'], 'DEVICES_URL': v['devices_url']}
    body = ''.join(f' public static final String {k}={json.dumps(val)};\n' for k, val in fields.items())
    target = Path(out_dir) / package.replace('.', '/') / 'Household.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(f'package {package};\n/** Generated from household.json by controls/household.py. Do not edit or commit. */\n'
                      f'final class Household {{\n private Household(){{}}\n{body} public static final int RELAY_PORT=8778;\n}}\n')
    return target


def page(text, h):
    """Fills {{MAC_HOST}} in the served install pages."""
    return text.replace('{{MAC_HOST}}', h['mac_host'])
