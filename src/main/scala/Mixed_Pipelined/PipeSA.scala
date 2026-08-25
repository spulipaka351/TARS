package SA

import chisel3._
import chisel3.util._
import custom.{PEWrapper}

class PipeSA(val rows: Int, val cols: Int) extends Module {
  val io = IO(new Bundle {
    val en        = Input(Bool())
    val res = Input(Bool())
    val mode      = Input(Bool())
    val load_bias = Input(Bool())
    val in_a      = Input(Vec(rows, UInt(16.W)))
    val in_b      = Input(Vec(cols, UInt(16.W)))
    val bias      = Input(Vec(cols, UInt(16.W)))
    val out_sum   = Output(Vec(rows, Vec(cols, UInt(16.W))))
  })

  val pes = Seq.fill(rows)(Seq.fill(cols)(Module(new PEWrapper())))

  for (i <- 0 until rows) {
    for (j <- 0 until cols) {
      val pe = pes(i)(j)
      pe.io.mode := io.mode
      pe.io.clk := clock
      pe.io.rst :=io.res
      pe.io.a := (if (j == 0) io.in_a(i) else pes(i)(j-1).io.out_a)
      pe.io.b := (if (i == 0) io.in_b(j) else pes(i-1)(j).io.out_b)

      io.out_sum(i)(j) := pe.io.out
    }
  }
}