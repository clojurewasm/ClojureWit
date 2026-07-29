;; B6 — what an aggregate argument costs at the component boundary.
;;
;; doc/design/0007-* established that no Canonical ABI any runtime executes
;; carries WasmGC references across a component boundary, so every `string`,
;; `list` or `record` a Clojure component exports gets copied between the GC
;; heap and a linear memory. Nothing in S0 measured that copy, and it is what
;; "a Rust developer calls a Clojure component" actually costs.
;;
;; The shape of the copy is forced: **WasmGC has no bulk move between a GC
;; array and linear memory.** `array.copy` is array-to-array, and
;; `array.new_data`/`array.init_data` read a *data segment* — a compile-time
;; constant — not memory. So lowering is a per-element loop, and the only lever
;; is how many bytes each iteration moves.
;;
;;   lower_i8    (array i8)  -> memory, one byte per iteration
;;   lower_i64   (array i64) -> memory, eight bytes per iteration
;;   lift_i8     memory -> (array i8), the other direction
;;   memcpy      memory -> memory via memory.copy — what a linear-memory
;;               language pays for the same payload, and the floor
;;   arraycopy   (array i8) -> (array i8) via array.copy — the bulk move that
;;               exists, for reference
;;
;; The payload is 4 KB and `n` counts whole payload copies, so the driver's
;; ns/op is ns per 4 KB and ns/byte is that over 4096. Use a much smaller n
;; than the dispatch benchmarks: `bb bench-s0 B6l8 ... --n 20000`.
;;
;; Every export returns n plus a byte read back from its own destination, so a
;; loop that ran zero times returns n instead of n+1 and the driver's result
;; check catches it.

(module
  (type $bytes (array (mut i8)))
  (type $words (array (mut i64)))

  (memory 2)
  ;; Source for memcpy and lift_i8, with a 1 in its first byte so a destination
  ;; that was written can be told from one that was not.
  (data (i32.const 8192) "\01")

  (global $src8 (mut (ref null $bytes)) (ref.null $bytes))
  (global $src64 (mut (ref null $words)) (ref.null $words))
  (global $dst8 (mut (ref null $bytes)) (ref.null $bytes))

  (func $setup
    (global.set $src8 (array.new_default $bytes (i32.const 4096)))
    (global.set $src64 (array.new_default $words (i32.const 512)))
    (global.set $dst8 (array.new_default $bytes (i32.const 4096)))
    (array.set $bytes (global.get $src8) (i32.const 0) (i32.const 1))
    (array.set $words (global.get $src64) (i32.const 0) (i64.const 1)))

  (start $setup)

  ;; (array i8) -> memory, one byte at a time. This is the Canonical ABI's
  ;; lowering of a string or list<u8> if the guest holds bytes as bytes.
  (func (export "lower_i8") (param $n i32) (result i32)
    (local $i i32) (local $j i32)
    (local.set $i (local.get $n))
    (block $done
      (loop $outer
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $j (i32.const 0))
        (block $inner-done
          (loop $inner
            (br_if $inner-done (i32.ge_u (local.get $j) (i32.const 4096)))
            (i32.store8 (local.get $j)
                        (array.get_u $bytes (global.get $src8) (local.get $j)))
            (local.set $j (i32.add (local.get $j) (i32.const 1)))
            (br $inner)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $outer)))
    (i32.add (local.get $n) (i32.load8_u (i32.const 0))))

  ;; The same 4 KB, eight bytes per iteration. doc/design/0008 says the internal
  ;; representation is ours to choose as long as no program can tell, so this is
  ;; a lever the compiler is allowed to pull.
  (func (export "lower_i64") (param $n i32) (result i32)
    (local $i i32) (local $j i32)
    (local.set $i (local.get $n))
    (block $done
      (loop $outer
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $j (i32.const 0))
        (block $inner-done
          (loop $inner
            (br_if $inner-done (i32.ge_u (local.get $j) (i32.const 512)))
            (i64.store (i32.shl (local.get $j) (i32.const 3))
                       (array.get $words (global.get $src64) (local.get $j)))
            (local.set $j (i32.add (local.get $j) (i32.const 1)))
            (br $inner)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $outer)))
    (i32.add (local.get $n) (i32.load8_u (i32.const 0))))

  ;; The lifting direction: memory -> (array i8). Same loop, opposite way.
  (func (export "lift_i8") (param $n i32) (result i32)
    (local $i i32) (local $j i32)
    (local.set $i (local.get $n))
    (block $done
      (loop $outer
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $j (i32.const 0))
        (block $inner-done
          (loop $inner
            (br_if $inner-done (i32.ge_u (local.get $j) (i32.const 4096)))
            (array.set $bytes (global.get $dst8) (local.get $j)
                       (i32.load8_u (i32.add (local.get $j) (i32.const 8192))))
            (local.set $j (i32.add (local.get $j) (i32.const 1)))
            (br $inner)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $outer)))
    (i32.add (local.get $n)
             (array.get_u $bytes (global.get $dst8) (i32.const 0))))

  ;; The floor: what a linear-memory language moves the same 4 KB for.
  (func (export "memcpy") (param $n i32) (result i32)
    (local $i i32)
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (memory.copy (i32.const 4096) (i32.const 8192) (i32.const 4096))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (i32.add (local.get $n) (i32.load8_u (i32.const 4096))))

  ;; For reference: the bulk move WasmGC *does* have, which is no help at the
  ;; boundary because both ends must be GC arrays.
  (func (export "arraycopy") (param $n i32) (result i32)
    (local $i i32)
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (array.copy $bytes $bytes
                    (global.get $dst8) (i32.const 0)
                    (global.get $src8) (i32.const 0) (i32.const 4096))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (i32.add (local.get $n)
             (array.get_u $bytes (global.get $dst8) (i32.const 0)))))
