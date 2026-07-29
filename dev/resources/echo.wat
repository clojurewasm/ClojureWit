;; Identity per scalar type. The point is not the guest — it is that a value
;; leaves Clojure, crosses the canonical ABI twice, and comes back, so
;; doc/design/0012's mapping is checked rather than asserted.
(module
  (func (export "echo-bool") (param $v i32) (result i32) (local.get $v))
  (func (export "echo-s32") (param $v i32) (result i32) (local.get $v))
  (func (export "echo-u64") (param $v i64) (result i64) (local.get $v))
  (func (export "echo-f32") (param $v f32) (result f32) (local.get $v))
  (func (export "echo-f64") (param $v f64) (result f64) (local.get $v))
  (func (export "echo-char") (param $v i32) (result i32) (local.get $v)))
