package custom

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import scala.math.exp

class FP16MultiplierBB() extends BlackBox() with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val a   = Input(UInt(16.W))
    val b   = Input(UInt(16.W))
    val out = Output(UInt(16.W))
  })
  override def desiredName: String = "FP16Multiplier"
  addResource("Softmax/Softmax.sv")
}

class FP16MultiplierWrapper() extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(16.W))
    val b   = Input(UInt(16.W))
    val out = Output(UInt(16.W))
  })
  val u = Module(new FP16MultiplierBB())
  u.io.clk := clock
  u.io.rst := reset.asBool
  u.io.a   := io.a
  u.io.b   := io.b
  io.out   := u.io.out
}

class FP16Multiplier_Test extends AnyFlatSpec with ChiselScalatestTester {
  def FloatToFP16(f: Float): Int = {
    val bits = java.lang.Float.floatToIntBits(f)
    val sign = (bits >> 31) & 0x1
    val exponent = (bits >> 23) & 0xFF
    val mantissa = bits & 0x7FFFFF
    val newExp = exponent - 112
    if (newExp <= 0) sign << 15
    else (sign << 15) | (newExp << 10) | (mantissa >> 13)
  }
  def FP16ToFloat(fp16: Int): Float = {
    val sign = (fp16 >> 15) & 0x1
    val exponent = (fp16 >> 10) & 0x1F
    val mantissa = fp16 & 0x3FF
    if (exponent == 0) { if (mantissa == 0) 0.0f else java.lang.Float.intBitsToFloat((sign<<31)|(120<<23)|(mantissa<<13)) }
    else java.lang.Float.intBitsToFloat((sign<<31)|((exponent+112)<<23)|(mantissa<<13))
  }

  "FP16Multiplier" should "compute a * b correctly" in {
    test(new FP16MultiplierWrapper()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

      val cases = Seq((0.0044288635f, 0.19995117f), (0.5f, 0.5f), (1.0f, 0.2f), (0.194887f, 0.19995117f))

      for ((av, bv) <- cases) {
        dut.io.a.poke((FloatToFP16(av) & 0xFFFF).U)
        dut.io.b.poke((FloatToFP16(bv) & 0xFFFF).U)
        dut.clock.step(4)
        val raw = dut.io.out.peek().litValue.toInt & 0xFFFF
        println(f"$av%.8f * $bv%.8f = ${FP16ToFloat(raw)}%.8f  (expected ${av*bv}%.8f)")
      }
    }
  }
}