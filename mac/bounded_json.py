"""Reject ambiguous and deeply nested JSON before recursive platform parsing."""
import json
import math

MAX_DEPTH = 32


def object_pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('Duplicate JSON field')
        value[key] = item
    return value


def finite_float(text):
    value = float(text)
    if not math.isfinite(value):
        raise ValueError('Non-finite JSON number')
    return value


def invalid_constant(_):
    raise ValueError('Invalid JSON constant')


def load_object(text):
    # Brackets inside quoted strings do not contribute to nesting. This pass
    # makes the parser's stack use bounded even for a sub-frame-limit payload.
    depth = 0
    quoted = escaped = False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in '[{':
            depth += 1
            if depth > MAX_DEPTH:
                raise ValueError('JSON nesting limit exceeded')
        elif char in ']}':
            depth -= 1
    value = json.loads(text, object_pairs_hook=object_pairs,
                       parse_constant=invalid_constant, parse_float=finite_float)
    if not isinstance(value, dict):
        raise ValueError('Expected a JSON object')
    return value
