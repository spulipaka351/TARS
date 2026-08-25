package Tars

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import SA.{PipeSA,Top}

class SA_TB extends AnyFlatSpec with ChiselScalatestTester {

  def FloatToFP16(f: Float): Int = {
    val bits = java.lang.Float.floatToIntBits(f)

    val sign     = (bits >> 31) & 0x1
    val exponent = (bits >> 23) & 0xFF
    val mantissa = bits & 0x7FFFFF

    if (exponent == 0xFF) {
      if (mantissa == 0)
        (sign << 15) | 0x7C00
      else
        (sign << 15) | 0x7E00

    } else if (exponent == 0 && mantissa == 0) {
      sign << 15

    } else {
      val newExp = exponent - 112

      if (newExp >= 31)
        (sign << 15) | 0x7C00

      else if (newExp <= 0)
        sign << 15

      else {
        val newMantissa = mantissa >> 13
        (sign << 15) | (newExp << 10) | newMantissa
      }
    }
  }

  def FP16ToFloat(fp16: Int): Float = {
    val sign     = (fp16 >> 15) & 0x1
    val exponent = (fp16 >> 10) & 0x1F
    val mantissa = fp16 & 0x3FF

    if (exponent == 0) {

      if (mantissa == 0) {
        0.0f
      } else {
        java.lang.Float.intBitsToFloat(
          (sign << 31) | (120 << 23) | (mantissa << 13)
        )
      }

    } else if (exponent == 31) {

      if (mantissa == 0) {
        if (sign == 0) Float.PositiveInfinity
        else Float.NegativeInfinity
      } else {
        Float.NaN
      }

    } else {

      val exp32 = exponent + 112

      val bits =
        (sign << 31) |
        (exp32 << 23) |
        (mantissa << 13)

      java.lang.Float.intBitsToFloat(bits)
    }
  }


   "Top" should "perform 2x2 FP16 matrix multiplication with internal skewing" in {

    test(new Top(rows = 2, cols = 2))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>

      // A = [1 2; 3 4], B = [5 6; 7 8], expected C = [19 22; 43 50]
      val A = Array(Array(1.0f, 2.0f), Array(3.0f, 4.0f))
      val B = Array(Array(5.0f, 6.0f), Array(7.0f, 8.0f))
      val expected = Array(Array(19.0f, 22.0f), Array(43.0f, 50.0f))

      // ------------------------------------------------------------
      // Reset. Note: io.res is a separate payload-level reset that
      // feeds the skew buffers + PE rst pins — it's NOT the same as
      // dut.reset (Chisel's implicit hardware reset). Drive both.
      // ------------------------------------------------------------
      dut.reset.poke(true.B)
      dut.io.res.poke(false.B)
      dut.io.en.poke(true.B)
      dut.io.mode.poke(true.B)        // FP16
      dut.io.load_bias.poke(false.B)
      for (j <- 0 until 2) dut.io.bias(j).poke(0.U)
      for (i <- 0 until 2) { dut.io.row(i).poke(0.U); dut.io.col(i).poke(0.U) }
      dut.clock.step(3)
      dut.reset.poke(false.B)

      // ------------------------------------------------------------
      // No manual i+k / j+k skew math needed here — StridedSkewBuffers
      // does that internally now. Just present each contraction step
      // k as column k of A (across rows) and row k of B (across cols),
      // one cycle per k, then hold zeros while the array settles.
      // ------------------------------------------------------------
      val K = 2
      val settleCycles = 4

      var c00, c01, c10, c11 = 0.0f

      for (cycle <- 0 until (K + settleCycles)) {

        if (cycle < K) {
          for (i <- 0 until 2) dut.io.row(i).poke((FloatToFP16(A(i)(cycle)) & 0xFFFF).U)
          for (j <- 0 until 2) dut.io.col(j).poke((FloatToFP16(B(cycle)(j)) & 0xFFFF).U)
        } else {
          for (i <- 0 until 2) dut.io.row(i).poke(0.U)
          for (j <- 0 until 2) dut.io.col(j).poke(0.U)
        }

        dut.clock.step(1)

        c00 = FP16ToFloat(dut.io.out_sum(0)(0).peek().litValue.toInt & 0xFFFF)
        c01 = FP16ToFloat(dut.io.out_sum(0)(1).peek().litValue.toInt & 0xFFFF)
        c10 = FP16ToFloat(dut.io.out_sum(1)(0).peek().litValue.toInt & 0xFFFF)
        c11 = FP16ToFloat(dut.io.out_sum(1)(1).peek().litValue.toInt & 0xFFFF)

        println(f"cycle=$cycle%2d  C00=$c00%7.2f  C01=$c01%7.2f  C10=$c10%7.2f  C11=$c11%7.2f")
      }

      assert(math.abs(c00 - expected(0)(0)) < 0.1, s"C00 mismatch: got $c00")
      assert(math.abs(c01 - expected(0)(1)) < 0.1, s"C01 mismatch: got $c01")
      assert(math.abs(c10 - expected(1)(0)) < 0.1, s"C10 mismatch: got $c10")
      assert(math.abs(c11 - expected(1)(1)) < 0.1, s"C11 mismatch: got $c11")
    }
  }
}