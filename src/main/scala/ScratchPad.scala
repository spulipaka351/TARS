package Tars

import chisel3._
import chisel3.util._

class ScratchpadMP(
  val depth:        Int,
  val dataWidth:    Int,
  val busWidth:     Int,
  val numReadPorts: Int = 1
) extends Module {

  val addrWidth = log2Ceil(depth)

  val io = IO(new Bundle {
    val wen   = Input(Bool())
    val waddr = Input(Vec(busWidth, UInt(addrWidth.W)))
    val wdata = Input(Vec(busWidth, UInt(dataWidth.W)))

    val ren   = Input(Vec(numReadPorts, Bool()))
    val raddr = Input(Vec(numReadPorts, Vec(busWidth, UInt(addrWidth.W))))
    val rdata = Output(Vec(numReadPorts, Vec(busWidth, UInt(dataWidth.W))))

    val clr   = Input(Bool())
  })

  val mem = SyncReadMem(depth, UInt(dataWidth.W))

  when(io.wen) {
    for (i <- 0 until busWidth) {
      mem.write(io.waddr(i), io.wdata(i))
    }
  }

  for (p <- 0 until numReadPorts) {
    for (i <- 0 until busWidth) {
      io.rdata(p)(i) := mem.read(io.raddr(p)(i), io.ren(p))
    }
  }

  // NOTE: io.clr is still unimplemented here, exactly as in the original
  // Scratchpad -- it is accepted and ignored. Bulk-zeroing a SyncReadMem
  // needs its own multi-cycle sweep counter; it is NOT a 1-cycle operation.
  // Safe to ignore only because every load fully overwrites each region
  // before it is read back.
}
