module PE (
    input  logic [15:0] a,
    input  logic [15:0] b,
    output logic [15:0] out,
    input  logic        clk,
    input  logic        rst,
    input  logic        mode,
    output  logic [15:0]     out_a  ,
    output logic [15:0] out_b
);

  localparam logic INT8 = 1'b0;
  localparam logic FP16 = 1'b1;

  // ---------------------------------------------------------------
  // Accumulator mantissa format (18 bits):
  //   [17:14] carry headroom
  //   [13]    implicit 1 when normalized
  //   [12:3]  10 mantissa bits
  //   [2:0]   guard bits
  // ---------------------------------------------------------------
  localparam int IMPL_BIT = 13;

  // ================= combinational field extraction =================
  logic        a_sign, b_sign;
  logic [11:0] mag_a, mag_b;

  always_comb begin
    if (mode == FP16) begin
      a_sign = a[15];  mag_a = {2'b01, a[9:0]};
      b_sign = b[15];  mag_b = {2'b01, b[9:0]};
    end else begin
      a_sign = a[7];   mag_a = {4'b0, (a[7] ? -a[7:0] : a[7:0])};
      b_sign = b[7];   mag_b = {4'b0, (b[7] ? -b[7:0] : b[7:0])};
    end
  end

  // ============================ S1 ==================================
  // field extraction & 12x12 multiply (shared by both modes)
  logic               s1_sign, s1_mode;
  logic signed [7:0]  s1_exp;
  logic [23:0]        s1_common_prod;

  always_ff @(posedge clk) begin
    if (rst) begin
      s1_sign <= 1'b0;  s1_mode <= INT8;
      s1_exp  <= '0;    s1_common_prod <= '0;
    end else begin
      s1_sign        <= a_sign ^ b_sign;
      s1_common_prod <= mag_a * mag_b;
      s1_mode        <= mode;
      // exponent only meaningful in FP16 — gated to stop INT8 garbage/toggling
      s1_exp <= (mode == FP16)
                ? ($signed({3'b0, a[14:10]}) + $signed({3'b0, b[14:10]}) - 8'sd15)
                : 8'sd0;
    end
  end

  // ============================ S2 ==================================
  // FP16 product normalization: product of two 1.f values is in [1,4),
  // so at most one right shift is needed to bring it back to [1,2).
  logic               s2_sign, s2_mode;
  logic signed [7:0]  s2_exp;
  logic [13:0]        s2_mant14;      // {implicit, 10 mant, 3 guard}
  logic signed [15:0] s2_int_prod;    // signed INT8xINT8 product

  logic [23:0]        s1_norm;
  always_comb s1_norm = s1_common_prod[21] ? (s1_common_prod >> 1) : s1_common_prod;

  always_ff @(posedge clk) begin
    if (rst) begin
      s2_sign <= 1'b0;  s2_mode <= INT8;
      s2_exp  <= '0;    s2_mant14 <= '0;  s2_int_prod <= '0;
    end else begin
      s2_sign   <= s1_sign;
      s2_mode   <= s1_mode;
      s2_exp    <= s1_common_prod[21] ? (s1_exp + 8'sd1) : s1_exp;
      s2_mant14 <= s1_norm[20:7];     // implicit 1 lands at MSB
      // INT8: convert sign-magnitude product to two's complement
      s2_int_prod <= s1_sign ? -$signed({1'b0, s1_common_prod[14:0]})
                             :  $signed({1'b0, s1_common_prod[14:0]});
    end
  end

  // ============================ S3 ==================================
  // alignment & addition — this is the persistent accumulator
  logic [17:0]        acc_mant;
  logic signed [7:0]  acc_exp;
  logic               acc_sign;
  logic signed [15:0] int_acc;
  logic               s3_mode;

  logic [17:0]        acc_mant_n, prod_ext, a_al, p_al;
  logic signed [7:0]  acc_exp_n, exp_al, exp_diff;
  logic               acc_sign_n, sum_sign;
  logic [18:0]        sum19;
  logic signed [15:0] int_acc_n;
  logic [7:0]         shamt;

  always_comb begin
    acc_mant_n = acc_mant;
    acc_exp_n  = acc_exp;
    acc_sign_n = acc_sign;
    int_acc_n  = int_acc;
    prod_ext   = {4'b0, s2_mant14};
    exp_diff   = s2_exp - acc_exp;
    a_al = '0; p_al = '0; exp_al = '0; sum19 = '0; sum_sign = 1'b0; shamt = '0;

    if (s2_mode == FP16) begin
      if (acc_mant == '0) begin
        // empty accumulator: load directly, don't treat acc_exp=0 as real
        acc_mant_n = prod_ext;
        acc_exp_n  = s2_exp;
        acc_sign_n = s2_sign;
      end else begin
        // ---- align to the larger exponent ----
        if (exp_diff > 0) begin
          shamt  = (exp_diff > 18) ? 8'd18 : exp_diff[7:0];
          a_al   = acc_mant >> shamt;
          p_al   = prod_ext;
          exp_al = s2_exp;
        end else begin
          shamt  = ((-exp_diff) > 18) ? 8'd18 : (-exp_diff);
          a_al   = acc_mant;
          p_al   = prod_ext >> shamt;
          exp_al = acc_exp;
        end

        // ---- sign-aware add / subtract ----
        if (acc_sign == s2_sign) begin
          sum19    = {1'b0, a_al} + {1'b0, p_al};
          sum_sign = acc_sign;
        end else if (a_al >= p_al) begin
          sum19    = {1'b0, a_al} - {1'b0, p_al};
          sum_sign = acc_sign;
        end else begin
          sum19    = {1'b0, p_al} - {1'b0, a_al};
          sum_sign = s2_sign;
        end

        // ---- carry-out fixup ----
        if (sum19[18]) begin
          acc_mant_n = sum19[18:1];
          acc_exp_n  = exp_al + 8'sd1;
        end else begin
          acc_mant_n = sum19[17:0];
          acc_exp_n  = exp_al;
        end
        acc_sign_n = sum_sign;
      end
    end else begin
      int_acc_n = int_acc + s2_int_prod;
    end
  end

  always_ff @(posedge clk) begin
    if (rst) begin
      acc_mant <= '0;  acc_exp <= '0;  acc_sign <= 1'b0;
      int_acc  <= '0;  s3_mode <= INT8;
    end else begin
      acc_mant <= acc_mant_n;
      acc_exp  <= acc_exp_n;
      acc_sign <= acc_sign_n;
      int_acc  <= int_acc_n;
      s3_mode  <= s2_mode;   // tag now matches the data sitting in acc_*
    end
  end

  // ============================ S4 ==================================
  // post-add normalization, rounding, packing
  function automatic logic [4:0] lead_one_idx(input logic [17:0] v);
    lead_one_idx = 5'd0;
    for (int i = 0; i < 18; i++)
      if (v[i]) lead_one_idx = i[4:0];
  endfunction

  logic [15:0]       out_n;
  logic [4:0]        idx;
  logic [17:0]       norm;
  logic signed [7:0] e;
  logic [9:0]        mant10;
  logic              round_up;

  always_comb begin
    out_n = 16'h0000;  idx = '0;  norm = '0;  e = '0;
    mant10 = '0;  round_up = 1'b0;

    if (s3_mode == FP16) begin
      if (acc_mant == '0) begin
        out_n = {acc_sign, 15'b0};                 // signed zero
      end else begin
        idx = lead_one_idx(acc_mant);
        if (idx >= IMPL_BIT) begin
          norm = acc_mant >> (idx - IMPL_BIT);
          e    = acc_exp + $signed({3'b0, (idx - IMPL_BIT)});
        end else begin
          norm = acc_mant << (IMPL_BIT - idx);
          e    = acc_exp - $signed({3'b0, (IMPL_BIT - idx)});
        end

        mant10   = norm[12:3];
        round_up = norm[2] & (|norm[1:0] | norm[3]);   // round-to-nearest-even
        if (round_up) begin
          if (&mant10) begin mant10 = 10'd0; e = e + 8'sd1; end
          else               mant10 = mant10 + 10'd1;
        end

        if (e <= 0)          out_n = {acc_sign, 15'b0};                   // flush underflow
        else if (e >= 31)    out_n = {acc_sign, 5'b11110, 10'h3FF};       // saturate
        else                 out_n = {acc_sign, e[4:0], mant10};
      end
    end else begin
      out_n = int_acc;
    end
  end

  always_ff @(posedge clk) begin
    if (rst) out <= 16'h0000;
    else     out <= out_n;
  end
always_ff @(posedge clk) begin
    if (rst) begin
        out_a <= 16'b0;
        out_b <= 16'b0;
    end else begin
        out_a <= a;
        out_b <= b;
    end
end
endmodule : PE