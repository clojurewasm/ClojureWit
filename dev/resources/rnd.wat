;; The guest half of dev/resources/rnd/. It imports one WASI function, which is
;; the smallest thing that fails if `:wasi` is wired wrongly -- and wiring it
;; wrongly aborts the process rather than returning an error (`0017` E).
(module
  (import "wasi:random/random@0.2.0" "get-random-u64" (func $r (result i64)))
  (memory (export "memory") 1)
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (i32.const 0))
  (func (export "roll") (result i64) (call $r)))
