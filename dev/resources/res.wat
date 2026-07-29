;; The guest half of dev/resources/res.wit: a resource that really exists, so
;; `own<T>` can be checked against something that answers rather than traps.
;;
;; Two ABI facts that `wasm-tools component embed --dummy` does not reveal:
;;
;;   * A `borrow` self parameter, lowered into the component that *defines* the
;;     resource, arrives as the **rep itself**, not a handle index. Calling
;;     [resource-rep] on it traps and then poisons the instance, so every later
;;     call fails with "cannot enter component instance" and the cause is gone.
;;   * An `own` parameter does arrive as a handle index, and the guest that
;;     receives it owns it — hence the [resource-drop] in `consume`.
;;
;; The allocator is a bump allocator that never frees, like echo.wat's.
(module
  (import "[export]local:res/bag@0.1.0" "[resource-new]counter"  (func $new  (param i32) (result i32)))
  (import "[export]local:res/bag@0.1.0" "[resource-rep]counter"  (func $rep  (param i32) (result i32)))
  (import "[export]local:res/bag@0.1.0" "[resource-drop]counter" (func $drop (param i32)))
  (memory (export "memory") 1)
  (global $next (mut i32) (i32.const 1024))
  (func $alloc (param $n i32) (result i32)
    (local $p i32)
    (local.set $p (global.get $next))
    (global.set $next (i32.add (local.get $p) (local.get $n)))
    (local.get $p))
  (func (export "cabi_realloc")
        (param i32 i32 i32 i32) (result i32)
    (call $alloc (local.get 3)))
  (func (export "local:res/bag@0.1.0#[constructor]counter") (param $start i32) (result i32)
    (local $p i32)
    (local.set $p (call $alloc (i32.const 4)))
    (i32.store (local.get $p) (local.get $start))
    (call $new (local.get $p)))
  ;; self arrives as the rep, not a handle index
  (func (export "local:res/bag@0.1.0#[method]counter.bump") (param $self i32) (param $by i32) (result i32)
    (i32.store (local.get $self) (i32.add (i32.load (local.get $self)) (local.get $by)))
    (i32.load (local.get $self)))
  (func (export "local:res/bag@0.1.0#consume") (param $h i32) (result i32)
    (local $v i32)
    (local.set $v (i32.load (call $rep (local.get $h))))
    (call $drop (local.get $h))
    (local.get $v))
  (func (export "local:res/bag@0.1.0#boom") (result i32) (unreachable))
  (func (export "local:res/bag@0.1.0#make-two") (param $start i32) (result i32)
    (local $a i32) (local $b i32) (local $l i32)
    (local.set $a (call $alloc (i32.const 4)))
    (i32.store (local.get $a) (local.get $start))
    (local.set $b (call $alloc (i32.const 4)))
    (i32.store (local.get $b) (i32.add (local.get $start) (i32.const 1)))
    (local.set $l (call $alloc (i32.const 8)))
    (i32.store (local.get $l) (call $new (local.get $a)))
    (i32.store offset=4 (local.get $l) (call $new (local.get $b)))
    (i32.store (i32.const 512) (local.get $l))
    (i32.store offset=4 (i32.const 512) (i32.const 2))
    (i32.const 512))
  (func (export "local:res/bag@0.1.0#[dtor]counter") (param $rep i32)))
