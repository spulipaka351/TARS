package Tars


import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class AGU_Test extends AnyFlatSpec with ChiselScalatestTester {
  
"AGU mode-0" should "pass" in {
  val OUT_WIDTH = 4
  test(new AGU(OUT_WIDTH)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
  val ROW = 4
  val COL = 4
  assert(OUT_WIDTH === COL, s"OUT_WIDTH ($OUT_WIDTH) must match COL ($COL) in the testbench")
   dut.io.res.poke(false.B)
    dut.io.en.poke(true.B)
    dut.io.baseAddr.poke(0.U)
    dut.io.mode.poke(0.U)
    dut.io.K.poke(COL.U)
    dut.io.N.poke(ROW.U)

    for (i <- 0 until ROW) {
        dut.clock.step(1)
        for (j <- 0 until COL) {
            val expected_addr = (i * COL + j).U
            dut.io.addr_out(j).expect(expected_addr)
            println(s"i: $i, j: $j, addr_out: ${dut.io.addr_out(j).peek().litValue}")
        }
        
        
        
    
    }

  }

}


"AGU mode-transpose" should "pass" in {
  val OUT_WIDTH = 4
  test(new AGU(OUT_WIDTH)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
  val ROW = 4
  val COL = 4
  val baseAddr = 128
  assert(OUT_WIDTH === COL, s"OUT_WIDTH ($OUT_WIDTH) must match COL ($COL) in the testbench")
   dut.io.res.poke(false.B)
    dut.io.en.poke(true.B)
    dut.io.baseAddr.poke(baseAddr.U)
    dut.io.mode.poke(1.U)
    dut.io.K.poke(COL.U)
    dut.io.N.poke(ROW.U)

    for (i <- 0 until ROW) {
        dut.clock.step(1)
        for (j <- 0 until COL) {
            val expected_addr = (baseAddr + (i + j*COL)).U
            dut.io.addr_out(j).expect(expected_addr)
            println(s"i: $i, j: $j, addr_out: ${dut.io.addr_out(j).peek().litValue}")
        }
        
        
        
    
    }

  }

}
}