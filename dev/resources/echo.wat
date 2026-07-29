;; Identity per scalar type. The point is not the guest — it is that a value
;; leaves Clojure, crosses the canonical ABI twice, and comes back, so
;; doc/design/0012's mapping is checked rather than asserted.
(module
  ;; Aggregates arrive already lowered into our memory by the host, so echoing
  ;; one means handing back the same (ptr, len) — no copy. What it does need is
  ;; the two things doc/design/0007 named: an exported memory and cabi_realloc.
  (memory (export "memory") 1)
  (global $next (mut i32) (i32.const 16))
  (func (export "cabi_realloc")
        (param $old i32) (param $old-sz i32) (param $align i32) (param $new-sz i32)
        (result i32)
    (local $p i32)
    (local.set $p (i32.and (i32.add (global.get $next)
                                    (i32.sub (local.get $align) (i32.const 1)))
                           (i32.xor (i32.sub (local.get $align) (i32.const 1))
                                    (i32.const -1))))
    (global.set $next (i32.add (local.get $p) (local.get $new-sz)))
    (local.get $p))

  ;; A result wider than one core value comes back through a pointer the callee
  ;; returns; here that is the fixed eight bytes at 0.
  (func (export "echo-string") (param $ptr i32) (param $len i32) (result i32)
    (i32.store (i32.const 0) (local.get $ptr))
    (i32.store (i32.const 4) (local.get $len))
    (i32.const 0))

  ;; enum is a discriminant and needs no memory.
  (func (export "echo-colour") (param $v i32) (result i32) (local.get $v))

  ;; option<u32> flattens to (discriminant, payload); the result is two core
  ;; values, so it comes back through a pointer. Its area is the eight bytes
  ;; at 8, kept clear of echo-string's at 0.
  (func (export "echo-option-u32") (param $disc i32) (param $val i32) (result i32)
    (i32.store (i32.const 8) (local.get $disc))
    (i32.store (i32.const 12) (local.get $val))
    (i32.const 8))

  (func (export "echo-bool") (param $v i32) (result i32) (local.get $v))
  (func (export "echo-s32") (param $v i32) (result i32) (local.get $v))
  (func (export "echo-u64") (param $v i64) (result i64) (local.get $v))
  (func (export "echo-f32") (param $v f32) (result f32) (local.get $v))
  (func (export "echo-f64") (param $v f64) (result f64) (local.get $v))
  (func (export "echo-char") (param $v i32) (result i32) (local.get $v)))
