;; The guest half of dev/resources/args/: it asks WASI how many arguments it
;; was given, which is the smallest observable consequence of `:args`.
(module
  (import "wasi:cli/environment@0.2.0" "get-arguments" (func $ga (param i32)))
  (memory (export "memory") 1)
  (global $next (mut i32) (i32.const 1024))
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32)
    (local $p i32)
    (local.set $p (global.get $next))
    (global.set $next (i32.add (local.get $p) (local.get 3)))
    (local.get $p))
  (func (export "argc") (result i32)
    (call $ga (i32.const 128))
    (i32.load offset=4 (i32.const 128))))
