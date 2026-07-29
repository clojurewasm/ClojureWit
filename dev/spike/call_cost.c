/* The C control for dev/cljwit/spike/call_cost.clj.
 *
 * Same module, same export, same C API entry points. The one thing that varies
 * against the Clojure spike is the caller: C rather than the JVM through FFM.
 * That is the whole point — 0011 attributed ~1.57 us/call to wasmtime, and
 * nothing had ever called wasmtime from anything but the JVM.
 *
 *   bb spike-c-cost [n] [reps]
 */
#include <wasmtime.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <pthread.h>

static double now_ns(void) {
  struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1e9 + t.tv_nsec;
}
static int cmp(const void *a, const void *b) {
  double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y;
}
static void report(const char *label, double *v, int reps) {
  qsort(v, reps, sizeof(double), cmp);
  printf("%-28s %8.1f ns/call   [%.1f .. %.1f]\n", label, v[reps / 2], v[0], v[reps - 1]);
}

/* Set once by main, read by the worker. The JVM never calls wasmtime from the
   process's initial thread, so "is it the JVM, or is it any spawned thread?"
   is a question the C control has to answer too. */
static long g_n; static int g_reps;

static void *bench(void *_unused) {
  (void)_unused;
  long n = g_n; int reps = g_reps;

  wasm_engine_t *eng = wasm_engine_new();
  wasmtime_store_t *st = wasmtime_store_new(eng, NULL, NULL);
  wasmtime_context_t *ctx = wasmtime_store_context(st);

  FILE *fp = fopen("dev/resources/add.core.wasm", "rb");
  if (!fp) { puts("run from the repository root"); return NULL; }
  fseek(fp, 0, SEEK_END); long sz = ftell(fp); rewind(fp);
  uint8_t *buf = malloc(sz);
  if (fread(buf, 1, sz, fp) != (size_t)sz) { puts("short read"); return NULL; }
  fclose(fp);

  wasmtime_module_t *mod;
  if (wasmtime_module_new(eng, buf, sz, &mod)) { puts("module_new"); return NULL; }
  wasmtime_instance_t inst; wasm_trap_t *trap = NULL;
  if (wasmtime_instance_new(ctx, mod, NULL, 0, &inst, &trap)) { puts("instance_new"); return NULL; }
  wasmtime_extern_t ext;
  if (!wasmtime_instance_export_get(ctx, &inst, "add", 3, &ext)) { puts("no export add"); return NULL; }
  wasmtime_func_t f = ext.of.func;

  printf("n=%ld reps=%d  (median, then min..max across reps)\n", n, reps);
  double *v = malloc(reps * sizeof(double));

  /* r < 0 is a warmup pass, and its timing is discarded. */
  for (int r = -1; r < reps; r++) {
    long k = r < 0 ? n / 10 : n;
    wasmtime_val_t args[2], res[1];
    args[0].kind = WASMTIME_I32; args[1].kind = WASMTIME_I32; args[1].of.i32 = 1;
    long long acc = 0; double t0 = now_ns();
    for (long i = 0; i < k; i++) {
      args[0].of.i32 = (int32_t)i;
      wasmtime_error_t *e = wasmtime_func_call(ctx, &f, args, 2, res, 1, &trap);
      if (e || trap) { puts("call failed"); return NULL; }
      acc += res[0].of.i32;
    }
    double dt = now_ns() - t0;
    if (r >= 0) v[r] = dt / (double)k;
    if (acc == -1) puts("");  /* keeps acc live; never taken */
  }
  report("wasmtime_func_call", v, reps);

  /* The unchecked path measured 2.4x *slower* from the JVM, reproducibly, and
     0011 left that unexplained. Here it is from C, where nothing is boxed. */
  for (int r = -1; r < reps; r++) {
    long k = r < 0 ? n / 10 : n;
    wasmtime_val_raw_t slot[2];
    long long acc = 0; double t0 = now_ns();
    for (long i = 0; i < k; i++) {
      slot[0].i32 = (int32_t)i; slot[1].i32 = 1;
      wasmtime_error_t *e = wasmtime_func_call_unchecked(ctx, &f, slot, 2, &trap);
      if (e || trap) { puts("unchecked call failed"); return NULL; }
      acc += slot[0].i32;
    }
    double dt = now_ns() - t0;
    if (r >= 0) v[r] = dt / (double)k;
    if (acc == -1) puts("");
  }
  report("wasmtime_func_call_unchecked", v, reps);
  return NULL;
}

int main(int argc, char **argv) {
  g_n = argc > 1 ? atol(argv[1]) : 1000000;
  g_reps = argc > 2 ? atoi(argv[2]) : 21;
  puts("-- initial thread --");
  bench(NULL);
  puts("-- spawned pthread --");
  pthread_t t; pthread_create(&t, NULL, bench, NULL); pthread_join(t, NULL);
  return 0;
}
