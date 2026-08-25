package Tars

import chisel3._
import chisel3.util._

class AGU(val width: Int, val hasAdvance: Boolean = false) extends Module {
  require(width > 0, "width must be positive")

  val io = IO(new Bundle {
    val res      = Input(Bool())
    val en       = Input(Bool())
    val mode     = Input(Bool()) // 0 = normal, 1 = transpose

    val baseAddr = Input(UInt(32.W))
    val K        = Input(UInt(32.W))
    val N        = Input(UInt(32.W))

    val addr_out = Output(Vec(width, UInt(32.W)))
    val valid    = Output(Bool())
    val done     = Output(Bool())
    val busy     = Output(Bool())
  })

  // ------------------------------------------------------------
  // State
  // ------------------------------------------------------------

  val addr_reg = RegInit(
    VecInit(Seq.fill(width)(0.U(32.W)))
  )

  val busy = RegInit(false.B)
  val i    = RegInit(0.U(32.W))

  // Configuration latched at start
  val kReg    = RegInit(0.U(32.W))
  val nReg    = RegInit(0.U(32.W))
  val modeReg = RegInit(false.B)
  
  // ------------------------------------------------------------
  // Rising-edge detection on en
  // ------------------------------------------------------------

  val enPrev = RegNext(io.en, false.B)
  val start  = io.en && !enPrev

  // ------------------------------------------------------------
  // Last cycle
  // ------------------------------------------------------------

  val last = busy && (i === (nReg - 1.U))

  // ------------------------------------------------------------
  // Outputs
  // ------------------------------------------------------------

  io.addr_out := addr_reg

  io.valid := busy && !io.res

  io.busy := busy

  io.done := last && !io.res

  // ------------------------------------------------------------
  // Control
  // ------------------------------------------------------------

  when(io.res) {

    busy := false.B
    i    := 0.U

    addr_reg := VecInit(
      Seq.fill(width)(0.U(32.W))
    )

  }.elsewhen(start && io.N =/= 0.U) {

    // ----------------------------------------------------------
    // Latch configuration
    // ----------------------------------------------------------

    kReg    := io.K
    nReg    := io.N
    modeReg := io.mode
    
    i    := 0.U
    busy := true.B

    // ----------------------------------------------------------
    // Initial addresses
    //
    // Normal:
    //     addr[j] = base + j
    //
    // Transpose:
    //     addr[j] = base + j*K
    // ----------------------------------------------------------

    for (j <- 0 until width) {

      when(io.mode) {
        // Transpose
        addr_reg(j) :=
          (io.baseAddr + (j.U * io.K))(31, 0)
      }.otherwise {
        // Normal
        addr_reg(j) :=
          (io.baseAddr + j.U)(31, 0)
      }
    }

  }.elsewhen(busy) {

    when(last) {

      // Final address remains visible/valid for this cycle
      busy := false.B

    }.otherwise {

      // --------------------------------------------------------
      // Advance addresses
      //
      // Normal:
      //     addr += K
      //
      // Transpose:
      //     addr += 1
      // --------------------------------------------------------

      for (j <- 0 until width) {

        when(modeReg) {
          // Transpose
          addr_reg(j) :=
            (addr_reg(j) + 1.U)(31, 0)
        }.otherwise {
          // Normal
          addr_reg(j) :=
            (addr_reg(j) + kReg)(31, 0)
        }
      }

      i := i + 1.U
    }
  }
}