;; B4 — what a ref.cast actually costs, on two axes.
;;
;; doc/design/0002-measure-first.md recorded "depth matters measurably — enough
;; to justify a flat type graph" before anything was built. B2 then raised a
;; second candidate axis: an earlier control there cost 6.81ns where its
;; replacement cost 2.33, and the two differed in the *variety of input types*
;; a cast saw as well as in depth. Both axes are measured here, separately.
;;
;; The type graph is a straight chain $obj -> $d2 -> ... -> $d5, with ten leaf
;; types at depth 6 under $d5. All of them carry the same fields, so:
;;
;;   depth axis    cast a depth-6 object to $d2, $d3, $d4, $d5 or $v0
;;   variety axis  cast to $d5, with the ring holding 1 leaf type or 10
;;
;; cast_none is the floor -- the same ring walk reading $nxt off $obj with no
;; cast at all -- so every number is the floor plus one cast.
;;
;; Same dependency-chain shape and prime ring length as B1 and B2: the walk
;; cannot be hoisted or deleted, and the driver refuses an n whose expected
;; answer is what an empty loop returns.

(module
  (rec
    (type $obj (sub (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $d2 (sub $obj (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $d3 (sub $d2 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $d4 (sub $d3 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $d5 (sub $d4 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v0 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v1 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v2 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v3 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v4 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v5 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v6 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v7 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v8 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32)))))
    (type $v9 (sub $d5 (struct
      (field $hash (mut i32))
      (field $nxt (mut (ref null $obj)))
      (field $tag (mut i32))))))

  (global $mono (mut (ref null $obj)) (ref.null $obj))
  (global $mixed (mut (ref null $obj)) (ref.null $obj))

  (func $setup
    (local $head (ref null $obj))
    (local $prev (ref null $obj))
    (local $cur (ref null $obj))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 0)))
    (local.set $head (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 1)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 2)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 3)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 4)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 5)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 6)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 7)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 8)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 9)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 10)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (struct.set $obj $nxt (local.get $prev) (local.get $head))
    (global.set $mono (local.get $head))

    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 0)))
    (local.set $head (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v1 (i32.const 0) (ref.null $obj) (i32.const 1)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v2 (i32.const 0) (ref.null $obj) (i32.const 2)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v3 (i32.const 0) (ref.null $obj) (i32.const 3)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v4 (i32.const 0) (ref.null $obj) (i32.const 4)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v5 (i32.const 0) (ref.null $obj) (i32.const 5)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v6 (i32.const 0) (ref.null $obj) (i32.const 6)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v7 (i32.const 0) (ref.null $obj) (i32.const 7)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v8 (i32.const 0) (ref.null $obj) (i32.const 8)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v9 (i32.const 0) (ref.null $obj) (i32.const 9)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (local.set $cur (struct.new $v0 (i32.const 0) (ref.null $obj) (i32.const 10)))
    (struct.set $obj $nxt (local.get $prev) (local.get $cur))
    (local.set $prev (local.get $cur))
    (struct.set $obj $nxt (local.get $prev) (local.get $head))
    (global.set $mixed (local.get $head)))

  (start $setup)

  (func (export "cast_depth_2") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $mono))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (struct.get $d2 $nxt (ref.cast (ref $d2) (local.get $o))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $obj $tag (local.get $o)))

  (func (export "cast_depth_3") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $mono))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (struct.get $d3 $nxt (ref.cast (ref $d3) (local.get $o))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $obj $tag (local.get $o)))

  (func (export "cast_depth_4") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $mono))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (struct.get $d4 $nxt (ref.cast (ref $d4) (local.get $o))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $obj $tag (local.get $o)))

  (func (export "cast_depth_5") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $mono))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (struct.get $d5 $nxt (ref.cast (ref $d5) (local.get $o))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $obj $tag (local.get $o)))

  (func (export "cast_depth_6") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $mono))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (struct.get $v0 $nxt (ref.cast (ref $v0) (local.get $o))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $obj $tag (local.get $o)))

  (func (export "cast_variety_1") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $mono))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (struct.get $d5 $nxt (ref.cast (ref $d5) (local.get $o))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $obj $tag (local.get $o)))

  (func (export "cast_variety_10") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $mixed))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (struct.get $d5 $nxt (ref.cast (ref $d5) (local.get $o))))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $obj $tag (local.get $o)))

  ;; Floor: the same walk with no cast at all, reading $nxt straight off $obj.
  ;; Every number above is this plus one ref.cast.
  (func (export "cast_none") (param $n i32) (result i32)
    (local $o (ref null $obj))
    (local $i i32)
    (local.set $o (global.get $mono))
    (local.set $i (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $i)))
        (local.set $o (struct.get $obj $nxt (local.get $o)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))
    (struct.get $obj $tag (local.get $o))))
