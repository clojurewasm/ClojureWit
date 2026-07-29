;; The guest half of dev/resources/imp.wit: `run` calls the imported `twice`
;; and adds one, so a wrong answer is distinguishable from a missing call.
(module
  (import "local:imp/host@0.1.0" "twice" (func $twice (param i32) (result i32)))
  (memory (export "memory") 1)
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (i32.const 0))
  (func (export "run") (param $v i32) (result i32)
    (i32.add (call $twice (local.get $v)) (i32.const 1))))
