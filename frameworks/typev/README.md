# typev

Type-V is a bytecode-interpreting concurrent VM, using io_uring socket
I/O. The benchmark server is written in Type-C (typev's own language) and
compiled ahead of time to bytecode that the VM runs.

- **Tier:** tuned
- **Profile:** pipelined

## Layout

| Path | Description |
|------|-------------|
| `Dockerfile` | Downloads the prebuilt typev VM, runs the server |
| `meta.json` | Framework metadata |
| `bundle/output.tvbc` | The compiled benchmark server |
| `bundle/benchmark-code/` | Benchmark source — Type-C `main.tc` plus the `std.io` / `std.socket` modules it uses |

The typev VM itself (binary + FFI plugins) is not vendored here — the Dockerfile
fetches it from object storage at build time. Only the compiled benchmark and
its source live in this directory.
