;; The guest half of dev/resources/mres.wit: mint through one interface,
;; peek through the other, drop, return.
(module
  (import "local:mres/host@0.1.0" "mint" (func $mint (param i32) (result i32)))
  (import "local:mres/more@0.1.0" "peek" (func $peek (param i32) (result i32)))
  (import "local:mres/host@0.1.0" "[resource-drop]token" (func $drop (param i32)))
  (memory (export "memory") 1)
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (i32.const 0))
  (func (export "run") (param $v i32) (result i32)
    (local $h i32) (local $r i32)
    (local.set $h (call $mint (local.get $v)))
    (local.set $r (call $peek (local.get $h)))
    (call $drop (local.get $h))
    (local.get $r)))
