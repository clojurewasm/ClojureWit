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
;; always in cache. B2 will be the identical harness with ten types.
;;
;; Four exports form a curve in *how far the funcref is from the receiver*, so
;; the cost can be attributed rather than guessed at:
;;
;;   bench_direct     no funcref at all — a direct call
;;   bench_indirect   call_ref, target from a global      (0 loads)
;;   bench_one_load   call_ref, target from a field on $obj (1 load)
;;   bench            call_ref, target through the vtable (3 loads)
;;
;; Subtracting two of these tells you what those two differ by, which is not the
;; same as telling you what one level of indirection costs. The curve is what
;; separates "each level costs something" from "the first load costs everything".

(module
  ;; These four types are mutually recursive ($fn1 -> $obj -> $vtables -> $vt1
  ;; -> $fn1), so they must share one rec block or they are not the types they
  ;; appear to be. Sharing a group is not free: it makes every type in it
  ;; identity-dependent on all the others — see doc/design/0009 and
  ;; test/cljwit/rec_group_identity_test.clj.
  (rec
    (type $fn1 (func (param (ref null $obj)) (result (ref null $obj))))
    (type $vt1 (array (ref null $fn1)))
    (type $vtables (struct (field $a1 (ref $vt1))))
    (type $obj (sub (struct
      (field $hash (mut i32))
      (field $vt (ref $vtables))
      ;; The collapsed vtable the design would build if levels turned out to
      ;; cost: the arity-1 slot 0 method, reachable in one load. Present only so
      ;; bench_one_load can measure whether collapsing is worth doing.
      (field $slot0 (ref null $fn1)))))
    (type $node (sub $obj (struct
      (field $hash (mut i32))
      (field $vt (ref $vtables))
      (field $slot0 (ref null $fn1))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32))))))

  ;; Prime, and the driver refuses an n that is a multiple of it. Otherwise the
  ;; walk's answer at the published n is the head's own tag, which is also what
  ;; a loop that ran zero iterations returns — and the result check would pass a
  ;; benchmark doing no work at all.
  (global $ring-len i32 (i32.const 11))
  (global $ring-head (mut (ref null $obj)) (ref.null $obj))
  ;; The same method bench_indirect reaches through, so that walk pays call_ref
  ;; without paying any load off the receiver. Set in $setup rather than
  ;; initialised here so the engine cannot treat it as a known constant.
  (global $global-slot0 (mut (ref null $fn1)) (ref.null $fn1))

  (elem declare func $node-pnext)

  ;; The protocol method. It receives the erased receiver type, so it pays the
  ;; downcast the call site did not — that cost is real and B4 measures it.
  (func $node-pnext (type $fn1) (param $this (ref null $obj)) (result (ref null $obj))
    (struct.get $node $nxt (ref.cast (ref $node) (local.get $this))))

  (func $setup
    (local $vt (ref null $vtables))
    (local $fn (ref null $fn1))
    (local $head (ref null $node))
    (local $prev (ref null $node))
    (local $cur (ref null $node))
    (local $i i32)
    (local.set $fn (ref.func $node-pnext))
    (local.set $vt
      (struct.new $vtables (array.new_fixed $vt1 1 (local.get $fn))))
    (local.set $head
      (struct.new $node (i32.const 0) (ref.as_non_null (local.get $vt))
                        (local.get $fn) (ref.null $obj) (i32.const 0)))
    (local.set $prev (local.get $head))
    (local.set $i (i32.const 1))
    (block $done
      (loop $l
        (br_if $done (i32.ge_u (local.get $i) (global.get $ring-len)))
        (local.set $cur
          (struct.new $node (i32.const 0) (ref.as_non_null (local.get $vt))
                            (local.get $fn) (ref.null $obj) (local.get $i)))
        (struct.set $node $nxt (local.get $prev) (local.get $cur))
        (local.set $prev (local.get $cur))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.set $node $nxt (local.get $prev) (local.get $head))
    (global.set $ring-head (local.get $head))
    (global.set $global-slot0
      (array.get $vt1 (struct.get $vtables $a1 (ref.as_non_null (local.get $vt)))
                 (i32.const 0))))

  (start $setup)

  ;; Returns the final node's tag, so the chain has a result that depends on how
  ;; many times round the ring it went: (n mod 11).
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

  ;; One load off the receiver instead of three. This is the collapsed vtable —
  ;; if indirection levels are what cost, this lands two thirds of the way back
  ;; to bench_indirect; if the first dependent load is what costs, it lands on
  ;; top of bench.
  (func $bench-one-load (export "bench_one_load") (param $n i32) (result i32)
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
            (struct.get $obj $slot0 (local.get $o))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $node $tag (ref.cast (ref $node) (local.get $o))))

  ;; call_ref with the target already in hand — no load off the receiver at all.
  ;; The floor for an indirect call on this walk.
  (func $bench-indirect (export "bench_indirect") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $ring-head))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (call_ref $fn1 (local.get $o) (global.get $global-slot0)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $node $tag (ref.cast (ref $node) (local.get $o))))

  ;; No dispatch at all. What is left is the cost of chasing the ring, which
  ;; every other export also pays and none of them is measuring.
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
