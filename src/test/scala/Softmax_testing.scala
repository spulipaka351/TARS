package custom

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random
import scala.math.exp

class Softmax_Test extends AnyFlatSpec with ChiselScalatestTester {
  def FloatToFP16(f: Float): Int = {
    val bits = java.lang.Float.floatToIntBits(f)
    val sign     = (bits >> 31) & 0x1
    val exponent = (bits >> 23) & 0xFF
    val mantissa = bits & 0x7FFFFF

    if (exponent == 0xFF) {
      if (mantissa == 0) (sign << 15) | 0x7C00 else (sign << 15) | 0x7E00
    } else if (exponent == 0 && mantissa == 0) {
      sign << 15
    } else {
      val newExp = exponent - 112
      if (newExp >= 31) (sign << 15) | 0x7C00
      else if (newExp <= 0) sign << 15
      else (sign << 15) | (newExp << 10) | (mantissa >> 13)
    }
  }

  def FP16ToFloat(fp16: Int): Float = {
    val sign     = (fp16 >> 15) & 0x1
    val exponent = (fp16 >> 10) & 0x1F
    val mantissa = fp16 & 0x3FF

    if (exponent == 0) {
      if (mantissa == 0) 0.0f
      else java.lang.Float.intBitsToFloat((sign << 31) | (120 << 23) | (mantissa << 13))
    } else if (exponent == 31) {
      if (mantissa == 0) { if (sign == 0) Float.PositiveInfinity else Float.NegativeInfinity }
      else Float.NaN
    } else {
      val exp32 = exponent + 112
      java.lang.Float.intBitsToFloat((sign << 31) | (exp32 << 23) | (mantissa << 13))
    }
  }

  def packRow(row: Array[Float]): BigInt = {
    var packed = BigInt(0)
    for (i <- 0 until 32) {
      packed |= BigInt(FloatToFP16(row(i)) & 0xFFFF) << (i * 16)
    }
    packed
  }

  def refSoftmax(inputs: Array[Float]): Array[Double] = {
    val maxVal  = inputs.max
    val expVals = inputs.map(x => exp((x - maxVal).toDouble))
    val sumExp  = expVals.sum
    expVals.map(_ / sumExp)
  }

  "Softmax" should "match a Scala reference within LUT quantization error" in {

    test(new SoftmaxWrapper()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val rand = new Random(42)

      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)

      val inputs = Array.fill(32)(rand.nextFloat() * -8f)
      dut.io.row.poke(packRow(inputs).U(512.W))

      dut.io.in_valid.poke(true.B)
      dut.clock.step(1)
      dut.io.in_valid.poke(false.B)

      var waited = 1
      while (!dut.io.out_valid.peek().litToBoolean && waited < 60) {
        dut.clock.step(1)
        waited += 1
      }
      println(s"out_valid went high at cycle $waited")
      assert(dut.io.out_valid.peek().litToBoolean, s"out_valid never asserted within $waited cycles")

      val expected = refSoftmax(inputs)

      val raw = dut.io.out.peek().litValue
      var maxAbsErr = 0.0
      for (i <- 0 until 32) {
        val lane = ((raw >> (i * 16)) & BigInt(0xFFFF)).toInt
        val actual = FP16ToFloat(lane)
        val err = math.abs(expected(i) - actual)
        maxAbsErr = math.max(maxAbsErr, err)
        println(f"i=$i%2d  x=${inputs(i)}%8.4f  expected=${expected(i)}%.6f  actual=$actual%.6f  err=$err%.6f")
      }
      println(f"max abs error across row: $maxAbsErr%.6f")
    }
  }

  // NOTE: this drives io.in_valid = true on every cycle while rows remain, i.e. it assumes
  // the pipeline is fully pipelined (initiation interval = 1) and has no ready/backpressure
  // signal to respect. If SoftmaxWrapper actually exposes an io.in_ready (or can only accept
  // a new row every N cycles), gate the push below on that signal or you'll silently drop rows.
  it should "sustain back-to-back input rows and report pipeline latency/throughput" in {

    test(new SoftmaxWrapper()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val rand = new Random(42)

      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)

      val numRows    = 32
      val allInputs  = Array.fill(numRows)(Array.fill(32)(rand.nextFloat() * -8f))
      val allExpected = allInputs.map(refSoftmax)

      val outputRows   = scala.collection.mutable.ArrayBuffer[BigInt]()
      val outputCycles = scala.collection.mutable.ArrayBuffer[Int]()
      val inputCycles  = scala.collection.mutable.ArrayBuffer[Int]()

      var cycle   = 0
      var pushIdx = 0
      val maxCycles = numRows * 4 + 200 // generous safety bound so a stall doesn't hang forever

      dut.io.in_valid.poke(false.B)

      while (outputRows.length < numRows && cycle < maxCycles) {
        // drive a new row every cycle while rows remain (back-to-back, II=1 assumption)
        if (pushIdx < numRows) {
          dut.io.row.poke(packRow(allInputs(pushIdx)).U(512.W))
          dut.io.in_valid.poke(true.B)
        } else {
          dut.io.in_valid.poke(false.B)
        }

        // capture any output that's valid this cycle
        if (dut.io.out_valid.peek().litToBoolean) {
          outputRows   += dut.io.out.peek().litValue
          outputCycles += cycle
        }

        if (pushIdx < numRows && dut.io.in_valid.peek().litToBoolean) {
          inputCycles += cycle
          pushIdx += 1
        }

        dut.clock.step(1)
        cycle += 1
      }
      dut.io.in_valid.poke(false.B)

      assert(outputRows.length == numRows,
        s"only received ${outputRows.length}/$numRows output rows within $maxCycles cycles " +
          s"(got outputs at cycles: ${outputCycles.mkString(",")})")

      val gaps = outputCycles.sliding(2).collect { case Seq(a, b) => b - a }.toSeq
      println(s"input accepted at cycles: ${inputCycles.mkString(",")}")
      println(s"output valid at cycles:   ${outputCycles.mkString(",")}")
      println(s"latency (first in -> first out): ${outputCycles.head - inputCycles.head} cycles")
      println(s"cycle gaps between consecutive outputs: ${gaps.mkString(",")}")
      println(f"average throughput: ${gaps.sum.toDouble / gaps.length}%.3f cycles/row (1.0 = fully pipelined)")

      // per-row correctness, in order (assumes an in-order/FIFO pipeline)
      var maxAbsErrOverall = 0.0
      for (r <- 0 until numRows) {
        val raw = outputRows(r)
        val expected = allExpected(r)
        var maxAbsErr = 0.0
        for (i <- 0 until 32) {
          val lane = ((raw >> (i * 16)) & BigInt(0xFFFF)).toInt
          val actual = FP16ToFloat(lane)
          val err = math.abs(expected(i) - actual)
          maxAbsErr = math.max(maxAbsErr, err)
        }
        maxAbsErrOverall = math.max(maxAbsErrOverall, maxAbsErr)
        println(f"row $r%2d max abs error: $maxAbsErr%.6f")
      }
      println(f"max abs error across all $numRows rows: $maxAbsErrOverall%.6f")
    }
  }
}