//! wasmtime's own floor, for comparison with dev/spike/call_cost.c.
//!
//! Same module, same export, same machine. Two lanes: the typed call, which is
//! what a Rust host would actually write, and the untyped `Val` call, which is
//! what the C API's `wasmtime_func_call` is a binding for.
//!
//!   cargo run --release -- [n] [reps]     # from the repository root
//!
//! Needs a Rust toolchain, which the dev shell does not provide.
use std::time::Instant;
use wasmtime::component::{Component, Linker as CLinker, Val as CVal};
use wasmtime::*;

fn report(label: &str, n: u64, reps: usize, mut f: impl FnMut(u64) -> i64) {
    for _ in 0..3 {
        std::hint::black_box(f(n / 10));
    }
    let mut v: Vec<f64> = (0..reps)
        .map(|_| {
            let t = Instant::now();
            std::hint::black_box(f(n));
            t.elapsed().as_nanos() as f64 / n as f64
        })
        .collect();
    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
    println!("{label:<28} {:>8.1} ns/call   [{:.1} .. {:.1}]", v[reps / 2], v[0], v[reps - 1]);
}

fn main() -> Result<()> {
    let n: u64 = std::env::args().nth(1).map_or(1_000_000, |s| s.parse().unwrap());
    let reps: usize = std::env::args().nth(2).map_or(21, |s| s.parse().unwrap());

    let engine = Engine::default();
    let module = Module::from_file(&engine, "dev/resources/add.core.wasm")?;
    let mut store = Store::new(&engine, ());
    let inst = Instance::new(&mut store, &module, &[])?;
    let f = inst.get_func(&mut store, "add").unwrap();
    let tf = f.typed::<(i32, i32), i32>(&store)?;

    println!("n={n} reps={reps}  (median, then min..max across reps)");
    report("TypedFunc::call", n, reps, |k| {
        let mut acc = 0i64;
        for i in 0..k {
            acc += tf.call(&mut store, (i as i32, 1)).unwrap() as i64;
        }
        acc
    });
    report("Func::call (Val)", n, reps, |k| {
        let mut acc = 0i64;
        let mut out = [Val::I32(0)];
        for i in 0..k {
            f.call(&mut store, &[Val::I32(i as i32), Val::I32(1)], &mut out).unwrap();
            acc += out[0].unwrap_i32() as i64;
        }
        acc
    });

    // The component lane. The C API has only the untyped path; this is the one
    // measurement that says whether a Rust shim with typed component calls
    // would recover the Component Model's tax (0013).
    let comp = Component::from_file(&engine, "dev/resources/add.component.wasm")?;
    let clinker = CLinker::new(&engine);
    let cinst = clinker.instantiate(&mut store, &comp)?;
    let cf = cinst.get_func(&mut store, "add").unwrap();
    let ctf = cf.typed::<(i32, i32), (i32,)>(&store)?;

    report("component TypedFunc::call", n, reps, |k| {
        let mut acc = 0i64;
        for _ in 0..k {
            let (r,) = ctf.call(&mut store, (17, 25)).unwrap();
            ctf.post_return(&mut store).unwrap();
            acc += r as i64;
        }
        acc
    });
    report("component Func::call (Val)", n, reps, |k| {
        let mut acc = 0i64;
        let mut out = [CVal::S32(0)];
        for _ in 0..k {
            cf.call(&mut store, &[CVal::S32(17), CVal::S32(25)], &mut out).unwrap();
            cf.post_return(&mut store).unwrap();
            if let CVal::S32(x) = out[0] { acc += x as i64; }
        }
        acc
    });
    Ok(())
}
