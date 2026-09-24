# SQLCipher ctypes setup adapted from acmerfight/wechat-local MIT wrapper.
# See ADAPTER_LICENSES.md; native SQLite provides WAL transaction visibility.
"""SQLCipher 4 read-only connections to encrypted private snapshots.

Uses SQLite's native WAL checksums/commit visibility and SQLCipher page HMACs.
No manual page/WAL decryption and no plaintext database export.
"""
import ctypes as C
import ctypes.util
import os
from pathlib import Path
import re


class Rows:
    def __init__(self, rows): self.rows = rows
    def fetchall(self): return self.rows
    def fetchone(self): return self.rows[0] if self.rows else None


class ReadonlyCipher:
    def __init__(self, path, key):
        if not re.fullmatch('[0-9a-fA-F]{64}', key):
            raise ValueError('invalid encryption key')
        choices = [os.environ.get('SQLCIPHER_LIBRARY'), '/opt/homebrew/opt/sqlcipher/lib/libsqlcipher.dylib', '/usr/local/opt/sqlcipher/lib/libsqlcipher.dylib', ctypes.util.find_library('sqlcipher')]
        name = next((p for p in choices if p and (not p.startswith('/') or Path(p).is_file())), None)
        if not name: raise RuntimeError('SQLCipher library unavailable')
        self.lib = C.CDLL(name)
        self.ptr = C.c_void_p()
        self.lib.sqlite3_open_v2.argtypes = [C.c_char_p, C.POINTER(C.c_void_p), C.c_int, C.c_char_p]
        self.lib.sqlite3_key.argtypes = [C.c_void_p, C.c_void_p, C.c_int]
        self.lib.sqlite3_exec.argtypes = [C.c_void_p, C.c_char_p, C.c_void_p, C.c_void_p, C.c_void_p]
        self.lib.sqlite3_close.argtypes = [C.c_void_p]
        self.lib.sqlite3_busy_timeout.argtypes = [C.c_void_p, C.c_int]
        try:
            if self.lib.sqlite3_open_v2(os.fsencode(path), C.byref(self.ptr), 1, None):
                raise RuntimeError('database open failed')
            raw = ("x'" + key + "'").encode('ascii')
            if self.lib.sqlite3_key(self.ptr, raw, len(raw)): raise RuntimeError('key setup failed')
            self.lib.sqlite3_busy_timeout(self.ptr, 2000)
            self.execute('PRAGMA cipher_compatibility=4')
            self.execute('PRAGMA query_only=ON')
            self.execute('SELECT count(*) FROM sqlite_master')
        except BaseException:
            self.close()
            raise
    def execute(self, sql, params=()):
        # Parameters are used only in fixed internal queries; quote values, never identifiers.
        parts = sql.split('?')
        if len(parts) != len(params) + 1: raise ValueError('parameter mismatch')
        cooked = parts[0]
        for param, tail in zip(params, parts[1:]):
            if param is None: value = 'NULL'
            elif isinstance(param, int): value = str(param)
            elif isinstance(param, str) and '\x00' not in param: value = "'" + param.replace("'", "''") + "'"
            else: raise ValueError('unsupported parameter')
            cooked += value + tail
        rows = []
        callback_type = C.CFUNCTYPE(C.c_int, C.c_void_p, C.c_int, C.POINTER(C.c_char_p), C.POINTER(C.c_char_p))
        def collect(_, count, values, names):
            rows.append(tuple(values[i].decode('utf-8', 'strict') if values[i] is not None else None for i in range(count)))
            return 0
        callback = callback_type(collect)
        rc = self.lib.sqlite3_exec(self.ptr, cooked.encode(), callback, None, None)
        if rc: raise RuntimeError(f'database query failed ({rc})')
        return Rows(rows)
    def close(self):
        if self.ptr:
            self.lib.sqlite3_close(self.ptr)
            self.ptr = C.c_void_p()
    def __enter__(self): return self
    def __exit__(self, *args): self.close()
