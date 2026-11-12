# open62541 bridge

This directory contains the MPL-2.0 open62541 adapter used by `SdnCustom.Protocols`.
The managed client deliberately fails with an actionable error when the native library is absent.

Build prerequisites:

- CMake 3.20+
- A C99 compiler
- open62541 1.4.x with `UA_ENABLE_AMALGAMATION=OFF`

```bash
cmake -S . -B build -DCMAKE_PREFIX_PATH=/path/to/open62541
cmake --build build --config Release
```

Copy the resulting library beside `SdnCustom.Client.App`:

- Windows: `sdncustom_opcua.dll`
- Linux: `libsdncustom_opcua.so`
