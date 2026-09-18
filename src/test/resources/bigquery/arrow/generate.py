# Generates the Arrow IPC fixtures for BigQueryArrowRowsTest with pyarrow, independently of the
# decoder under test. Run: python generate.py (pyarrow 25).
import datetime as dt, decimal, os, pyarrow as pa, pyarrow.ipc as ipc
out = os.path.dirname(os.path.abspath(__file__)) + "/"
schema = pa.schema([
    pa.field("name", pa.string(), nullable=False),
    pa.field("AGE", pa.int64()),
    pa.field("ts", pa.timestamp("us", tz="UTC")),
    pa.field("d", pa.date32()),
    pa.field("dt", pa.timestamp("us")),
    pa.field("n", pa.decimal128(38, 9)),
    pa.field("big", pa.decimal256(76, 38)),
    pa.field("tags", pa.list_(pa.string())),
    pa.field("addr", pa.struct([pa.field("city", pa.string())])),
    pa.field("f", pa.float32()),
    pa.field("ok", pa.bool_()),
    pa.field("raw", pa.binary()),
    pa.field("t", pa.time64("us")),
    pa.field("iv", pa.month_day_nano_interval()),
    pa.field("small", pa.uint8()),
])
utc = dt.timezone.utc
batch = pa.record_batch([
    pa.array(["ada", "bob", "cid"]),
    pa.array([36, None, -7], pa.int64()),
    pa.array([dt.datetime(2024, 1, 2, 3, 4, 5, 123456, tzinfo=utc), None, dt.datetime(1969, 12, 31, 23, 59, 59, 999999, tzinfo=utc)], pa.timestamp("us", tz="UTC")),
    pa.array([dt.date(2024, 1, 1), None, dt.date(1, 1, 1)]),
    pa.array([dt.datetime(2024, 1, 2, 3, 4, 5, 6), None, dt.datetime(2024, 1, 2, 3, 4, 5)], pa.timestamp("us")),
    pa.array([decimal.Decimal("12.5"), None, decimal.Decimal("-0.000000001")], pa.decimal128(38, 9)),
    pa.array([decimal.Decimal("1.25"), None, decimal.Decimal("-3")], pa.decimal256(76, 38)),
    pa.array([["a", "b"], None, []], pa.list_(pa.string())),
    pa.array([{"city": "London"}, None, {"city": None}], pa.struct([pa.field("city", pa.string())])),
    pa.array([1.5, None, -2.25], pa.float32()),
    pa.array([True, None, False]),
    pa.array([b"\x01\x02", None, b""]),
    pa.array([dt.time(1, 2, 3, 500000), None, dt.time(0, 0)], pa.time64("us")),
    pa.array([pa.MonthDayNano([14, 3, 3_723_500_000_000]), None, pa.MonthDayNano([-1, 0, 0])], pa.month_day_nano_interval()),
    pa.array([255, None, 0], pa.uint8()),
], schema=schema)
open(out + "schema.bin", "wb").write(schema.serialize().to_pybytes())
open(out + "batch.bin", "wb").write(batch.serialize().to_pybytes())
# A null in a REQUIRED column (row 1).
bad = pa.record_batch([pa.array(["ok", None]), *[pa.nulls(2, f.type) for f in list(schema)[1:]]],
                      schema=schema.set(0, pa.field("name", pa.string())))
open(out + "batch-null-name.bin", "wb").write(bad.serialize().to_pybytes())
# Nanosecond timestamps are not a supported TIMESTAMP encoding.
open(out + "schema-nanos.bin", "wb").write(pa.schema([pa.field("ts", pa.timestamp("ns", tz="UTC"))]).serialize().to_pybytes())
# An LZ4-compressed batch, taken from an IPC stream.
sink = pa.BufferOutputStream()
small = pa.record_batch([pa.array(["x"])], names=["name"])
with ipc.new_stream(sink, small.schema, options=ipc.IpcWriteOptions(compression="lz4")) as w:
    w.write_batch(small)
messages = list(ipc.MessageReader.open_stream(sink.getvalue()))
open(out + "schema-name.bin", "wb").write(messages[0].serialize().to_pybytes())
open(out + "batch-lz4.bin", "wb").write(messages[1].serialize().to_pybytes())
print("ok")
