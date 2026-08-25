// package Tars

// import chisel3._
// import chisel3.util._
// // _root_ is required: `import chisel3.util._` above pulls in chisel3.util.Pipe,
// // which shadows the top-level Pipe package this project defines.
// import SA.{Top => PipeTop}

// // =============================================================================
// // SA_ACCEL -- memory subsystem stitched onto the pipelined systolic array
// // =============================================================================
// //
// // ACCEL stitched AGU + Scratchpad into a single address-generate-and-stream
// // engine. This module does the same thing twice (once per operand) and hands
// // the two streams to Pipe.Top, which owns the skew buffers and the PE array.
// //
// //        wdata_a --> Scratchpad A --+                +--> row --+
// //                        ^          |                |         |
// //                      AGU A -------+--> beat reg ---+       Pipe.Top --> out_sum
// //                        ^          |                |         |
// //        wdata_b --> Scratchpad B --+                +--> col --+
// //                        ^
// //                      AGU B
// //
// // -----------------------------------------------------------------------------
// // DATA LAYOUT -- this is the part that makes the AGU fit without modification
// // -----------------------------------------------------------------------------
// // The AGU emits addr(j) = base + step*K + j: `width` CONSECUTIVE addresses,
// // advancing by K each step. For a rows x N times N x cols product the array
// // wants, at step k, the k-th column of A and the k-th row of B:
// //
// //   A-side: store A COLUMN-MAJOR, so A(i)(k) lives at k*rows + i.
// //           Then K = rows and lane i naturally lands on A(i)(k).
// //   B-side: store B ROW-MAJOR, so B(k)(j) lives at k*cols + j.
// //           Then K = cols and lane j naturally lands on B(k)(j).
// //
// // Both sides are therefore a plain sequential fill: write beat k carries
// // exactly the operand vector that read beat k returns.
// //
// // Bank conflicts are impossible by construction: lane j maps to bank
// // (base + k*K + j) % width = (base + j) % width, which is distinct for all j
// // because K is a multiple of width. conflict is still exported so a testbench
// // can prove it.
// //
// // -----------------------------------------------------------------------------
// // PACING -- why II exists
// // -----------------------------------------------------------------------------
// // PipePE has a 3-stage pipeline (result 3 cycles after input) and PipeSA feeds
// // the result back through 3 more registers (fb_reg1..3) before it reappears on
// // psum_in. The accumulation recurrence is therefore SIX cycles long: operand k
// // can only be added to the running sum of operand k-1 if the two are issued 6
// // cycles apart. Streaming one beat per cycle would silently split each dot
// // product into 6 independent interleaved partial sums.
// //
// // So the AGU is stalled to one step every II cycles (II = 6 by default) and the
// // array is fed explicit ZEROS in between. Zeros are a genuine no-op for the PE:
// // in INT8 mode shared_mul_result is 0 so out_int = psum_in + 0, and in FP16
// // mode product_is_zero forces the mantissa to 0 so the add returns psum
// // unchanged. The partial sum simply circulates in the feedback registers until
// // the next real beat arrives.
// //
// // Note this is also why en is NOT used to stall: PipeSA's feedback registers
// // are `RegNext(Mux(res, 0, Mux(en, ..., 0)))`, so dropping en ZEROES the
// // accumulator rather than freezing it. en is held high for the entire run.
// // =============================================================================

// class SA_ACCEL(
//   val rows:   Int = 4,       // A operands per beat  == systolic array rows
//   val cols:   Int = 4,       // B operands per beat  == systolic array cols
//   val N:      Int = 4,       // inner dimension: number of beats per run
//   val II:     Int = 6,       // issue interval, must cover the PE feedback loop
//   val depthA: Int = -1,      // -1 => exactly N*rows words
//   val depthB: Int = -1       // -1 => exactly N*cols words
// ) extends Module {

//   // PipePE's pipeline depth. Pipe.Top hardcodes the same 3 for its skew
//   // buffers, so this is not free to change here alone.
//   val PIPE_DEPTH = 3
//   val dataWidth  = 16        // Pipe.Top's row/col ports are UInt(16.W)

//   require(rows > 0 && cols > 0, "rows and cols must be positive")
//   require(isPow2(rows), "rows is the A-side scratchpad busWidth, must be a power of two")
//   require(isPow2(cols), "cols is the B-side scratchpad busWidth, must be a power of two")
//   require(N > 1, "N must be > 1 (each scratchpad bank must hold more than one word)")
//   require(II >= 2 * PIPE_DEPTH,
//     s"II must be >= ${2 * PIPE_DEPTH}: the PE result is $PIPE_DEPTH cycles behind its " +
//     s"input and PipeSA adds $PIPE_DEPTH more before it returns on psum_in")

//   val dA = if (depthA > 0) depthA else N * rows
//   val dB = if (depthB > 0) depthB else N * cols

//   require(dA >= N * rows, s"depthA ($dA) cannot hold N*rows (${N * rows}) words")
//   require(dB >= N * cols, s"depthB ($dB) cannot hold N*cols (${N * cols}) words")
//   require(dA % rows == 0, "depthA must be a whole number of A beats")
//   require(dB % cols == 0, "depthB must be a whole number of B beats")

//   // Cycles to hold the array open after the last real beat so every PE's final
//   // sum has propagated: the (rows-1, cols-1) corner sees the beat
//   // PIPE_DEPTH*(rows-1 + cols-1) cycles late and needs PIPE_DEPTH more to
//   // produce it. +2 of slack.
//   val drainCycles = PIPE_DEPTH * (rows + cols - 2) + PIPE_DEPTH + 2

//   // the far-corner PE is latched at this offset; the drain must outlast it
//   val lastCapture = PIPE_DEPTH * (rows + cols - 2) + PIPE_DEPTH
//   require(drainCycles > lastCapture,
//     s"drain ($drainCycles) must outlast the last result capture ($lastCapture)")

//   val io = IO(new Bundle {
//     val res = Input(Bool())                                // synchronous abort/reload

//     // ---- fill port (one beat per cycle per side, sequential) ----------------
//     val wen_a   = Input(Bool())
//     val wdata_a = Input(Vec(rows, UInt(dataWidth.W)))
//     val wen_b   = Input(Bool())
//     val wdata_b = Input(Vec(cols, UInt(dataWidth.W)))
//     val ready_a = Output(Bool())                           // high while A accepts writes
//     val ready_b = Output(Bool())                           // high while B accepts writes

//     // ---- run configuration -------------------------------------------------
//     val base_a = Input(UInt(32.W))                         // latched when the run starts
//     val base_b = Input(UInt(32.W))
//     val mode   = Input(Bool())                             // false = INT8, true = FP16
//     val bias   = Input(Vec(cols, UInt(32.W)))

//     // ---- results -----------------------------------------------------------
//     val out_sum  = Output(Vec(rows, Vec(cols, UInt(32.W))))
//     val busy     = Output(Bool())
//     val done     = Output(Bool())                          // level, holds until res
//     val conflict = Output(Bool())

//     // ---- observability: the operand stream actually handed to the array -----
//     val dbg_beat = Output(Bool())
//     val dbg_row  = Output(Vec(rows, UInt(dataWidth.W)))
//     val dbg_col  = Output(Vec(cols, UInt(dataWidth.W)))
//   })

//   val spaA = Module(new Scratchpad(dA, dataWidth, rows))
//   val spaB = Module(new Scratchpad(dB, dataWidth, cols))
//   val aguA = Module(new AGU(rows, hasAdvance = true))
//   val aguB = Module(new AGU(cols, hasAdvance = true))
//   val sa   = Module(new PipeTop(rows, cols))

//   val sLoad :: sRun :: sDrain :: sDone :: Nil = Enum(4)
//   val state = RegInit(sLoad)

//   val phase    = RegInit(0.U(log2Ceil(II + 1).W))
//   val drainCnt = RegInit(0.U(log2Ceil(drainCycles + 1).W))
//   val adv      = phase === 0.U

//   // ---------------------------------------------------------------------------
//   // defaults -- every submodule input needs an unconditional driver or FIRRTL
//   // rejects the design as not fully initialized
//   // ---------------------------------------------------------------------------
//   spaA.io.wen := false.B
//   spaA.io.waddr.foreach(_ := 0.U)
//   spaA.io.wdata := io.wdata_a
//   spaA.io.ren := false.B
//   spaA.io.raddr.foreach(_ := 0.U)
//   spaA.io.clr := false.B

//   spaB.io.wen := false.B
//   spaB.io.waddr.foreach(_ := 0.U)
//   spaB.io.wdata := io.wdata_b
//   spaB.io.ren := false.B
//   spaB.io.raddr.foreach(_ := 0.U)
//   spaB.io.clr := false.B

//   aguA.io.res         := false.B
//   aguA.io.en          := false.B
//   aguA.io.advance.get := false.B
//   aguA.io.baseAddr    := io.base_a
//   aguA.io.K           := rows.U
//   aguA.io.N           := N.U

//   aguB.io.res         := false.B
//   aguB.io.en          := false.B
//   aguB.io.advance.get := false.B
//   aguB.io.baseAddr    := io.base_b
//   aguB.io.K           := cols.U
//   aguB.io.N           := N.U

//   // ---------------------------------------------------------------------------
//   // one-cycle beat pipeline matching SyncReadMem latency; squashed on abort.
//   // Both AGUs are in lockstep (same N, same advance) so one flag covers both.
//   // ---------------------------------------------------------------------------
//   val beatValid = RegNext(aguA.io.valid && !io.res, false.B)
//   val seenFirst = RegInit(false.B)

//   val zerosRow = VecInit(Seq.fill(rows)(0.U(dataWidth.W)))
//   val zerosCol = VecInit(Seq.fill(cols)(0.U(dataWidth.W)))

//   // Between real beats the scratchpad output is stale (ren was low), so it is
//   // explicitly replaced with zeros rather than passed through.
//   val saRow = Mux(beatValid, spaA.io.rdata, zerosRow)
//   val saCol = Mux(beatValid, spaB.io.rdata, zerosCol)

//   // ---------------------------------------------------------------------------
//   // systolic array
//   // ---------------------------------------------------------------------------
//   sa.io.row  := saRow
//   sa.io.col  := saCol
//   sa.io.bias := io.bias
//   sa.io.mode := io.mode

//   // Clear the array (skew buffers and PipeSA feedback registers) while loading,
//   // then hold en high for run + drain + done. Dropping en would zero the
//   // accumulators, so it stays high until the next res.
//   sa.io.res := (state === sLoad) || io.res
//   sa.io.en  := (state =/= sLoad) && !io.res

//   // PipeSA delays load_bias by PIPE_DEPTH*(i+j) with the same en gating as the
//   // data, so a single pulse aligned to the first beat at PE(0,0) lands exactly
//   // on the wavefront everywhere else.
//   sa.io.load_bias := beatValid && !seenFirst

//   when(beatValid) { seenFirst := true.B }

//   // ---------------------------------------------------------------------------
//   // RESULT CAPTURE -- out_sum is NOT stable, it has to be sampled per PE
//   // ---------------------------------------------------------------------------
//   // PipeSA's recurrence is out(T) = out(T-6) + product(T-3), so the feedback
//   // path is a SIX-SLOT circular buffer, not a single accumulator. Exactly one
//   // slot ever receives a product; the other five hold zero and keep circulating.
//   // out_sum(i)(j) therefore shows the real total on one cycle in II and zero on
//   // the rest, forever.
//   //
//   // Worse, the live phase is per PE. PE(i,j) sees its beats at
//   // beat0 + II*k + PIPE_DEPTH*(i+j), so its result lands on cycles congruent to
//   // PIPE_DEPTH*(i+j) + PIPE_DEPTH (mod II). With PIPE_DEPTH=3 and II=6 that
//   // alternates between phase 0 and phase 3 with the parity of (i+j): there is NO
//   // single cycle on which the whole array is readable.
//   //
//   // So each PE is latched on its own offset from the last real beat, which is
//   // the first sDrain cycle (drainCnt == 0). Offsets are elaboration-time
//   // constants, so this is just an enable per register, no comparators.
//   val outReg = RegInit(VecInit(Seq.fill(rows)(VecInit(Seq.fill(cols)(0.U(32.W))))))

//   // ---------------------------------------------------------------------------
//   // outputs
//   // ---------------------------------------------------------------------------
//   io.out_sum  := outReg
//   io.ready_a  := state === sLoad && !spaA.io.full
//   io.ready_b  := state === sLoad && !spaB.io.full
//   io.busy     := state === sRun || state === sDrain
//   io.done     := state === sDone
//   io.conflict := spaA.io.conflict || spaB.io.conflict
//   io.dbg_beat := beatValid
//   io.dbg_row  := saRow
//   io.dbg_col  := saCol

//   // ---------------------------------------------------------------------------
//   // FSM
//   // ---------------------------------------------------------------------------
//   switch(state) {

//     is(sLoad) {
//       // hold both AGUs cleared so their edge-detected en sees a clean
//       // low-to-high transition on entry to sRun
//       aguA.io.res := true.B
//       aguB.io.res := true.B

//       spaA.io.wen := io.wen_a && !spaA.io.full
//       for (b <- 0 until rows) {
//         spaA.io.waddr(b) := spaA.io.count(spaA.addrWidth - 1, 0) + b.U
//       }

//       spaB.io.wen := io.wen_b && !spaB.io.full
//       for (b <- 0 until cols) {
//         spaB.io.waddr(b) := spaB.io.count(spaB.addrWidth - 1, 0) + b.U
//       }

//       phase     := 0.U
//       drainCnt  := 0.U
//       seenFirst := false.B
//       outReg.foreach(_.foreach(_ := 0.U))

//       when(spaA.io.full && spaB.io.full) {
//         state := sRun
//       }
//     }

//     is(sRun) {
//       aguA.io.en := true.B
//       aguB.io.en := true.B
//       aguA.io.advance.get := adv
//       aguB.io.advance.get := adv

//       // gated on valid, so the AGUs' pre-start register contents are never
//       // presented as an address, and no read is issued on stall cycles
//       spaA.io.ren := aguA.io.valid
//       for (b <- 0 until rows) {
//         spaA.io.raddr(b) := aguA.io.addr_out(b)(spaA.addrWidth - 1, 0)
//       }

//       spaB.io.ren := aguB.io.valid
//       for (b <- 0 until cols) {
//         spaB.io.raddr(b) := aguB.io.addr_out(b)(spaB.addrWidth - 1, 0)
//       }

//       // Free-run the phase counter only once the AGU is actually walking, so
//       // the first step lands on phase 0 rather than mid-interval.
//       when(aguA.io.busy) {
//         phase := Mux(phase === (II - 1).U, 0.U, phase + 1.U)
//       }.otherwise {
//         phase := 0.U
//       }

//       when(aguA.io.done) {
//         state    := sDrain
//         drainCnt := 0.U
//       }
//     }

//     is(sDrain) {
//       // en stays high and zeros keep flowing, flushing the skew buffers and
//       // walking the last wavefront out to the far corner of the array.
//       //
//       // drainCnt == 0 is the cycle the LAST real beat is presented, so PE(i,j)
//       // consumes it at drainCnt == PIPE_DEPTH*(i+j) and its result is on out_sum
//       // PIPE_DEPTH cycles after that. Latch each PE on exactly that cycle --
//       // one cycle earlier or later and the circular feedback buffer hands back
//       // one of the five empty slots instead.
//       drainCnt := drainCnt + 1.U

//       for (i <- 0 until rows; j <- 0 until cols) {
//         val when_ij = PIPE_DEPTH * (i + j) + PIPE_DEPTH
//         when(drainCnt === when_ij.U) {
//           outReg(i)(j) := sa.io.out_sum(i)(j)
//         }
//       }

//       when(drainCnt === (drainCycles - 1).U) {
//         state := sDone
//       }
//     }

//     is(sDone) {
//       // outReg holds the latched result, so unlike sa.io.out_sum this IS stable
//       // and can be read for as long as you like.
//       spaA.io.clr := true.B
//       spaB.io.clr := true.B
//     }
//   }

//   // synchronous abort/reload -- LAST so it wins over the FSM above
//   when(io.res) {
//     state     := sLoad
//     phase     := 0.U
//     drainCnt  := 0.U
//     seenFirst := false.B

//     aguA.io.res := true.B
//     aguB.io.res := true.B
//     aguA.io.en  := false.B
//     aguB.io.en  := false.B
//     aguA.io.advance.get := false.B
//     aguB.io.advance.get := false.B

//     spaA.io.wen := false.B
//     spaB.io.wen := false.B
//     spaA.io.ren := false.B
//     spaB.io.ren := false.B
//     spaA.io.clr := true.B
//     spaB.io.clr := true.B
//   }
// }
