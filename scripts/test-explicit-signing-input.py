import pathlib
import unittest

class ExplicitSigningInputTest(unittest.TestCase):
    def test_build_uses_only_explicit_mobile_input(self):
        source = (pathlib.Path(__file__).resolve().parents[1] / 'app/build.gradle').read_text()
        self.assertNotIn('readDockerEnvValue', source)
        self.assertNotIn('readGeneratedIosConfigValue', source)
        self.assertIn("environmentVariable('MOBILE_SIGNING_SECRET')", source)
        self.assertIn("findProperty('mobileSigningSecret')", source)
        self.assertIn('requiresMobileSigningSecret && mobileSigningSecret.isBlank()', source)
        self.assertIn('requiresResearchArtifact ||', source)

if __name__ == '__main__':
    unittest.main()
