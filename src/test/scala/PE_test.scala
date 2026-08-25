package custom

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorFlags
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class PEWrapperTest extends AnyFlatSpec with ChiselScalatestTester {
def FloatToFP16(f: Float): Int = {
    val bits = java.lang.Float.floatToIntBits(f)
    val sign = (bits >> 31) & 0x1
    val exponent = (bits >> 23) & 0xFF
    val mantissa = bits & 0x7FFFFF
    
    if (exponent == 0xFF) {
      // Infinity or NaN
      if (mantissa == 0) ((sign << 15) | 0x7C00).toShort
      else ((sign << 15) | 0x7E00).toShort
    } else if (exponent == 0 && mantissa == 0) {
      // Zero
      (sign << 15).toShort
    } else {
      // Normal number
      val new_exp = exponent - 112
      if (new_exp >= 31) ((sign << 15) | 0x7C00).toShort
      else if (new_exp <= 0) (sign << 15).toShort
      else {
        val new_mantissa = mantissa >> 13
        ((sign << 15) | (new_exp << 10) | new_mantissa).toShort
      }
    }
  }
  
  // Helper function to convert FP16 to float
  def FP16ToFloat(fp16: Int): Float = {
    val sign = (fp16 >> 15) & 0x1
    val exponent = (fp16 >> 10) & 0x1F
    val mantissa = fp16 & 0x3FF
    
    if (exponent == 0) {
      if (mantissa == 0) 0.0f
      else java.lang.Float.intBitsToFloat((sign << 31) | (120 << 23) | (mantissa << 13))
    } else if (exponent == 31) {
      if (mantissa == 0) {
        if (sign == 0) Float.PositiveInfinity else Float.NegativeInfinity
      } else Float.NaN
    } else {
      val exp32 = exponent + 112
      val bits = (sign << 31) | (exp32 << 23) | (mantissa << 13)
      java.lang.Float.intBitsToFloat(bits)
    }
  }
  // FP16 constants


  "PEWrapper" should "accumulate Random positive FP16 numbers" in {
    test(new PEWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>

        // reset
        dut.io.mode.poke(true.B)          // FP16
        dut.io.a.poke(0.U)
        dut.io.b.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(3)
        dut.reset.poke(false.B)

        // stream 1.0 * 1.0 for 4 cycles
val N = 4
val LATENCY = 3

val rand = new Random(42)

val expected = Array.fill[Float](N + LATENCY)(0.0f)

var mac = 0.0f

val inputA = Array.ofDim[Float](N)
val inputB = Array.ofDim[Float](N)

// Generate inputs and software reference
for (i <- 0 until N) {
    val a = rand.nextFloat() * 4
    val b = rand.nextFloat() * 4

    inputA(i) = a
    inputB(i) = b

    // Important: reference should use quantized FP16 values
    val a16 = FloatToFP16(a) & 0xFFFF
    val b16 = FloatToFP16(b) & 0xFFFF

    val aq = FP16ToFloat(a16)
    val bq = FP16ToFloat(b16)

    mac += aq * bq

    expected(i + LATENCY) = mac
}


for (cycle <- 0 until N + LATENCY) {

    // Feed PE
    if (cycle < N) {
        dut.io.a.poke((FloatToFP16(inputA(cycle)) & 0xFFFF).U)
        dut.io.b.poke((FloatToFP16(inputB(cycle)) & 0xFFFF).U)
    } else {
        dut.io.a.poke(0.U)
        dut.io.b.poke(0.U)
    }

    // Advance
    dut.clock.step(1)

    // Read output
    val raw = dut.io.out.peek().litValue.toInt
    val actual = FP16ToFloat(raw)

    println(
        f"cycle=$cycle%2d " +
        f"expected=${expected(cycle)}%8.4f " +
        f"actual=$actual%8.4f"
    )

    // Check valid outputs
    if (cycle >= LATENCY) {
        assert(
            math.abs(actual - expected(cycle)) < 0.02f,
            f"Mismatch cycle $cycle: expected=${expected(cycle)}%.4f actual=$actual%.4f"
        )
    }
}

} 

       
                    
      
  }



"pe should multiply negative numbers" should "pass" in{
  test(new PEWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>

        // reset
        dut.io.mode.poke(true.B)          // FP16
        dut.io.a.poke(0.U)
        dut.io.b.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(3)
        dut.reset.poke(false.B)

        // stream 1.0 * 1.0 for 4 cycles
val N = 4
val LATENCY = 3

val rand = new Random(42)

val expected = Array.fill[Float](N + LATENCY)(0.0f)

var mac = 0.0f

val inputA = Array.ofDim[Float](N)
val inputB = Array.ofDim[Float](N)

// Generate inputs and software reference
for (i <- 0 until N) {
    val a = -(rand.nextFloat() * 7.0f)
val b =  (rand.nextFloat() * 7.0f)

    inputA(i) = a
    inputB(i) = b

    // Important: reference should use quantized FP16 values
    val a16 = FloatToFP16(a) & 0xFFFF
    val b16 = FloatToFP16(b) & 0xFFFF

    val aq = FP16ToFloat(a16)
    val bq = FP16ToFloat(b16)

    mac += aq * bq

    expected(i + LATENCY) = mac
}


for (cycle <- 0 until N + LATENCY) {

    // Feed PE
    if (cycle < N) {
        dut.io.a.poke((FloatToFP16(inputA(cycle)) & 0xFFFF).U)
        dut.io.b.poke((FloatToFP16(inputB(cycle)) & 0xFFFF).U)
    } else {
        dut.io.a.poke(0.U)
        dut.io.b.poke(0.U)
    }

    // Advance
    dut.clock.step(1)

    // Read output
    val raw = dut.io.out.peek().litValue.toInt
    val actual = FP16ToFloat(raw)

    println(
        f"cycle=$cycle%2d " +
        f"expected=${expected(cycle)}%8.4f " +
        f"actual=$actual%8.4f"
    )

    // Check valid outputs
    if (cycle >= LATENCY) {
        assert(
            math.abs(actual - expected(cycle)) < 0.02f,
            f"Mismatch cycle $cycle: expected=${expected(cycle)}%.4f actual=$actual%.4f"
        )
    }
}

} 
}

"PEWrapper" should "accumulate while switching modes" in {
    test(new PEWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      
      
      val floating = Array(1.2f,2.2f,-2.22f,-1.0f)
      val int8 = Array(1,2,3,4)
      val modes = Array(false,true,false,true)
      val expected = Array(
  1.0f,   // INT8 transaction 0
  2.2f,   // FP16 transaction 1
  4.0f,   // INT8 transaction 2
  1.2f    // FP16 transaction 3
)
       dut.reset.poke(true.B)
    dut.clock.step(3)
    dut.reset.poke(false.B)

    for (cycle <- 0 until 4) {

      if (modes(cycle)) {
        // FP16
        dut.io.mode.poke(true.B)

        dut.io.a.poke(
          (FloatToFP16(floating(cycle)) & 0xFFFF).U
        )

        dut.io.b.poke(
          (FloatToFP16(1.0f) & 0xFFFF).U
        )

      } else {
        // INT8
        dut.io.mode.poke(false.B)

        dut.io.a.poke((int8(cycle) & 0xFF).U)
        dut.io.b.poke((1 & 0xFF).U)
      }

      dut.clock.step(1)
    }

    // Flush pipeline
    for (cycle <- 0 until 4) {
  dut.io.a.poke(0.U)
  dut.io.b.poke(0.U)

  dut.clock.step(1)

  val raw = dut.io.out.peek().litValue.toInt
  val actual =
    if (modes(cycle))
      FP16ToFloat(raw)
    else
      raw.toByte.toInt

  println(
    f"cycle=${cycle + 4}%d actual=$actual%.4f expected=${expected(cycle)}%.4f"
  )
}
      }
      
      }




}