
module Exponential #(
    parameter logic [20:0] SCALE_Q20 = 21'd267423   // default: d = 32
)(
    input  logic        clk,
    input  logic        rst,
    input  logic [15:0] x,
    output logic [15:0] y
);

    logic [10:0] pow2_lut [0:32];
    initial begin
        pow2_lut[0]  = 11'd0;    pow2_lut[1]  = 11'd22;   pow2_lut[2]  = 11'd45;
        pow2_lut[3]  = 11'd69;   pow2_lut[4]  = 11'd93;   pow2_lut[5]  = 11'd117;
        pow2_lut[6]  = 11'd142;  pow2_lut[7]  = 11'd168;  pow2_lut[8]  = 11'd194;
        pow2_lut[9]  = 11'd220;  pow2_lut[10] = 11'd248;  pow2_lut[11] = 11'd276;
        pow2_lut[12] = 11'd304;  pow2_lut[13] = 11'd333;  pow2_lut[14] = 11'd363;
        pow2_lut[15] = 11'd393;  pow2_lut[16] = 11'd424;  pow2_lut[17] = 11'd456;
        pow2_lut[18] = 11'd488;  pow2_lut[19] = 11'd521;  pow2_lut[20] = 11'd555;
        pow2_lut[21] = 11'd590;  pow2_lut[22] = 11'd625;  pow2_lut[23] = 11'd661;
        pow2_lut[24] = 11'd698;  pow2_lut[25] = 11'd736;  pow2_lut[26] = 11'd774;
        pow2_lut[27] = 11'd814;  pow2_lut[28] = 11'd854;  pow2_lut[29] = 11'd895;
        pow2_lut[30] = 11'd937;  pow2_lut[31] = 11'd980;  pow2_lut[32] = 11'd1024;
    end

    // ---- Unpack ----
    logic              x_sign;
    logic [4:0]        x_exp;
    logic [9:0]        x_mant;
    logic signed [7:0] true_exp;
    logic [10:0]       mant_full;

    assign x_sign    = x[15];
    assign x_exp     = x[14:10];
    assign x_mant    = x[9:0];
    assign true_exp  = signed'({3'b000, x_exp}) - 8'sd15;
    assign mant_full = {1'b1, x_mant};   // 1.mant, value = mant_full / 1024

    // ---- |x| in Q16 ----
    // |x| = 2^true_exp * mant_full / 1024, so |x| * 2^16 = mant_full <<
    // (true_exp + 6). Shifts are clamped so a large |x| saturates rather
    // than wrapping around.
    logic signed [8:0] sh;
    logic [31:0]       x_q16;

    assign sh = true_exp + 9'sd6;

    always_comb begin
        if (x_exp == 5'd0) begin
            x_q16 = 32'd0;                       // zero / subnormal -> x ~= 0
        end else if (sh >= 9'sd21) begin
            x_q16 = 32'hFFFFFFFF;                // |x| enormous -> saturate
        end else if (sh >= 0) begin
            x_q16 = {21'd0, mant_full} << sh[4:0];
        end else if (sh > -9'sd17) begin
            x_q16 = {21'd0, mant_full} >> (-sh);
        end else begin
            x_q16 = 32'd0;                       // |x| negligible
        end
    end

    // ---- t = |x| * log2(e)/sqrt(d), Q36 ----
    logic [52:0] t_q36;
    assign t_q36 = x_q16 * SCALE_Q20;    // Q16 * Q20 = Q36

    logic [16:0] n_int;                  // integer part of t
    logic [35:0] frac;                   // fractional part, Q36

    assign n_int = t_q36[52:36];
    assign frac  = t_q36[35:0];

    // ---- u = 1 - frac, then m = (2^u - 1) * 1024 ----
    // frac == 0 is the exact-power-of-two case: u = 1, 2^u = 2, which
    // would overflow the mantissa. Folded into the exponent instead.
    logic        exact_pow2;
    logic [35:0] u_q36;
    logic [4:0]  u_idx;
    logic [7:0]  u_fr;
    logic [10:0] lut_lo, lut_hi;
    logic [5:0]  step;
    logic [13:0] scaled;
    logic [10:0] m;

    assign exact_pow2 = (frac == 36'd0);
    assign u_q36      = 36'd0 - frac;     // 2^36 - frac, exact for frac != 0
    assign u_idx      = u_q36[35:31];
    assign u_fr       = u_q36[30:23];
    assign lut_lo     = pow2_lut[u_idx];
    assign lut_hi     = pow2_lut[u_idx + 5'd1];
    assign step       = lut_hi[5:0] - lut_lo[5:0];   // table ascends, step <= 44
    assign scaled     = step * u_fr;
    assign m          = lut_lo + {5'd0, scaled[13:8]};

    // ---- Exponent ----
    // y = 2^(-n-1) * 2^u, so the biased exponent is 15 - n - 1 = 14 - n.
    // In the exact-power-of-two case the mantissa is 1.0 and the exponent
    // is one higher.
    logic signed [19:0] exp_calc;
    assign exp_calc = exact_pow2 ? (20'sd15 - signed'({3'b0, n_int}))
                                 : (20'sd14 - signed'({3'b0, n_int}));

    logic [15:0] y_next;
    always_comb begin
        if (x_sign == 1'b0) begin
            // x >= 0 should not occur after the softmax max-subtraction;
            // clamp to e^0 = 1.0, as the original did.
            y_next = 16'h3C00;
        end else if (exp_calc <= 20'sd0) begin
            y_next = 16'd0;                       // underflow to zero
        end else if (exp_calc >= 20'sd31) begin
            y_next = 16'h7BFF;                    // saturate below Inf
        end else if (exact_pow2) begin
            y_next = {1'b0, exp_calc[4:0], 10'd0};
        end else begin
            y_next = {1'b0, exp_calc[4:0], m[9:0]};
        end
    end

    always_ff @(posedge clk) begin
        if (rst) y <= 16'd0;
        else     y <= y_next;
    end

endmodule : Exponential


module FP16Max(
    input logic clk,
    input logic rst,
    input logic [15:0]a,
    input logic [15:0]b,

    output logic [15:0] out
);
logic sgn_a,sgn_b;
logic [15:0]out_reg;
logic [14:0] mag_a, mag_b;
assign sgn_a = a[15];
assign sgn_b = b[15];
assign mag_a = a[14:0];
assign mag_b = b[14:0];
always_comb begin
    if (sgn_a != sgn_b) begin
        out_reg = (sgn_a == 1'b0) ? a : b;
    end else if (sgn_a == 1'b0) begin
        out_reg = (mag_a >= mag_b) ? a : b;
    end else begin
        out_reg = (mag_a <= mag_b) ? a : b;
    end
end

always@(posedge clk)begin
 if(rst)
    out<=16'd0;
else
    out <=out_reg;

end
endmodule


module FP16AddSub(
    input  logic         clk,
    input  logic         rst,
    input  logic [15:0]  a,
    input  logic [15:0]  b,
    input  logic         sub,     // 0 = a+b, 1 = a-b
    output logic [15:0]  out
);

    // ---------------- Stage 0 (comb): unpack + effective sign ----------------
    logic        s0_sign_a, s0_sign_b_eff;
    logic [4:0]  s0_exp_a,  s0_exp_b;
    logic [10:0] s0_mant_a, s0_mant_b;   // {implicit 1, 10-bit mantissa}

    assign s0_sign_a     = a[15];
    assign s0_sign_b_eff = sub ? ~b[15] : b[15];   // subtract = add negated b
    assign s0_exp_a      = a[14:10];
    assign s0_exp_b      = b[14:10];
    assign s0_mant_a      = {(a[14:10] != 5'd0), a[9:0]};  // 0 implicit bit if exp==0 (treat as zero, no subnormals)
    assign s0_mant_b      = {(b[14:10] != 5'd0), b[9:0]};

    // ---------------- Stage 1 (registered): align exponents ----------------
    logic        s1_sign_big, s1_sign_small;
    logic [4:0]  s1_exp_big;
    logic [10:0] s1_mant_big, s1_mant_small_aligned;
    logic        s1_same_sign;

    always_ff @(posedge clk) begin
        if (rst) begin
            {s1_sign_big, s1_sign_small, s1_exp_big, s1_mant_big, s1_mant_small_aligned, s1_same_sign} <= '0;
        end else begin
            logic a_ge_b;
            logic [4:0] exp_diff;
            a_ge_b = (s0_exp_a > s0_exp_b) ||
                     ((s0_exp_a == s0_exp_b) && (s0_mant_a >= s0_mant_b));

            if (a_ge_b) begin
                exp_diff              = s0_exp_a - s0_exp_b;
                s1_exp_big            <= s0_exp_a;
                s1_sign_big           <= s0_sign_a;
                s1_sign_small         <= s0_sign_b_eff;
                s1_mant_big           <= s0_mant_a;
                s1_mant_small_aligned <= (exp_diff >= 5'd11) ? 11'd0 : (s0_mant_b >> exp_diff);
            end else begin
                exp_diff              = s0_exp_b - s0_exp_a;
                s1_exp_big            <= s0_exp_b;
                s1_sign_big           <= s0_sign_b_eff;
                s1_sign_small         <= s0_sign_a;
                s1_mant_big           <= s0_mant_b;
                s1_mant_small_aligned <= (exp_diff >= 5'd11) ? 11'd0 : (s0_mant_a >> exp_diff);
            end
            s1_same_sign <= (s0_sign_a == s0_sign_b_eff);
        end
    end

    // ---------------- Stage 2 (registered): add/sub mantissas ----------------
    logic        s2_sign;
    logic [4:0]  s2_exp;
    logic [11:0] s2_mant;   // extra bit for carry-out on add

    always_ff @(posedge clk) begin
        if (rst) begin
            {s2_sign, s2_exp, s2_mant} <= '0;
        end else begin
            s2_exp  <= s1_exp_big;
            s2_sign <= s1_sign_big;
            if (s1_same_sign)
                s2_mant <= {1'b0, s1_mant_big} + {1'b0, s1_mant_small_aligned};
            else
                s2_mant <= {1'b0, s1_mant_big} - {1'b0, s1_mant_small_aligned};
        end
    end

    // ---------------- Stage 3 (registered): normalize + pack ----------------
    always_ff @(posedge clk) begin
        if (rst) begin
            out <= 16'd0;
        end else begin
            logic [4:0]  exp_norm;
            logic [10:0] mant_norm;
            logic [3:0]  lead_zeros;
            int          k;

            if (s2_mant[11]) begin
                // carry-out from addition: shift right 1, exponent +1
                exp_norm  = s2_exp + 5'd1;
                mant_norm = s2_mant[11:1];
            end else if (s2_mant[10]) begin
                // already normalized
                exp_norm  = s2_exp;
                mant_norm = s2_mant[10:0];
            end else begin
                // leading zeros from cancellation: find MSB, shift left
                lead_zeros = 4'd0;
                for (k = 9; k >= 0; k--)
                    if (!lead_zeros && s2_mant[k]) lead_zeros = 4'(10 - k);
                if (s2_mant == 12'd0) begin
                    exp_norm  = 5'd0;
                    mant_norm = 11'd0;
                end else if (s2_exp > lead_zeros) begin
                    exp_norm  = s2_exp - lead_zeros;
                    mant_norm = (s2_mant << lead_zeros) & 11'h7FF;
                end else begin
                    // underflow to zero (no subnormal support)
                    exp_norm  = 5'd0;
                    mant_norm = 11'd0;
                end
            end

            out <= (mant_norm == 11'd0 && exp_norm == 5'd0)
                     ? 16'd0
                     : {s2_sign, exp_norm, mant_norm[9:0]};
        end
    end

endmodule



module MaxTree(
    input logic clk,
    input logic rst,
    input logic [15:0]row[31:0],
    output logic [15:0] out

);

logic [15:0]l1[15:0];
logic [15:0]l2[7:0];
logic [15:0]l3[3:0];
logic [15:0]l4[1:0];
genvar i ;
       
generate 
for(i=0;i<16;i++)begin
    FP16Max r(
        clk,rst,
        row[2*i],row[2*i+1],
        l1[i]
    );

end

for(i=0;i<8;i++)begin
    FP16Max r(
        clk,rst,
        l1[2*i],l1[2*i+1],
        
        l2[i]
    );

end
for(i=0;i<4;i++)begin
    FP16Max r(
        clk,rst,
        l2[2*i],l2[2*i+1],
        
        l3[i]
    );

end
for(i=0;i<2;i++)begin
    FP16Max r(
        clk,rst,
        l3[2*i],l3[2*i+1],
        
        l4[i]
    );

end


endgenerate
FP16Max f1(
    clk,rst,
    l4[0],l4[1],

    out
);

endmodule

module AdderTree(
    input logic clk,
    input logic rst,
    input logic [15:0]row[31:0],
    output logic [15:0] out

);

logic [15:0]l1[15:0];
logic [15:0]l2[7:0];
logic [15:0]l3[3:0];
logic [15:0]l4[1:0];
genvar i ;
       
generate 
for(i=0;i<16;i++)begin
    FP16AddSub r(
        clk,rst,
        row[2*i],row[2*i+1],
        1'b0,
        l1[i]
    );

end

for(i=0;i<8;i++)begin
    FP16AddSub r(
        clk,rst,
        l1[2*i],l1[2*i+1],
        1'b0,
        l2[i]
    );

end
for(i=0;i<4;i++)begin
    FP16AddSub r(
        clk,rst,
        l2[2*i],l2[2*i+1],
        1'b0,
        l3[i]
    );

end
for(i=0;i<2;i++)begin
    FP16AddSub r(
        clk,rst,
        l3[2*i],l3[2*i+1],
        1'b0,
        l4[i]
    );

end


endgenerate
FP16AddSub f2(
    clk,rst,
    l4[0],l4[1],
    1'b0,
    out
);

endmodule

module FP16Multiplier(
    input  logic         clk,
    input  logic         rst,
    input  logic [15:0]  a,
    input  logic [15:0]  b,
    output logic [15:0]  out
);

    // ---- Stage 0 (comb): unpack ----
    logic        s0_sign_a, s0_sign_b;
    logic [4:0]  s0_exp_a,  s0_exp_b;
    logic [10:0] s0_mant_a, s0_mant_b;    // {implicit 1, 10-bit mantissa}
    logic        s0_zero;

    assign s0_sign_a = a[15];
    assign s0_sign_b = b[15];
    assign s0_exp_a  = a[14:10];
    assign s0_exp_b  = b[14:10];
    assign s0_mant_a = {(a[14:10] != 5'd0), a[9:0]};   // no subnormals: exp==0 -> treated as zero
    assign s0_mant_b = {(b[14:10] != 5'd0), b[9:0]};
    assign s0_zero   = (a[14:10] == 5'd0) || (b[14:10] == 5'd0);

    // ---- Stage 1 (registered): sign, exponent sum, mantissa multiply ----
    logic        s1_sign, s1_zero;
    logic [5:0]  s1_exp_sum;      // ea+eb, up to 62, fits 6 bits
    logic [21:0] s1_product;      // 11x11 -> Q2.20, range [1,4) in this encoding

    always_ff @(posedge clk) begin
        if (rst) begin
            {s1_sign, s1_zero, s1_exp_sum, s1_product} <= '0;
        end else begin
            s1_sign    <= s0_sign_a ^ s0_sign_b;
            s1_zero    <= s0_zero;
            s1_exp_sum <= {1'b0, s0_exp_a} + {1'b0, s0_exp_b};
            s1_product <= s0_mant_a * s0_mant_b;
        end
    end

    // ---- Stage 2 (registered): normalize ----
    logic        s2_sign, s2_zero;
    logic [4:0]  s2_exp;
    logic [9:0]  s2_mant;

    always_ff @(posedge clk) begin
        if (rst) begin
            {s2_sign, s2_zero, s2_exp, s2_mant} <= '0;
        end else begin
            logic [6:0] exp_unbiased;   // signed-ish, bias-corrected below
            s2_sign <= s1_sign;
            s2_zero <= s1_zero;

            if (s1_product[21]) begin
                // product in [2,4): normalize by shifting right 1, exponent +1
                exp_unbiased = {1'b0, s1_exp_sum} - 7'd15 + 7'd1;
                s2_exp  <= exp_unbiased[4:0];
                s2_mant <= s1_product[20:11];
            end else begin
                // product in [1,2): already normalized
                exp_unbiased = {1'b0, s1_exp_sum} - 7'd15;
                s2_exp  <= exp_unbiased[4:0];
                s2_mant <= s1_product[19:10];
            end
        end
    end

    // ---- Stage 3 (registered): pack ----
    always_ff @(posedge clk) begin
        if (rst)
            out <= 16'd0;
        else
            out <= s2_zero ? 16'd0 : {s2_sign, s2_exp, s2_mant};
    end

endmodule

// ==================================================================
// Reciprocal, linearly interpolated.
//
// Replaces the 32-bucket nearest-neighbour lookup, whose relative error
// was |df|/(1+f) with |df| <= 1/64, i.e. 0.78% at f=1 up to 1.56% at
// f=0. In softmax that error is NOT noise: every element of a row is
// scaled by the same reciprocal of the same denominator, so it appears
// as a coherent ~1% bias on the whole row (row sums land at 1.01
// instead of 1.00) and is then amplified by |V| in S*V.
//
// Linear interpolation drops the error from O(h) to O(h^2):
//   h^2/8 * max|d2/df2 (1/(1+f))| = (1/1024)/8 * 2 ~= 0.024%
// which is at the FP16 mantissa noise floor (~0.1% ULP), so a larger
// table would buy nothing.
//
// LATENCY IS UNCHANGED at 3 registered stages -- Softmax.sv's out_valid
// timing depends on it, so this is a drop-in replacement.
// ==================================================================
module Reciprocal(
    input  logic         clk,
    input  logic         rst,
    input  logic [15:0]  a,      // assumed strictly positive, nonzero
    output logic [15:0]  out
);

    // ---- 33-entry LUT: 1/(1+idx/32) in 11-bit fixed point, scaled 2048
    //      LUT[idx] = round(65536 / (32 + idx)), range [1024, 2048]
    //      33 entries (not 32) so interpolation has a right-hand endpoint.
    logic [11:0] recip_lut [0:32];
    initial begin
        recip_lut[0]  = 12'd2048; recip_lut[1]  = 12'd1986; recip_lut[2]  = 12'd1928;
        recip_lut[3]  = 12'd1872; recip_lut[4]  = 12'd1820; recip_lut[5]  = 12'd1771;
        recip_lut[6]  = 12'd1725; recip_lut[7]  = 12'd1680; recip_lut[8]  = 12'd1638;
        recip_lut[9]  = 12'd1598; recip_lut[10] = 12'd1560; recip_lut[11] = 12'd1524;
        recip_lut[12] = 12'd1489; recip_lut[13] = 12'd1456; recip_lut[14] = 12'd1425;
        recip_lut[15] = 12'd1394; recip_lut[16] = 12'd1365; recip_lut[17] = 12'd1337;
        recip_lut[18] = 12'd1311; recip_lut[19] = 12'd1285; recip_lut[20] = 12'd1260;
        recip_lut[21] = 12'd1237; recip_lut[22] = 12'd1214; recip_lut[23] = 12'd1192;
        recip_lut[24] = 12'd1170; recip_lut[25] = 12'd1150; recip_lut[26] = 12'd1130;
        recip_lut[27] = 12'd1111; recip_lut[28] = 12'd1092; recip_lut[29] = 12'd1074;
        recip_lut[30] = 12'd1057; recip_lut[31] = 12'd1040; recip_lut[32] = 12'd1024;
    end

    // ---- Stage 0 (comb): unpack, TRUNCATE to bucket + keep the fraction ----
    // Truncation (not rounding) is deliberate here: interpolation needs the
    // left endpoint and the offset within the bucket, not a nearest bucket.
    logic       s0_sign;
    logic [4:0] s0_exp;
    logic [4:0] s0_idx;    // mant[9:5]
    logic [4:0] s0_frac;   // mant[4:0], position within the bucket

    assign s0_sign = a[15];
    assign s0_exp  = a[14:10];
    assign s0_idx  = a[9:5];
    assign s0_frac = a[4:0];

    // ---- Stage 1 (registered): read both endpoints ----
    logic        s1_sign;
    logic [4:0]  s1_exp;
    logic [4:0]  s1_frac;
    logic [11:0] s1_lo;    // LUT[idx]
    logic [11:0] s1_hi;    // LUT[idx+1]

    always_ff @(posedge clk) begin
        if (rst) begin
            s1_sign <= 1'b0;
            s1_exp  <= 5'd0;
            s1_frac <= 5'd0;
            s1_lo   <= 12'd0;
            s1_hi   <= 12'd0;
        end else begin
            s1_sign <= s0_sign;
            s1_exp  <= s0_exp;
            s1_frac <= s0_frac;
            s1_lo   <= recip_lut[s0_idx];
            s1_hi   <= recip_lut[s0_idx + 5'd1];  // safe: idx <= 31, LUT has 33
        end
    end

    // ---- Stage 2 (registered): interpolate + exponent ----
    // The table is monotonically decreasing, so lo >= hi and the step is
    // a small positive number (max 62 at idx=0, fits 6 bits). The
    // multiplier is therefore 6x5 bits -- cheap.
    logic        s2_sign;
    logic [4:0]  s2_exp;
    logic [9:0]  s2_mant;

    always_ff @(posedge clk) begin
        if (rst) begin
            s2_sign <= 1'b0;
            s2_exp  <= 5'd0;
            s2_mant <= 10'd0;
        end else begin
            logic [5:0]  step;       // lo - hi, <= 62
            logic [10:0] scaled;     // step * frac
            logic [11:0] r_fixed;    // interpolated 1/(1+f) * 2048
            logic [6:0]  exp_calc;   // signed-ish headroom for clamping

            step    = s1_lo[5:0] - s1_hi[5:0];
            scaled  = step * s1_frac;
            r_fixed = s1_lo - {1'b0, scaled[10:5]};   // lo - step*frac/32

            s2_sign <= s1_sign;

            // r_fixed lies in [1024, 2048].
            //   == 2048 means exactly 1.0  -> mantissa 0, no binade shift
            //   otherwise it is 2^-1 * (r_fixed/1024) -> mantissa r_fixed-1024
            if (r_fixed >= 12'd2048) begin
                s2_mant  = 10'd0;
                exp_calc = 7'd30 - {2'b0, s1_exp};
            end else begin
                s2_mant  = r_fixed[9:0];             // r_fixed - 1024
                exp_calc = 7'd29 - {2'b0, s1_exp};
            end

            // Clamp instead of silently wrapping. The original truncated
            // exp_calc[4:0], so a large input (s1_exp >= 30) aliased into
            // the Inf/NaN exponent. Softmax denominators never get there,
            // but wrapping to Inf is a bad failure mode to leave armed.
            if (exp_calc[6])            s2_exp <= 5'd0;   // underflow -> zero
            else if (exp_calc > 7'd30)  s2_exp <= 5'd30;  // saturate below Inf
            else                        s2_exp <= exp_calc[4:0];
        end
    end

    // ---- Stage 3 (registered): pack ----
    always_ff @(posedge clk) begin
        if (rst)
            out <= 16'd0;
        else
            out <= {s2_sign, s2_exp, s2_mant};
    end

endmodule

module DelayLine #(
    parameter int WIDTH = 16,
    parameter int DEPTH = 5
)(
    input  logic             clk,
    input  logic             rst,
    input  logic [WIDTH-1:0] in,
    output logic [WIDTH-1:0] out
);
    logic [WIDTH-1:0] pipe [0:DEPTH-1];
    integer i;

    always_ff @(posedge clk) begin
        if (rst) begin
            for (i = 0; i < DEPTH; i++) pipe[i] <= '0;
        end else begin
            pipe[0] <= in;
            for (i = 1; i < DEPTH; i++) pipe[i] <= pipe[i-1];
        end
    end

    assign out = pipe[DEPTH-1];
endmodule

module Softmax(
    input  logic          clk,
    input  logic          rst,
    input  logic          in_valid,
    input  logic [511:0]  row,

    output logic          out_valid,
    output logic [511:0]  out
);

    genvar i;

    DelayLine #(.WIDTH(1), .DEPTH(32)) valid_pipe (
        .clk(clk), .rst(rst), .in(in_valid), .out(out_valid)
    );

    // ---- keep internals UNPACKED so MaxTree/AdderTree ports still match ----
    logic [15:0] x [31:0];
    generate
        for (i = 0; i < 32; i++) begin : IN_REG
            always_ff @(posedge clk) begin
                if (rst) x[i] <= 16'h0000;
                else     x[i] <= row[i*16 +: 16];
            end
        end
    endgenerate

    logic [15:0] s1_max;
    MaxTree m (clk, rst, x, s1_max);

    logic [15:0] x_delayed [31:0];
    generate
        for (i = 0; i < 32; i++) begin : DELAY_X
            DelayLine #(.WIDTH(16), .DEPTH(5)) d (
                .clk(clk), .rst(rst), .in(x[i]), .out(x_delayed[i])
            );
        end
    endgenerate

    logic [15:0] x_cap [31:0];
    generate
        for (i = 0; i < 32; i++) begin : SUB_MAX
            FP16AddSub f1 (clk, rst, x_delayed[i], s1_max, 1'b1, x_cap[i]);
        end
    endgenerate

    logic [15:0] s3_exp [31:0];
    generate
        for (i = 0; i < 32; i++) begin : EXP_UNITS
            Exponential expu (clk, rst, x_cap[i], s3_exp[i]);
        end
    endgenerate

    logic [15:0] s4_sum;
    AdderTree a1 (clk, rst, s3_exp, s4_sum);

    logic [15:0] s5_recip;
    Reciprocal r1 (.clk(clk), .rst(rst), .a(s4_sum), .out(s5_recip));

    logic [15:0] s5_exp_delayed [31:0];
    generate
        for (i = 0; i < 32; i++) begin : DELAY_EXP
            DelayLine #(.WIDTH(16), .DEPTH(18)) d (
                .clk(clk), .rst(rst), .in(s3_exp[i]), .out(s5_exp_delayed[i])
            );
        end
    endgenerate

    logic [15:0] s6_out [31:0];
    generate
        for (i = 0; i < 32; i++) begin : MUL_UNITS
            FP16Multiplier mul (
                .clk(clk), .rst(rst),
                .a(s5_exp_delayed[i]), .b(s5_recip),
                .out(s6_out[i])
            );
        end
    endgenerate

    // ---- pack into the flat output port, per-element ----
    generate
        for (i = 0; i < 32; i++) begin : OUT_REG
            always_ff @(posedge clk) begin
                if (rst) out[i*16 +: 16] <= 16'h0000;
else     out[i*16 +: 16] <= s6_out[i];
            end
        end
    endgenerate

endmodule

