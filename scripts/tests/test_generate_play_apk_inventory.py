import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[1] / 'generate_play_apk_inventory.py'
spec = importlib.util.spec_from_file_location('generate_play_apk_inventory', path)
try:
    inventory = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(inventory)
except FileNotFoundError:
    inventory = None

class PlayApkInventoryTest(unittest.TestCase):
    def test_play_notice_includes_bun_and_javascriptcore_license_texts(self):
        text = inventory.compose_play_notice('base', 'alpine', 'bun upstream text', 'jsc upstream text', 'lgpl21 upstream text')
        self.assertIn('bun upstream text', text)
        self.assertIn('jsc upstream text', text)
        self.assertIn('lgpl21 upstream text', text)
        self.assertIn('JavaScriptCore', text)

    def test_includes_base_and_cached_packages_with_exact_recipe_revisions(self):
        self.assertIsNotNone(inventory, 'Play Alpine inventory generator is required')
        package = {'package': 'bash', 'version': '5-r0', 'origin': 'bash', 'license': 'GPL-3.0-or-later',
                   'aportsCommit': 'a' * 40, 'repository': 'main'}
        base = {'name': 'apk-tools', 'version': '3-r0', 'origin': 'apk-tools', 'license': 'GPL-2.0-only',
                'aportsCommit': 'b' * 40, 'repository': 'main', 'abi': 'arm64-v8a'}
        result = inventory.render({'architectures': {'arm64-v8a': {'packages': [package]}}, 'basePackages': [base]})
        self.assertIn('bash 5-r0', result)
        self.assertIn('apk-tools 3-r0', result)
        self.assertIn('github.com/alpinelinux/aports/tree/' + 'a' * 40 + '/main/bash', result)
        self.assertIn('GPL-2.0-only', result)

if __name__ == '__main__':
    unittest.main()
