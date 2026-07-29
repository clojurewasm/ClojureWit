;; B1 — vtable-slot protocol dispatch, monomorphic call site.
;;
;; The shape under test is doc/design/0004-dispatch-design.md's claim that a
;; protocol call costs "three loads and an indirect call":
;;
;;   obj -> $vt          (load 1)  struct.get $obj $vt
;;   vtables -> $a1      (load 2)  struct.get $vtables $a1
;;   $a1[slot]           (load 3)  array.get $vt1
;;   call_ref
;;
;; The benchmark walks a ring of nodes by calling the protocol method that
;; returns the next node, so each dispatch depends on the previous one. That
;; costs us instruction-level parallelism, which is the point: a chain measures
;; dispatch *latency* and cannot be deleted, hoisted, or hidden. It is also the
;; shape of the dispatch Clojure actually does most (seq traversal).
;;
;; Every node here has the same type, so the site is monomorphic — one vtable,
;; always in cache. b2_megamorphic.wat is the identical harness with ten types.

(module
  ;; WasmGC subtyping is nominal and these four types are mutually recursive
  ;; ($fn1 -> $obj -> $vtables -> $vt1 -> $fn1), so they must share one rec
  ;; block or they are not the types they appear to be.
  (rec
    (type $fn1 (func (param (ref null $obj)) (result (ref null $obj))))
    (type $vt1 (array (ref null $fn1)))
    (type $vtables (struct (field $a1 (ref $vt1))))
    (type $obj (sub (struct
      (field $hash (mut i32))
      (field $vt (ref $vtables)))))
    (type $node (sub $obj (struct
      (field $hash (mut i32))
      (field $vt (ref $vtables))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32))))))

  (global $ring-head (mut (ref null $obj)) (ref.null $obj))
  (global $ring-len i32 (i32.const 10))
  ;; Holds the same method $bench-indirect reaches through, so that walk pays
  ;; call_ref without paying the vtable loads. Set in $setup rather than
  ;; initialised here so the engine cannot treat it as a known constant.
  (global $slot0 (mut (ref null $fn1)) (ref.null $fn1))

  (elem declare func $node-pnext)

  ;; The protocol method. It receives the erased receiver type, so it pays the
  ;; downcast the call site did not — that cost is real and B4 measures it.
  (func $node-pnext (type $fn1) (param $this (ref null $obj)) (result (ref null $obj))
    (struct.get $node $nxt (ref.cast (ref $node) (local.get $this))))

  (func $setup
    (local $vt (ref null $vtables))
    (local $head (ref null $node))
    (local $prev (ref null $node))
    (local $cur (ref null $node))
    (local $i i32)
    (local.set $vt
      (struct.new $vtables (array.new_fixed $vt1 1 (ref.func $node-pnext))))
    (local.set $head
      (struct.new $node
        (i32.const 0) (ref.as_non_null (local.get $vt)) (ref.null $obj) (i32.const 0)))
    (local.set $prev (local.get $head))
    (local.set $i (i32.const 1))
    (block $done
      (loop $l
        (br_if $done (i32.ge_u (local.get $i) (global.get $ring-len)))
        (local.set $cur
          (struct.new $node
            (i32.const 0) (ref.as_non_null (local.get $vt)) (ref.null $obj) (local.get $i)))
        (struct.set $node $nxt (local.get $prev) (local.get $cur))
        (local.set $prev (local.get $cur))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.set $node $nxt (local.get $prev) (local.get $head))
    (global.set $ring-head (local.get $head))
    (global.set $slot0
      (array.get $vt1 (struct.get $vtables $a1 (ref.as_non_null (local.get $vt)))
                 (i32.const 0))))

  (start $setup)

  ;; Returns the final node's tag so the chain has an observable result. With a
  ;; ring of 10, the answer is (n mod 10) — the harness checks it.
  (func $bench (export "bench") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $ring-head))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o
          (call_ref $fn1
            (local.get $o)
            (array.get $vt1
              (struct.get $vtables $a1 (struct.get $obj $vt (local.get $o)))
              (i32.const 0))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $node $tag (ref.cast (ref $node) (local.get $o))))

  ;; Control: the identical ring walk with the three loads and the call_ref
  ;; replaced by a direct call. Without it, `bench` measures dispatch *plus* the
  ;; latency of chasing a pointer ring, and there is no way to say which of the
  ;; two a slow number belongs to.
  ;;
  ;; bench_indirect sits between the two: it pays the call_ref but reaches the
  ;; target through a global instead of the receiver's vtable, so the three
  ;; loads are gone. The design note claims a cost of "three loads and an
  ;; indirect call"; these three exports are what say which half is which, and
  ;; therefore whether flattening the vtable would buy anything.
  (func $bench-indirect (export "bench_indirect") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $ring-head))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (call_ref $fn1 (local.get $o) (global.get $slot0)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $node $tag (ref.cast (ref $node) (local.get $o))))

  (func $bench-direct (export "bench_direct") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $ring-head))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (call $node-pnext (local.get $o)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $node $tag (ref.cast (ref $node) (local.get $o)))))
