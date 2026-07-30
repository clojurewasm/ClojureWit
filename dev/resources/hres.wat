;; The guest half of dev/resources/hres.wit: mint a host resource, read it
;; back through a borrow, and drop it. The drop is what makes the host's
;; destructor callback observable.
(module
  (import "local:hres/host@0.1.0" "mint" (func $mint (param i32) (result i32)))
  (import "local:hres/host@0.1.0" "peek" (func $peek (param i32) (result i32)))
  (import "local:hres/host@0.1.0" "eat" (func $eat (param i32) (result i32)))
  (import "local:hres/host@0.1.0" "[resource-drop]token" (func $drop (param i32)))
  (memory (export "memory") 1)
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (i32.const 0))
  (func (export "give-back") (param $v i32) (result i32)
    (call $eat (call $mint (local.get $v))))
  (global $kept (mut i32) (i32.const 0))
  (func (export "leak") (param $v i32) (result i32)
    (global.set $kept (call $mint (local.get $v)))
    (call $peek (global.get $kept)))
  (func (export "run") (param $v i32) (result i32)
    (local $h i32) (local $r i32)
    (local.set $h (call $mint (local.get $v)))
    (local.set $r (call $peek (local.get $h)))
    (call $drop (local.get $h))
    (local.get $r)))
