;; The guest half of dev/resources/nres.wit. It only *declares* the import —
;; the point is what instantiate does with the signature, so `poof` is never
;; called. list<own<token>> lowers to (ptr, len).
(module
  (import "local:nres/host@0.1.0" "poof" (func $poof (param i32 i32) (result i32)))
  (memory (export "memory") 1)
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (i32.const 0))
  (func (export "run") (param $v i32) (result i32) (local.get $v)))
