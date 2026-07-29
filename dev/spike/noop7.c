/* A no-op with wasmtime_func_call's exact shape: seven parameters, five of
 * them pointers, returning a pointer. 0013 measured that call at 75 ns from C
 * and 1645 ns through FFM, and needs to know whether the gap is FFM's handling
 * of *this signature* or something specific to entering wasmtime.
 *
 * Built and driven by dev/cljwit/spike/ffm_shape.clj — `bb spike-ffm-shape`. */
#include <stddef.h>
#include <stdint.h>

static volatile int64_t sink;

void *noop7(void *cx, void *f, void *args, int64_t nargs,
            void *res, int64_t nres, void *trap) {
  (void)cx; (void)f; (void)args; (void)nargs; (void)res; (void)nres; (void)trap;
  return NULL; /* the shape wasmtime uses for "no error" */
}

/* Same shape, but spends about as long as wasmtime_func_call does (~75 ns).
   Splits "FFM plus a callee that takes real time" from "wasmtime's own code,
   executed on a JVM thread" — the last two explanations 0013 leaves open. */
void *busy7(void *cx, void *f, void *args, int64_t nargs,
            void *res, int64_t nres, void *trap) {
  (void)cx; (void)f; (void)args; (void)res; (void)trap;
  int64_t acc = nargs;
  for (int i = 0; i < 220; i++) acc = acc * 6364136223846793005LL + 1442695040888963407LL;
  sink = acc + nres;
  return NULL;
}
