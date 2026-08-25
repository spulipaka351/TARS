package Tars

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.io.Source
import java.io.File

/**
 * Golden-vector testbench for ACCEL_ATTN.
 *
 * Reads FP16 inputs and float64 expected outputs produced by
 * gen_golden.py, so the reference model lives in PyTorch rather than
 * being reimplemented (and independently bugged) in Scala.
 *
 * Run gen_golden.py first; it writes ./golden/.
 */
class ACCEL_ATTN_GOLDEN extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val DEPTH = 4096
  val DW    = 16
  val BUS   = 32
  val N     = 32
  val K     = 32

  val REGION_WORDS = DEPTH / 4
  val REGION_BEATS = REGION_WORDS / BUS

  val QBASE = 0
  val KBASE = 1024
  val VBASE = 2048
  val SBASE = 3072

  val GOLDEN_DIR = "golden"

  // ============================================================
  // Golden file loading
  // ============================================================

  def goldenFile(name: String): File = {
    val f = new File(s"$GOLDEN_DIR/$name")
    assert(f.exists(),
      s"missing $f -- run gen_golden.py first (it writes ./$GOLDEN_DIR/)")
    f
  }

  /** FP16 bit patterns, row-major: line i*K + j is element (i, j). */
  def loadHex(name: String): Array[Int] = {
    val src = Source.fromFile(goldenFile(name))
    try {
      val words = src.getLines()
        .map(_.trim).filter(_.nonEmpty)
        .map(s => Integer.parseInt(s, 16) & 0xFFFF)
        .toArray
      assert(words.length == BUS * K,
        s"$name has ${words.length} words, expected ${BUS * K}")
      words
    } finally src.close()
  }

  def loadGolden(name: String): Array[Array[Double]] = {
    val src = Source.fromFile(goldenFile(name))
    try {
      val vals = src.getLines()
        .map(_.trim).filter(_.nonEmpty)
        .map(_.toDouble)
        .toArray
      assert(vals.length == BUS * BUS,
        s"$name has ${vals.length} values, expected ${BUS * BUS}")
      Array.tabulate(BUS, BUS)((i, j) => vals(i * BUS + j))
    } finally src.close()
  }

  // out_sum lanes: FP16 in the low 16 bits of a 32-bit field.
  def fp16BitsToFloat(bits: BigInt): Float = {
    val fp16     = bits.toInt & 0xFFFF
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
      java.lang.Float.intBitsToFloat((sign << 31) | ((exponent + 112) << 23) | (mantissa << 13))
    }
  }

  // ============================================================
  // Drive
  // ============================================================

  def loadQKV(dut: ACCEL_ATTN, q: Array[Int], k: Array[Int], v: Array[Int]): Unit = {
    dut.io.Q_base.poke(QBASE.U)
    dut.io.K_base.poke(KBASE.U)
    dut.io.V_base.poke(VBASE.U)
    dut.io.S_base.poke(SBASE.U)
    dut.io.sa_mode.poke(true.B)

    dut.io.res.poke(true.B)
    dut.io.en.poke(false.B)
    dut.clock.step(1)
    dut.io.res.poke(false.B)

    // Region layout matches the file layout exactly: word `off` of the
    // region is line `off` of the hex file, so no index juggling.
    for (mat <- Seq(q, k, v)) {
      for (beat <- 0 until REGION_BEATS) {
        for (b <- 0 until BUS) {
          dut.io.data_in(b).poke(mat(beat * BUS + b).U)
        }
        dut.io.en.poke(true.B)
        dut.clock.step(1)
      }
    }
    dut.io.en.poke(false.B)
  }

  def waitForDone(dut: ACCEL_ATTN, guardLimit: Int = 3000): Int = {
    var guard = 0
    var fired = -1
    while (fired < 0 && guard < guardLimit) {
      if (dut.io.done.peek().litToBoolean) fired = guard
      dut.clock.step(1)
      guard += 1
    }
    assert(fired >= 0,
      s"never pulsed done within $guardLimit cycles " +
      s"(stuck in state ${dut.io.dbg_state.peek().litValue})")
    fired
  }

  def readOut(dut: ACCEL_ATTN): Array[Array[Double]] =
    Array.tabulate(BUS, BUS) { (i, j) =>
      fp16BitsToFloat(dut.io.out_sum(i)(j).peek().litValue).toDouble
    }

  def report(tag: String, got: Array[Array[Double]], exp: Array[Array[Double]]): Double = {
    var maxErr = 0.0
    var sumErr = 0.0
    var wi = 0; var wj = 0
    for (i <- 0 until BUS; j <- 0 until BUS) {
      val e = math.abs(got(i)(j) - exp(i)(j))
      sumErr += e
      if (e > maxErr) { maxErr = e; wi = i; wj = j }
    }
    println(f"[$tag] max abs error = $maxErr%.6f at ($wi, $wj): " +
            f"expected=${exp(wi)(wj)}%+.6f actual=${got(wi)(wj)}%+.6f")
    println(f"[$tag] mean abs error = ${sumErr / (BUS * BUS)}%.6f")
    maxErr
  }

  // ============================================================
  // TEST: full attention against the PyTorch golden
  // ============================================================

  "ACCEL_ATTN" should "match the PyTorch golden for softmax(Q*K^T)*V" in {

    val q = loadHex("q.hex")
    val k = loadHex("k.hex")
    val v = loadHex("v.hex")
    val oGolden = loadGolden("o_golden.txt")

    test(new ACCEL_ATTN(DEPTH, DW, BUS, N, K))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>

        loadQKV(dut, q, k, v)
        val cycles = waitForDone(dut)
        println(s"done after $cycles cycles")

        val got = readOut(dut)

        for (j <- 0 until 6) {
          println(f"O[0][$j]  expected=${oGolden(0)(j)}%+9.5f  actual=${got(0)(j)}%+9.5f")
        }

        // Tolerance is EMPIRICAL. Three error sources stack: FP16
        // accumulation over 32 terms in Q*K^T, the softmax LUT (~0.026
        // standalone), then FP16 accumulation again in S*V. Read the
        // printed numbers before deciding a failure is a real bug --
        // a dataflow error gives garbage, not slightly-loose values.
        val maxErr = report("O", got, oGolden)
        maxErr should be < 0.05
      }
  }

  // ============================================================
  // TEST: intermediate S, isolating which matmul is at fault.
  //
  // Runs the same golden inputs but with V = identity, so S*V == S and
  // out_sum should equal softmax(Q*K^T) directly. If the full test above
  // fails but this passes, the fault is in the S*V stage, not in Q*K^T
  // or the softmax. It also doubles as the accumulator-clearing check:
  // every row of S sums to 1.0, so contamination from an uncleared
  // Q*K^T is immediately visible in the row sums.
  // ============================================================

  it should "match the golden softmax when V is the identity" in {

    val q = loadHex("q.hex")
    val k = loadHex("k.hex")
    val sGolden = loadGolden("s_golden.txt")

    // FP16 identity: 1.0 is 0x3C00.
    val vIdent = Array.tabulate(BUS * K) { idx =>
      if (idx / K == idx % K) 0x3C00 else 0x0000
    }

    test(new ACCEL_ATTN(DEPTH, DW, BUS, N, K))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>

        loadQKV(dut, q, k, vIdent)
        waitForDone(dut)

        val got = readOut(dut)

        var worstRowSum = 0.0
        for (i <- 0 until BUS) {
          worstRowSum = math.max(worstRowSum, math.abs(got(i).sum - 1.0))
        }
        println(f"worst |rowSum - 1.0| = $worstRowSum%.4f")

        if (worstRowSum > 0.5) {
          println("*** Row sums far from 1.0 -- sa.io.res is probably NOT")
          println("*** clearing the PE accumulators, so S*V is accumulating")
          println("*** on top of Q*K^T. Switch sClear to use load_bias.")
        }

        val maxErr = report("S", got, sGolden)
        worstRowSum should be < 0.05
        maxErr should be < 0.05
      }
  }
}