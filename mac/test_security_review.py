import io
import json
from pathlib import Path
import tempfile
import unittest

from bounded_json import load_object
from macbridge import read_frame
from transfer_state import TransferState


class SecurityReviewTests(unittest.TestCase):
    def test_deep_frames_fail_before_recursive_parsing(self):
        for depth in (33, 4000):
            payload = '{"extra":' + '[' * depth + '0' + ']' * depth + '}'
            with self.subTest(depth=depth), self.assertRaises(ValueError):
                read_frame(io.BytesIO(payload.encode() + b'\n'))

    def test_duplicate_fields_and_nonfinite_numbers_are_rejected(self):
        for payload in ('{"type":"CLIPBOARD","type":"HEARTBEAT"}',
                        '{"nested":{"allowed":false,"allowed":true}}',
                        '{"size":NaN}', '{"size":Infinity}', '{"size":1e400}',
                        '{"type":"HEARTBEAT"} trailing', '[]'):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                read_frame(io.BytesIO(payload.encode() + b'\n'))

    def test_unicode_escaped_quotes_and_brackets_inside_text_are_not_depth(self):
        message = dict(type='CLIPBOARD', content='世界 🌉 ' + '[{\\"' * 1000,
                       sourceDevice='Phone', mimeType='text/plain')
        self.assertEqual(message, read_frame(io.BytesIO(json.dumps(message).encode() + b'\n')))
        self.assertEqual({'a': [0]}, load_object('{"a":[0]}'))
        self.assertEqual({'a': 1.5}, load_object('{"a":1.5}'))

    def test_corrupt_checkpoint_depth_duplicates_and_constants_recover_empty(self):
        with tempfile.TemporaryDirectory() as temp:
            state = TransferState(Path(temp) / 'checkpoint.json', ['fixture'])
            for payload in ('{"version":1,"version":2,"identity":["fixture"]}',
                            '{"version":1,"identity":["fixture"],"expires":NaN}',
                            '{"extra":' + '[' * 4000 + '0' + ']' * 4000 + '}'):
                state.path.write_text(payload)
                self.assertEqual({}, state.load())
            state.save(incoming=None, receipts=[])
            self.assertEqual(1, state.load()['version'])
