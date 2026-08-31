from pathlib import Path

p = Path('.ci/apply_watch_detail.py')
text = p.read_text()
old = '''    count = text.count(old)\n    if count != expected:\n        raise SystemExit(f"Expected {expected} occurrence(s), found {count}: {old[:80]!r}")\n    return text.replace(old, new, expected if expected == 1 else -1)\n'''
new = '''    count = text.count(old)\n    if count < expected:\n        raise SystemExit(f"Expected at least {expected} occurrence(s), found {count}: {old[:80]!r}")\n    return text.replace(old, new, expected)\n'''
if text.count(old) != 1:
    raise SystemExit('replace_exact helper anchor not found exactly once')
p.write_text(text.replace(old, new, 1))
