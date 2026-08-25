package SA

import chisel3._
import chisel3.util._

// 4-stage pipelined mixed-precision Processing Element
//
// Stage breakdown:
//   S1: field extract + 12x12 shared multiply + INT8 MAC + psum decode
//   S2: FP16 multiply normalise
//   S3: FP32 exponent align + add/subtract
//   S4: post-add normalise + output mux
//
// Pipeline depth: 3 RegNext boundaries → result valid 3 cycles after input.
// out_a / out_b: 3-cycle delayed pass-throughs matching pipeline depth.
// psum_in decoded at S1 (not S3) — prevents stale-psum leak on zero-flush.

class PipePE extends Module {
  val io = IO(new Bundle {
    val in_a    = Input(UInt(16.W))
    val in_b    = Input(UInt(16.W))
    val mode    = Input(Bool())        // false = INT8, true = FP16
    val psum_in = Input(UInt(32.W))

    val out   = Output(UInt(32.W))    // FP32 (FP16 mode) or INT32 (INT8 mode)
    val out_a = Output(UInt(16.W))    // in_a delayed 3 cycles
    val out_b = Output(UInt(16.W))    // in_b delayed 3 cycles
  })
  
  // ---------------------------------------------------------------
  // STAGE 1: field extract + 12x12 multiply + INT8 MAC + psum decode
  // ---------------------------------------------------------------
  val sign_a       = io.in_a(15)
  val exp_a        = io.in_a(14, 10)
  val man_a        = io.in_a(9, 0)
  val sign_b       = io.in_b(15)
  val exp_b        = io.in_b(14, 10)
  val man_b        = io.in_b(9, 0)

  val hidden_a     = exp_a.orR
  val hidden_b     = exp_b.orR
  val fp_man_a_11b = Cat(hidden_a, man_a)
  val fp_man_b_11b = Cat(hidden_b, man_b)
  val toggle       = RegInit(false.B)
  when(!io.mode) {
  toggle := ~toggle   // only toggle in INT8 mode
}.otherwise {
  toggle := false.B   // FP16: always hold false
}

  val high_int_a = io.in_a(15, 8).asSInt
  val low_int_a  = io.in_a(7, 0).asSInt
  val high_int_b = io.in_b(15, 8).asSInt
  val low_int_b  = io.in_b(7, 0).asSInt

  val int_a = Mux(toggle, high_int_a, low_int_a)
  val int_b = Mux(toggle, high_int_b, low_int_b)
 

  val shared_in_a = Mux(io.mode,
                        Cat(0.U(1.W), fp_man_a_11b).asSInt,
                        int_a.pad(12))
  val shared_in_b = Mux(io.mode,
                        Cat(0.U(1.W), fp_man_b_11b).asSInt,
                        int_b.pad(12))

  val shared_mul_result = shared_in_a * shared_in_b   // SInt(24.W)

  val a_is_zero       = !exp_a.orR && !man_a.orR
  val b_is_zero       = !exp_b.orR && !man_b.orR
  val product_is_zero = a_is_zero || b_is_zero

  val out_int_s1 = (io.psum_in.asSInt +
                    shared_mul_result(15, 0).asSInt.pad(32)).asUInt

  // decode psum fields at S1 so zero-flush propagates cleanly
  val s1_psum_sign = RegNext(io.psum_in(31),     false.B)
  val s1_psum_exp  = RegNext(io.psum_in(30, 23), 0.U)
  val s1_psum_man  = RegNext(io.psum_in(22, 0),  0.U)

  // S1 -> S2
  val s1_mul       = RegNext(shared_mul_result,        0.S)
  val s1_fp_sign   = RegNext(sign_a ^ sign_b,          false.B)
  val s1_exp_raw   = RegNext((exp_a +& exp_b).pad(8),  0.U)
  val s1_prod_zero = RegNext(product_is_zero,          false.B)
  val s1_mode      = RegNext(io.mode,                  false.B)
  val s1_out_int   = RegNext(out_int_s1,               0.U)

  // ---------------------------------------------------------------
  // STAGE 2: FP16 multiply normalise
  // ---------------------------------------------------------------
  val raw_man_s2  = s1_mul(21, 0).asUInt
  val norm_sel_s2 = raw_man_s2(21)

  val mul_norm_man = Mux(s1_prod_zero, 0.U(23.W),
                      Mux(norm_sel_s2,
                          Cat(raw_man_s2(20, 0), 0.U(2.W)),
                          Cat(raw_man_s2(19, 0), 0.U(3.W))))
  val mul_norm_exp = Mux(s1_prod_zero, 0.U(8.W),
                      Mux(norm_sel_s2,
                          s1_exp_raw + 98.U,
                          s1_exp_raw + 97.U))

  // S2 -> S3
  val s2_norm_man  = RegNext(mul_norm_man,  0.U)
  val s2_norm_exp  = RegNext(mul_norm_exp,  0.U)
  val s2_fp_sign   = RegNext(s1_fp_sign,   false.B)
  val s2_prod_zero = RegNext(s1_prod_zero, false.B)
  val s2_psum_sign = RegNext(s1_psum_sign, false.B)
  val s2_psum_exp  = RegNext(s1_psum_exp,  0.U)
  val s2_psum_man  = RegNext(s1_psum_man,  0.U)
  val s2_mode      = RegNext(s1_mode,      false.B)
  val s2_out_int   = RegNext(s1_out_int,   0.U)

  // ---------------------------------------------------------------
  // STAGE 3: FP32 exponent align + add/subtract
  // ---------------------------------------------------------------
  val psum_man_full = Cat(s2_psum_exp.orR, s2_psum_man)
  val mul_man_full  = Mux(s2_prod_zero, 0.U(24.W),
                          Cat(1.U(1.W), s2_norm_man))

  val mul_larger       = s2_norm_exp >= s2_psum_exp
  val exp_diff         = Mux(mul_larger,
                             s2_norm_exp - s2_psum_exp,
                             s2_psum_exp - s2_norm_exp)
  val common_exp       = Mux(mul_larger, s2_norm_exp, s2_psum_exp)
  val aligned_mul_man  = Mux(mul_larger, mul_man_full,
                                         mul_man_full  >> exp_diff)
  val aligned_psum_man = Mux(mul_larger, psum_man_full >> exp_diff,
                                         psum_man_full)

  val signs_match = s2_fp_sign === s2_psum_sign
  val mul_gte     = aligned_mul_man >= aligned_psum_man

  val add_result = aligned_mul_man +& aligned_psum_man
  val sub_ab     = aligned_mul_man  - aligned_psum_man
  val sub_ba     = aligned_psum_man - aligned_mul_man

  val sum_man    = Mux(signs_match, add_result,
                    Mux(mul_gte, sub_ab.pad(25), sub_ba.pad(25)))
  val final_sign = Mux(signs_match, s2_fp_sign,
                    Mux(mul_gte,    s2_fp_sign, s2_psum_sign))

  // S3 -> S4
  val s3_sum_man    = RegNext(sum_man,    0.U)
  val s3_final_sign = RegNext(final_sign, false.B)
  val s3_common_exp = RegNext(common_exp, 0.U)
  val s3_mode       = RegNext(s2_mode,   false.B)
  val s3_out_int    = RegNext(s2_out_int, 0.U)

  // ---------------------------------------------------------------
  // STAGE 4: post-add normalise + output mux
  // ---------------------------------------------------------------
  val is_zero    = s3_sum_man === 0.U
  val overflow   = s3_sum_man(24)
  val normalised = s3_sum_man(23)

  val sub_field  = s3_sum_man(22, 0)
  val hi_idx     = PriorityEncoder(Reverse(sub_field))
  val left_shift = hi_idx + 1.U

  val man_A = s3_sum_man(23, 1)
  val man_B = s3_sum_man(22, 0)
  val man_C = (sub_field.pad(47) << left_shift)(22, 0)

  val exp_A = s3_common_exp + 1.U
  val exp_B = s3_common_exp
  val exp_C = Mux(left_shift <= s3_common_exp,
                  s3_common_exp - left_shift, 0.U(8.W))

  val norm_man = Mux(is_zero,    0.U(23.W),
                 Mux(overflow,   man_A,
                 Mux(normalised, man_B, man_C)))
  val norm_exp = Mux(is_zero,    0.U(8.W),
                 Mux(overflow,   exp_A,
                 Mux(normalised, exp_B, exp_C)))

  val out_fp = Cat(s3_final_sign, norm_exp, norm_man)

  io.out   := Mux(s3_mode, out_fp, s3_out_int)
  io.out_a := ShiftRegister(io.in_a, 3)
  io.out_b := ShiftRegister(io.in_b, 3)
}