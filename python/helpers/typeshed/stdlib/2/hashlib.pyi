# Stubs for hashlib (Python 2)

from typing import Tuple, Union

_DataType = Union[str, bytearray, buffer, memoryview]

class _hash(object):
    # This is not actually in the module namespace.
    digest_size = 0
    block_size = 0
    def update(self, arg: _DataType) -> None: ...
    def digest(self) -> str: ...
    def hexdigest(self) -> str: ...
    def copy(self) -> _hash: ...

def new(name: str, data: str = ...) -> _hash: ...

def md5(s: _DataType = ...) -> _hash: ...
def sha1(s: _DataType = ...) -> _hash: ...
def sha224(s: _DataType = ...) -> _hash: ...
def sha256(s: _DataType = ...) -> _hash: ...
def sha384(s: _DataType = ...) -> _hash: ...
def sha512(s: _DataType = ...) -> _hash: ...

algorithms = ...  # type: Tuple[str, ...]
algorithms_guaranteed = ...  # type: Tuple[str, ...]
algorithms_available = ...  # type: Tuple[str, ...]

def pbkdf2_hmac(name: str, password: str, salt: str, rounds: int, dklen: int = ...) -> str: ...
