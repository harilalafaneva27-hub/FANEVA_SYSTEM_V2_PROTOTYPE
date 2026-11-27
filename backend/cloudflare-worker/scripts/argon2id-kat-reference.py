from argon2.low_level import Type, hash_secret_raw

EXPECTED_HEX = "5d2c6c66cf59f37a108b6da3d9f1d72d6a509729f80bffdcb195bd373c43b0bc"
password = bytes(range(32))
salt = bytes(range(16, 32))
actual = hash_secret_raw(
    secret=password,
    salt=salt,
    time_cost=2,
    memory_cost=19 * 1024,
    parallelism=1,
    hash_len=32,
    type=Type.ID,
    version=19,
)
actual_hex = actual.hex()
if actual_hex != EXPECTED_HEX:
    raise SystemExit(f"Independent libargon2 KAT mismatch: {actual_hex}")

print(
    "ARGON2ID_REFERENCE_KAT_RESULT "
    '{"status":"PASS","reference":"argon2-cffi 25.1.0 / libargon2",'
    '"version":19,"parallelism":1,"passes":2,"memorySizeKiB":19456,"tagLength":32}'
)
