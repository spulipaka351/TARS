// package Tars

// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec

// class SP_TEST extends AnyFlatSpec with ChiselScalatestTester {

//   "this test" should "pass" in {

//     test(new Scratchpad(32, 16, 4))
//       .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>

//       val buffer =
//         Array.tabulate(32)(i => BigInt(i * 3 + 7))

//       dut.io.wen.poke(true.B)

//       for (addr <- 0 until 32) {

//         dut.io.waddr.poke(addr.U)
//         dut.io.wdata.poke(buffer(addr).U)

//         dut.clock.step()
//       }

//       dut.io.wen.poke(false.B)
//       dut.io.ren.poke(true.B)

//       for (base <- 0 until 32 by 4) {

//         // AGU-style access pattern
//         for (lane <- 0 until 4) {
//           dut.io.raddr(lane).poke((base + lane).U)
//         }

//         dut.clock.step()

//         // SyncReadMem has one-cycle latency,
//         // so this output corresponds to the request
//         // made in the previous cycle.
//         for (lane <- 0 until 4) {

//           val expected =
//             buffer(base + lane)

//           val actual =
//             dut.io.rdata(lane).peek().litValue

//           println(
//             s"addr=${base + lane}, " +
//             s"expected=$expected, " +
//             s"got=$actual"
//           )

//           dut.io.rdata(lane).expect(expected.U)
//         }
//       }

//       dut.io.ren.poke(false.B)
//     }
//   }
// }