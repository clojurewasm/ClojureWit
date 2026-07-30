;; B8 — the boxed-i64 lane (0022 C): what fib's n = 46…92 domain costs.
;;
;; Predictions are in doc/design/0002-measure-first.md, written before the
;; run. Every variant walks B3's dependency chain — acc = acc + one, n
;; times, result consumed — but on boxed i64 values. The accumulator
;; starts at 2^30, one past i31's maximum, so every value in flight is
;; outside i31: the mixed dispatch always takes the boxed arm and the
;; canonicalization probe never fires, yet both branches are real code
;; the engine must keep. The answer is 2^30 + n, which no empty loop
;; produces.
;;
;; The overflow-check arm is (unreachable) — the throw representation is
;; open (0022 C) — and is never reached: values stay far below 2^63.

(module
  (type $Box (struct (field $v i64)))

  ;; B8k — the floor: operands statically boxed, no dispatch.
  ;; load, load, add, overflow check, allocate.
  (func (export "b8_known") (param $n i32) (result i32)
    (local $acc (ref $Box))
    (local $one (ref $Box))
    (local $i i32)
    (local $a i64)
    (local $b i64)
    (local $t i64)
    (local.set $acc (struct.new $Box (i64.const 1073741824)))
    (local.set $one (struct.new $Box (i64.const 1)))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $a (struct.get $Box $v (local.get $acc)))
        (local.set $b (struct.get $Box $v (local.get $one)))
        (local.set $t (i64.add (local.get $a) (local.get $b)))
        ;; signed-overflow check: ((a^t)&(b^t)) < 0 means the signs prove
        ;; overflow — the arm is the open throw lane, untaken here.
        (if (i64.lt_s (i64.and (i64.xor (local.get $a) (local.get $t))
                               (i64.xor (local.get $b) (local.get $t)))
                      (i64.const 0))
          (then (unreachable)))
        (local.set $acc (struct.new $Box (local.get $t)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (i32.wrap_i64 (struct.get $Box $v (local.get $acc))))

  ;; B8b — the lane as a compiler emits it: operands arrive as (ref null
  ;; eq), representation decided per operand at run time. The i31 arm is
  ;; real and never taken at these values.
  (func (export "b8_mixed") (param $n i32) (result i32)
    (local $acc (ref null eq))
    (local $one (ref null eq))
    (local $i i32)
    (local $a i64)
    (local $b i64)
    (local $t i64)
    (local.set $acc (struct.new $Box (i64.const 1073741824)))
    (local.set $one (struct.new $Box (i64.const 1)))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $a (if (result i64) (ref.test (ref i31) (local.get $acc))
                        (then (i64.extend_i32_s (i31.get_s (ref.cast (ref i31) (local.get $acc)))))
                        (else (struct.get $Box $v (ref.cast (ref $Box) (local.get $acc))))))
        (local.set $b (if (result i64) (ref.test (ref i31) (local.get $one))
                        (then (i64.extend_i32_s (i31.get_s (ref.cast (ref i31) (local.get $one)))))
                        (else (struct.get $Box $v (ref.cast (ref $Box) (local.get $one))))))
        (local.set $t (i64.add (local.get $a) (local.get $b)))
        (if (i64.lt_s (i64.and (i64.xor (local.get $a) (local.get $t))
                               (i64.xor (local.get $b) (local.get $t)))
                      (i64.const 0))
          (then (unreachable)))
        (local.set $acc (struct.new $Box (local.get $t)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (i32.wrap_i64 (struct.get $Box $v (ref.cast (ref $Box) (local.get $acc)))))

  ;; B8c — B8b plus the canonicalization probe: a result that fits i31
  ;; would re-box as i31 (0022 C's open question). Untaken at these
  ;; values, but the branch and both arms are real.
  (func (export "b8_canon") (param $n i32) (result i32)
    (local $acc (ref null eq))
    (local $one (ref null eq))
    (local $i i32)
    (local $a i64)
    (local $b i64)
    (local $t i64)
    (local.set $acc (struct.new $Box (i64.const 1073741824)))
    (local.set $one (struct.new $Box (i64.const 1)))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $a (if (result i64) (ref.test (ref i31) (local.get $acc))
                        (then (i64.extend_i32_s (i31.get_s (ref.cast (ref i31) (local.get $acc)))))
                        (else (struct.get $Box $v (ref.cast (ref $Box) (local.get $acc))))))
        (local.set $b (if (result i64) (ref.test (ref i31) (local.get $one))
                        (then (i64.extend_i32_s (i31.get_s (ref.cast (ref i31) (local.get $one)))))
                        (else (struct.get $Box $v (ref.cast (ref $Box) (local.get $one))))))
        (local.set $t (i64.add (local.get $a) (local.get $b)))
        (if (i64.lt_s (i64.and (i64.xor (local.get $a) (local.get $t))
                               (i64.xor (local.get $b) (local.get $t)))
                      (i64.const 0))
          (then (unreachable)))
        (local.set $acc
          (if (result (ref null eq))
              (i64.eq (local.get $t)
                      (i64.shr_s (i64.shl (local.get $t) (i64.const 33)) (i64.const 33)))
            (then (ref.i31 (i32.wrap_i64 (local.get $t))))
            (else (struct.new $Box (local.get $t)))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (i32.wrap_i64 (struct.get $Box $v (ref.cast (ref $Box) (local.get $acc)))))

  ;; B8i — B3's i31 fast path with a *real* allocating slow path where B3
  ;; had (unreachable). Inputs are i31 (0..n), the slow path is never
  ;; taken; the question is what its presence costs the fast path.
  (func $slow-add (param $a (ref null eq)) (param $b (ref null eq)) (result (ref null eq))
    (local $x i64)
    (local $y i64)
    (local $t i64)
    (local.set $x (if (result i64) (ref.test (ref i31) (local.get $a))
                    (then (i64.extend_i32_s (i31.get_s (ref.cast (ref i31) (local.get $a)))))
                    (else (struct.get $Box $v (ref.cast (ref $Box) (local.get $a))))))
    (local.set $y (if (result i64) (ref.test (ref i31) (local.get $b))
                    (then (i64.extend_i32_s (i31.get_s (ref.cast (ref i31) (local.get $b)))))
                    (else (struct.get $Box $v (ref.cast (ref $Box) (local.get $b))))))
    (local.set $t (i64.add (local.get $x) (local.get $y)))
    (if (i64.lt_s (i64.and (i64.xor (local.get $x) (local.get $t))
                           (i64.xor (local.get $y) (local.get $t)))
                  (i64.const 0))
      (then (unreachable)))
    (if (result (ref null eq))
        (i64.eq (local.get $t)
                (i64.shr_s (i64.shl (local.get $t) (i64.const 33)) (i64.const 33)))
      (then (ref.i31 (i32.wrap_i64 (local.get $t))))
      (else (struct.new $Box (local.get $t)))))

  (func (export "b8_slow_real") (param $n i32) (result i32)
    (local $acc (ref null eq))
    (local $one (ref null eq))
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
          (block $joined (result (ref null eq))
            (drop
              (block $slow (result (ref null eq))
                (local.set $x
                  (i31.get_s (br_on_cast_fail $slow (ref null eq) (ref i31) (local.get $acc))))
                (local.set $sum
                  (i32.add (local.get $x)
                           (i31.get_s (br_on_cast_fail $slow (ref null eq) (ref i31) (local.get $one)))))
                (br_if $slow (local.get $acc)
                             (i32.ne (i32.shr_s (i32.shl (local.get $sum) (i32.const 1))
                                                (i32.const 1))
                                     (local.get $sum)))
                (br $joined (ref.i31 (local.get $sum)))))
            (call $slow-add (local.get $acc) (local.get $one))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (i31.get_s (ref.cast (ref i31) (local.get $acc)))))
