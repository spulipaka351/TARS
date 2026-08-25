package Tars

import chisel3._
import chisel3.util._
import SA.Top
import custom.SoftmaxWrapper

// ==================================================================
// ACCEL_ATTN: full single-head attention datapath.
//
//   O = softmax(Q * K^T) * V
//
// Stage sequence:
//   sLoad     - fill Q, K, V regions (host pre-scales Q by 1/sqrt(d))
//   sQK       - Q*K^T on the systolic array   (both AGUs transpose)
//   sQKDrain  - flush array pipeline
//   sSoftmax  - stream out_sum rows -> Softmax -> write S back to scratchpad
//   sClear    - reset the array so S*V does not accumulate onto Q*K^T
//   sSV       - S*V on the systolic array     (AGU_S transpose, AGU_V normal)
//   sSVDrain  - flush array pipeline
//   sDone     - out_sum holds O, parked until io.res
//
// WHY S IS WRITTEN BACK TO THE SCRATCHPAD:
//   Softmax consumes S row-by-row (it normalizes across a row), but S*V
//   needs column t of S at step t. Producing in row order and consuming in
//   column order is a transpose. Rather than build a transpose network,
//   S is written back into its own scratchpad region and re-read with the
//   AGU in transpose mode -- the scratchpad IS the transpose unit.
// ==================================================================
class ACCEL_ATTN(
  val depth:     Int = 4096,
  val dataWidth: Int = 16,
  val busWidth:  Int = 32,
  val N:         Int = 32,   // AGU steps == inner dimension
  val K:         Int = 32,   // row pitch
  val drainCycles: Int = 0
) extends Module {

  // The Softmax blackbox has a hardwired 512-bit row = 32 FP16 lanes.
  // This is NOT parameterizable, so the whole pipeline is pinned to 32.
  require(busWidth == 32,
    "Softmax's 512-bit row fixes busWidth at 32 FP16 lanes")
  require(dataWidth == 16, "Top and Softmax both expect 16-bit lanes")

  // FOUR regions now: Q, K, V, S.
  require(depth % 4 == 0, "depth must be divisible by 4")
  val regionWords = depth / 4
  require(regionWords % busWidth == 0,
    "each region must be busWidth aligned")
  require(regionWords >= busWidth * K,
    "each region must hold a full busWidth x K matrix")

  val saRows = busWidth
  val saCols = busWidth

  val effDrain = if (drainCycles > 0) drainCycles else (saRows + saCols + 8)

  val io = IO(new Bundle {
    val res = Input(Bool())
    val en  = Input(Bool())
    val d = Input(UInt(8.W))
    val sa_mode = Input(Bool())   // 1 = FP16 (confirmed against hardware)

    val data_in = Input(Vec(busWidth, UInt(dataWidth.W)))

    val Q_base = Input(UInt(32.W))
    val K_base = Input(UInt(32.W))
    val V_base = Input(UInt(32.W))
    val S_base = Input(UInt(32.W))   // scratch region for softmax output

    // FP16 in the low 16 bits of each 32-bit lane (confirmed).
    val out_sum = Output(Vec(saRows, Vec(saCols, UInt(32.W))))

    val valid = Output(Bool())
    val done  = Output(Bool())
    val ready = Output(Bool())

    // Stage visibility -- makes a stuck pipeline debuggable from the TB
    // instead of just timing out.
    val dbg_state = Output(UInt(3.W))
  })

  // ================================================================
  // Modules
  // ================================================================

  val spa = Module(new ScratchpadMP(depth, dataWidth, busWidth, numReadPorts = 2))
  val aguA = Module(new AGU(busWidth))   // Q in stage 1, S in stage 2
  val aguB = Module(new AGU(busWidth))   // K in stage 1, V in stage 2
  val sa   = Module(new Top(saRows, saCols))
  val sm   = Module(new SoftmaxWrapper())

  // ================================================================
  // FSM
  // ================================================================

  val sLoad :: sQK :: sQKDrain :: sSoftmax :: sClear :: sSV :: sSVDrain :: sDone :: Nil = Enum(8)
  val state = RegInit(sLoad)
  io.dbg_state := state

  val qBaseReg = RegInit(0.U(32.W))
  val kBaseReg = RegInit(0.U(32.W))
  val vBaseReg = RegInit(0.U(32.W))
  val sBaseReg = RegInit(0.U(32.W))

  when(io.res) {
    qBaseReg := io.Q_base
    kBaseReg := io.K_base
    vBaseReg := io.V_base
    sBaseReg := io.S_base
  }

  val phase     = RegInit(0.U(2.W))   // 0=Q 1=K 2=V during load
  val loadCount = RegInit(0.U(32.W))

  val loadBase = Wire(UInt(32.W))
  loadBase := qBaseReg
  switch(phase) {
    is(0.U) { loadBase := qBaseReg }
    is(1.U) { loadBase := kBaseReg }
    is(2.U) { loadBase := vBaseReg }
  }

  // ================================================================
  // Defaults
  // ================================================================

  spa.io.wen   := false.B
  spa.io.clr   := false.B
  spa.io.wdata := io.data_in
  for (b <- 0 until busWidth) { spa.io.waddr(b) := 0.U }
  for (p <- 0 until 2) {
    spa.io.ren(p) := false.B
    for (b <- 0 until busWidth) { spa.io.raddr(p)(b) := 0.U }
  }

  for (a <- Seq(aguA, aguB)) {
    a.io.res := false.B
    a.io.en  := false.B
    a.io.K   := K.U
    a.io.N   := N.U
  }

  val inSV = state === sSV

  // Stage 1: Q (transpose) x K (transpose)  -> see ACCEL_QK
  // Stage 2: S (transpose) x V (NORMAL)
  //   O[i][j] = sum_t S[i][t] * V[t][j]
  //   step t needs column t of S  -> transpose
  //             and row t of V    -> normal
  aguA.io.baseAddr := Mux(inSV, sBaseReg, qBaseReg)
  aguA.io.mode     := true.B                 // always column access
  aguB.io.baseAddr := Mux(inSV, vBaseReg, kBaseReg)
  aguB.io.mode     := !inSV                  // K transpose, V normal

  // ================================================================
  // Array feed
  // ================================================================

  val running   = (state === sQK) || inSV
  val draining  = (state === sQKDrain) || (state === sSVDrain)

  val feedValid = RegNext(aguA.io.valid && running && !io.res, false.B)
  val lastFeed  = RegNext(aguA.io.done  && running && !io.res, false.B)

  val zeros = VecInit(Seq.fill(busWidth)(0.U(dataWidth.W)))

  // Pulsing sa.io.res between the two matmuls is what clears the stationary
  // PE accumulators. WITHOUT THIS, S*V accumulates on top of Q*K^T.
  // ASSUMPTION: Top's res clears the PE accumulator registers. If PipeSA's
  // reset does not reach them, this must instead be done via load_bias with
  // a zero bias vector -- see the note in the accompanying testbench.
  val clearing = state === sClear
  sa.io.res       := io.res || clearing
  sa.io.en        := feedValid || draining
  sa.io.mode      := io.sa_mode
  sa.io.load_bias := false.B
  sa.io.bias      := VecInit(Seq.fill(saCols)(0.U(32.W)))
  sa.io.row       := Mux(feedValid, spa.io.rdata(0), zeros)
  sa.io.col       := Mux(feedValid, spa.io.rdata(1), zeros)

  val drainCount = RegInit(0.U(32.W))

  // ================================================================
  // Softmax stage
  //
  // out_sum row i is already a Vec of FP16-in-low-16-bits, and Softmax
  // wants lane j at bits [16j+15 : 16j]. Vec.asUInt puts element 0 in the
  // low bits, which is exactly that packing -- no reordering needed.
  // ================================================================

  val smRowIdx  = RegInit(0.U(log2Ceil(busWidth + 1).W))  // rows pushed in
  val smOutIdx  = RegInit(0.U(log2Ceil(busWidth + 1).W))  // rows written back
  val smPushing = (state === sSoftmax) && (smRowIdx < busWidth.U)

  val smRow = VecInit((0 until saCols).map { j =>
    sa.io.out_sum(smRowIdx)(j)(15, 0)
  })

  sm.io.row      := smRow.asUInt
  sm.io.in_valid := smPushing

  // Softmax output row -> scratchpad writeback at S_base + smOutIdx*K + j
  val smOutLanes = VecInit((0 until busWidth).map { j =>
    sm.io.out(16 * j + 15, 16 * j)
  })

  when(state === sSoftmax) {
    when(smPushing) {
      smRowIdx := smRowIdx + 1.U
    }

    when(sm.io.out_valid) {
      spa.io.wen   := true.B
      spa.io.wdata := smOutLanes
      for (b <- 0 until busWidth) {
        // row-major writeback: row smOutIdx, lane b
        spa.io.waddr(b) :=
          (sBaseReg + smOutIdx * K.U)(spa.addrWidth - 1, 0) + b.U
      }
      smOutIdx := smOutIdx + 1.U
    }

    // all rows have come back out of the softmax pipeline
    when(smOutIdx === busWidth.U) {
      state := sClear
    }
  }

  // ================================================================
  // LOAD
  // ================================================================

  when(state === sLoad) {
    aguA.io.res := true.B
    aguB.io.res := true.B

    spa.io.wen := io.en
    for (b <- 0 until busWidth) {
      spa.io.waddr(b) :=
        loadBase(spa.addrWidth - 1, 0) +
        loadCount(spa.addrWidth - 1, 0) + b.U
    }

    val lastRegionWrite = loadCount === (regionWords - busWidth).U
    when(io.en) {
      when(lastRegionWrite) {
        loadCount := 0.U
        when(phase === 2.U) { state := sQK }
          .otherwise { phase := phase + 1.U }
      }.otherwise {
        loadCount := loadCount + busWidth.U
      }
    }
  }

  // ================================================================
  // RUN (shared by sQK and sSV -- only the AGU bases/modes differ)
  // ================================================================

  when(running) {
    aguA.io.en := true.B
    aguB.io.en := true.B

    spa.io.ren(0) := aguA.io.valid
    spa.io.ren(1) := aguB.io.valid
    for (b <- 0 until busWidth) {
      spa.io.raddr(0)(b) := aguA.io.addr_out(b)(spa.addrWidth - 1, 0)
      spa.io.raddr(1)(b) := aguB.io.addr_out(b)(spa.addrWidth - 1, 0)
    }

    when(lastFeed) {
      drainCount := 0.U
      state := Mux(inSV, sSVDrain, sQKDrain)
    }
  }

  // ================================================================
  // DRAIN (shared)
  // ================================================================

  when(draining) {
    drainCount := drainCount + 1.U
    when(drainCount === (effDrain - 1).U) {
      when(state === sQKDrain) {
        smRowIdx := 0.U
        smOutIdx := 0.U
        state    := sSoftmax
      }.otherwise {
        state := sDone
      }
    }
  }

  // ================================================================
  // CLEAR: one cycle of sa.io.res, then start S*V
  // ================================================================

  when(clearing) {
    aguA.io.res := true.B
    aguB.io.res := true.B
    state := sSV
  }

  // ================================================================
  // Outputs
  // ================================================================

  io.out_sum := sa.io.out_sum
  io.valid   := state === sDone
  io.done    := (state === sSVDrain) && (drainCount === (effDrain - 1).U)
  io.ready   := state === sLoad

  // ================================================================
  // RESET
  // ================================================================

  when(io.res) {
    state      := sLoad
    phase      := 0.U
    loadCount  := 0.U
    drainCount := 0.U
    smRowIdx   := 0.U
    smOutIdx   := 0.U

    aguA.io.res := true.B
    aguA.io.en  := false.B
    aguB.io.res := true.B
    aguB.io.en  := false.B

    spa.io.wen := false.B
    for (p <- 0 until 2) { spa.io.ren(p) := false.B }
    spa.io.clr := true.B
  }
}