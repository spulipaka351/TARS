package SA

import chisel3._
import chisel3.util._

class StridedSkewBuffers(val n: Int, val width: Int, val stride: Int) extends Module {
  val io = IO(new Bundle {
    val res = Input(Bool())
    val en  = Input(Bool())
    val inp = Input(Vec(n, UInt(width.W)))
    val out = Output(Vec(n, UInt(width.W)))
  })

  for (i <- 0 until n) {
    val safe_in = Mux(io.res, 0.U, io.inp(i))
    val delay   = i * stride
    if (delay == 0)
      io.out(i) := safe_in
    else
      io.out(i) := ShiftRegister(safe_in, delay, 0.U, io.en)
  }
}

class Top(val rows: Int, val cols: Int) extends Module {

  // Two distinct latencies inside the PE — don't conflate them:
  val FORWARD_HOP_LATENCY = 1   // out_a/out_b register delay per PE hop → skew stride
  val io = IO(new Bundle {
    val res  = Input(Bool())
    val en   = Input(Bool())
    val mode = Input(Bool())
    val load_bias = Input(Bool())
    val bias = Input(Vec(cols, UInt(32.W)))
    val row  = Input(Vec(rows, UInt(16.W)))
    val col  = Input(Vec(cols, UInt(16.W)))

    val out_sum = Output(Vec(rows, Vec(cols, UInt(32.W))))
  })

  val skew_a = Module(new StridedSkewBuffers(rows, 16, FORWARD_HOP_LATENCY))
  skew_a.io.inp := io.row
  skew_a.io.en  := io.en
  skew_a.io.res := io.res

  val skew_b = Module(new StridedSkewBuffers(cols, 16, FORWARD_HOP_LATENCY))
  skew_b.io.inp := io.col
  skew_b.io.en  := io.en
  skew_b.io.res := io.res

  val sa = Module(new PipeSA(rows, cols))
  sa.io.in_a := skew_a.io.out
  sa.io.in_b := skew_b.io.out
  sa.io.res  := io.res
  sa.io.en   := io.en
  sa.io.mode := io.mode
  sa.io.load_bias := io.load_bias
  sa.io.bias := io.bias
  io.out_sum := sa.io.out_sum
}