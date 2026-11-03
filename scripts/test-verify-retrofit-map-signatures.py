#!/usr/bin/env python3
"""Regression checks for the release dex annotation reader."""

from pathlib import Path
import runpy
import tempfile
import unittest


CHECK = runpy.run_path(str(Path(__file__).with_name('verify-retrofit-map-signatures.py')))
MAP = 'Ljava/util/Map<Ljava/lang/String;Ljava/lang/String;>;'
ANNOTATIONS = {f'Lretrofit2/http/{name};' for name in ('HeaderMap', 'QueryMap', 'FieldMap', 'PartMap')}


class RetrofitMapSignatureTest(unittest.TestCase):
    def verify(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'classes.dump'
            path.write_bytes(text.encode() + b'\xc0\x80\n')
            return CHECK['verify_dump'](path, ANNOTATIONS)

    def dump(self, signature, parameter=0, annotation='Lretrofit2/http/HeaderMap;'):
        return ("Class #1 annotations:\nAnnotations on method #7 'upload'\n"
                f'  VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={{ "{signature}" }}\n'
                "Annotations on method #7 'upload' parameters\n"
                f'#{parameter}\n  VISIBILITY_RUNTIME {annotation}\nClass #1            -\n')

    def test_nested_generic_and_array_parameters_keep_their_positions(self):
        nested = 'Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Ljava/lang/String;>;>;'
        self.assertEqual(['[I', nested, '[Ljava/lang/String;', 'Z'],
                         CHECK['parameter_types'](f'([I{nested}[Ljava/lang/String;Z)V'))
        self.assertEqual(1, self.verify(self.dump(f'([I{nested}Z)V', parameter=1)))

    def test_all_four_retrofit_annotations_require_generic_map(self):
        for annotation in ANNOTATIONS:
            with self.subTest(annotation=annotation):
                self.assertEqual(1, self.verify(self.dump(f'({MAP})V', annotation=annotation)))
                for raw in ('Ljava/util/Map;', 'Lexample/CollectedUploadHeaders;'):
                    with self.assertRaisesRegex(ValueError, 'expected generic java.util.Map'):
                        self.verify(self.dump(f'({raw})V', annotation=annotation))

    def test_missing_method_signature_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'missing Signature'):
            self.verify(self.dump(''))

    def test_a_map_on_another_parameter_does_not_hide_the_defect(self):
        with self.assertRaisesRegex(ValueError, 'Ljava/lang/String;'):
            self.verify(self.dump(f'(Ljava/lang/String;{MAP})V'))


if __name__ == '__main__':
    unittest.main()
