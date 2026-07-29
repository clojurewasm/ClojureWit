;; B3 — boxed arithmetic on the i31 fast path.
;;
;; doc/design/0004-dispatch-design.md: "br_on_cast_fail both operands to a slow
;; path; on the fast path, two i31.get_s and an i32.add with an overflow check.
;; No call. The JVM's equivalent is two eight-way class chains plus two virtual
;; calls." That is what this measures.
;;
;; i31 is a tagged pointer, not an allocation, so the fast path never touches
;; the heap. The overflow check is real and is on the fast path: 31 bits signed,
;; so a sum that no longer fits has to fall out to the slow path or the answer
;; is silently wrong. The slow path is present and unreachable at these inputs —
;; a fast path measured without its fallback is measuring a different program.
;;
;; The accumulator is a dependency chain: each add needs the previous result, so
;; nothing hoists and nothing is deleted. Adding 1 exactly n times means the
;; answer is n, which the driver checks and which no empty loop can produce.

(module
  ;; The slow path's boxed representation — what a value that does not fit in
  ;; 31 bits becomes. Never constructed at these inputs; it exists so the fast
  ;; path has somewhere to fall to.
  (type $boxed (struct (field $lo i32) (field $hi i32)))

  (func $slow-add (param $a anyref) (param $b anyref) (result anyref)
    (unreachable))

  ;; The measurement. Two br_on_cast_fail, two i31.get_s, an i32.add and an
  ;; overflow check, all inline.
  (func $bench (export "bench") (param $n i32) (result i32)
    (local $acc anyref)
    (local $one anyref)
    (local $i i32)
    (local $x i32)
    (local $sum i32)
    (local.set $acc (ref.i31 (i32.const 0)))
    (local.set $one (ref.i31 (i32.const 1)))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $acc
          (block $joined (result anyref)
            (drop
              (block $slow (result anyref)
                (local.set $x
                  (i31.get_s (br_on_cast_fail $slow anyref (ref i31) (local.get $acc))))
                (local.set $sum
                  (i32.add (local.get $x)
                           (i31.get_s (br_on_cast_fail $slow anyref (ref i31) (local.get $one)))))
                ;; Does the sum still fit in 31 bits signed? Sign-extend from
                ;; bit 30 and compare; if it changed, it overflowed.
                (br_if $slow (local.get $acc)
                             (i32.ne (i32.shr_s (i32.shl (local.get $sum) (i32.const 1))
                                                (i32.const 1))
                                     (local.get $sum)))
                (br $joined (ref.i31 (local.get $sum)))))
            (call $slow-add (local.get $acc) (local.get $one))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (i31.get_s (ref.cast (ref i31) (local.get $acc))))

  ;; Floor: the same loop with the boxing removed entirely — a raw i32 add.
  ;; This is what the arithmetic costs if a compiler can prove the values are
  ;; fixnums and unbox them, so `bench` minus this is what the tagging costs.
  (func $bench-unboxed (export "bench_unboxed") (param $n i32) (result i32)
    (local $acc i32)
    (local $i i32)
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $acc (i32.add (local.get $acc) (i32.const 1)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (local.get $acc))

  ;; Control: the same i31 round-trip with the overflow check removed, so the
  ;; check can be priced on its own rather than assumed cheap.
  (func $bench-nocheck (export "bench_nocheck") (param $n i32) (result i32)
    (local $acc anyref)
    (local $one anyref)
    (local $i i32)
    (local.set $acc (ref.i31 (i32.const 0)))
    (local.set $one (ref.i31 (i32.const 1)))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $acc
          (block $joined (result anyref)
            (drop
              (block $slow (result anyref)
                (br $joined
                  (ref.i31
                    (i32.add
                      (i31.get_s (br_on_cast_fail $slow anyref (ref i31) (local.get $acc)))
                      (i31.get_s (br_on_cast_fail $slow anyref (ref i31) (local.get $one))))))))
            (call $slow-add (local.get $acc) (local.get $one))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (i31.get_s (ref.cast (ref i31) (local.get $acc)))))
